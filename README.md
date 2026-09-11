# kotoba-biomech

**Biomechanics simulation domain layer in pure Clojure.** 筋肉・骨・皮膚・
内臓の生体力学的な sim を扱う [kotoba-lang](https://github.com/kotoba-lang)
capability library.

専用 repo として存在しなかった（2026-07-19 探索確認: 既存 solver 群はあったが
人体ドメインを束ねる統合層は無かった）ため、既存の solver 資産を束ねるドメイン層
として起こした。

No network, no I/O in any `.cljc` domain namespace (the one JVM-only loader,
`kotoba.biomech.tissue-loader`, reads `resources/kami/biomech/tissues.edn` and
is split out as a `.clj` exactly like kotoba-lang/fea's `material-loader`).

## Boundary with `cloud-itonami/suji`

Two repos in this workspace implement Hill-type muscle mechanics. **The
distinction is resolution, and it is not a duplication to collapse.**

| | `kotoba-lang/biomech` (here) | `cloud-itonami/suji` |
|---|---|---|
| Scale | tissue / single muscle-tendon unit | whole body |
| Time | **dynamic** — semi-implicit Euler, sub-stepped | **static** — one posture, no time |
| Muscle | a lumped 1-D mass-spring-damper with an active element; a muscle is a *body* with mass, length and velocity | one line of action; a muscle is a *moment arm and a force*, with no mass and no state |
| Question it answers | how does this tissue move under load | what moments does this posture demand, which muscles carry them, and what disc compression results |
| Also owns | continuum tissue properties, Euler–Bernoulli beams, FEM, XPBD soft body, LBM flow | posture solving, inverse dynamics, Crowninshield–Brand force sharing, ligaments, per-level disc compression |
| Deps | three solver repos (fea, kami-vehicle, kami-engine-cfd) | stdlib only — it compiles into a browser bundle |

Neither repo depends on the other, and the last row is why: biomech's three git
dependencies are a cost suji will not pay for a three-line function, and suji's
whole-body statics are not a thing biomech has a body to run.
`suji.methods.muscle/force-length-factor` already says so in its own docstring.

### What was actually measured, 2026-09-07

Both `force-length-factor` implementations were loaded into **one JVM** and
evaluated over the same normalized-length grid; biomech's passive element was
measured by running `acceleration` with zero velocity and zero activation
(`f_passive = -m·a`), not by transcribing the formula.

**The active force–length curve is the same closed form**, `1 − 4(L/L₀ − 1)²`
clamped at 0, to floating point. Over 17 grid points from 0.40 to 1.60 × optimal
the **worst absolute difference is 6.7 × 10⁻¹⁶** — double rounding, because the
two were evaluated at different absolute scales (0.15 m vs 0.10 m optimal).

They diverge at the edges, deliberately on suji's side:

| input | biomech | suji |
|---|---|---|
| `length` nil | throws `NullPointerException` | `1.0` |
| `optimal` nil | throws `NullPointerException` | `1.0` |
| `optimal` 0.0 | `0.0` (silently: "produces nothing") | `1.0` |

suji falls back to the peak on purpose — a muscle whose length it cannot state
must not be reported as infinitely strained. biomech has no such fallback.

**The passive elements are different models and disagree by up to two orders of
magnitude.** biomech uses a linear, *bidirectional* spring about rest length;
suji uses a tension-only exponential above optimal length, normalised to 80% of
peak active force at 1.5 × optimal. Expressed as a fraction of each model's own
peak active force:

| L/L₀ | biomech (N, Fmax 1000 N) | biomech / Fmax | suji (N, peak 2040 N) | suji / peak | suji : biomech |
|---|---|---|---|---|---|
| 0.70 | −9.00 | −0.0090 | 0.00 | 0.0000 | sign disagreement |
| 0.90 | −3.00 | −0.0030 | 0.00 | 0.0000 | sign disagreement |
| 1.00 | 0.00 | 0.0000 | 0.00 | 0.0000 | both zero |
| 1.05 | 1.50 | 0.0015 | 7.18 | 0.0035 | 2.3× |
| 1.10 | 3.00 | 0.0030 | 19.02 | 0.0093 | 3.1× |
| 1.20 | 6.00 | 0.0060 | 70.73 | 0.0347 | 5.8× |
| 1.25 | 7.50 | 0.0075 | 123.80 | 0.0607 | 8.1× |
| 1.30 | 9.00 | 0.0090 | 211.30 | 0.1036 | 11.5× |
| 1.40 | 12.00 | 0.0120 | 593.38 | 0.2909 | 24.2× |
| **1.50** | **15.00** | **0.0150** | **1632.00** | **0.8000** | **53.3×** |
| 1.60 | 18.00 | 0.0180 | 4455.26 | 2.1840 | 121.3× |

Two things this table says that a single ratio would hide. Below optimal length
the disagreement is not a magnitude but a **sign**: biomech's spring pushes back
when compressed, suji's passive tissue is exactly slack. And at 1.60 suji reports
**2.18 × its own peak active force** — it is extrapolating past 1.5, the stretch
its exponential is calibrated at, exactly as its ligament code warns about for
ligaments. Neither number is wrong for its model; they are answers to different
questions, and averaging them would be meaningless.

**Only biomech has**, and suji has no state to feed them: force–velocity
(measured 1.00 isometric, 0.50 at half v-max shortening, 0.00 at v-max, 1.25 and
1.50 for the capped eccentric branch), first-order activation dynamics (0.6321
after one time constant), and a tension-only series-elastic tendon. **Only suji
has**: posture solving, Crowninshield–Brand minimum-cubed-stress recruitment,
ligaments with their own calibration ranges, and refusal of postures where a
straight-line muscle passes through its joint.

**This comparison is not automated.** Running it needs suji on the classpath, and
adding that dependency would defeat the reason both repos independently chose not
to have it. `force-length-grid-pinned-against-suji-test` in
`test/kotoba/biomech/muscle_test.cljk` pins **biomech's half** of the grid above,
so this repo cannot drift silently; it cannot notice suji changing, and that is
stated rather than implied. To re-run the full comparison, put both `src`
directories on one classpath and evaluate the two `force-length-factor`s and
`passive-force-n` / `acceleration` over the grid.

### One thing this measurement got wrong on the way in

`default-params` claimed its passive stiffness was "tuned so a +30% stretch gives
~40 N passive restoring force". Measured, it gives **9.0 N** — 200 N/m × 0.045 m.
The prose was 4.4× the parameter sitting next to it and nothing tested the
sentence. The docstring is corrected and
`passive-spring-is-linear-and-bidirectional-test` now pins 9.0 N; the parameter
is untouched, because retuning `k` to rescue a comment would have changed every
passive number in this repo.

### Which passive model is right, and what the sources actually say

Settled 2026-09-07. The measurement above left this open and said why: deciding
it needed a third source, and neither anchor either repo already uses — Hansraj
2014 cervical compression, Wilke 1999 intradiscal pressure — is one. Six were
gone to. Five could be read; the sixth is recorded below as unobtainable rather
than cited.

**Every source that specifies a passive force–length curve specifies it as
tension-only, with the onset at or near optimal fiber length. None of them lets
the parallel element push.** The disagreement in the table above is therefore
not two defensible models: on the tissue question biomech is the outlier.

| source | what could be read | what it specifies |
|---|---|---|
| **Thelen 2003** — ASME *J Biomech Eng* **125**(1):70–77, DOI [10.1115/1.1531112](https://doi.org/10.1115/1.1531112) | full text | Eq. (3), verbatim: `F̄^PE = (e^(k^PE(L̄^M−1)/ε₀^M) − 1) / (e^(k^PE) − 1)`, "where F̄^PE is the normalized passive muscle force, k^PE is an exponential shape factor, and ε₀^M is the passive muscle strain due to maximum isometric force. The shape factor, k^PE, was set equal to five". Table 1 (p. 71) gives ε₀^M = **0.6** young, **0.5** old — "reduced from 0.60 for young adults to 0.50 for older adults to account for the relative increase in passive stiffness". **The printed equation is not itself tension-only**: it is negative for L̄^M < 1. |
| **OpenSim's Thelen implementation** — `OpenSim/Actuators/Thelen2003Muscle.cpp`, opensim-core `main` | source read directly | `calcfpe` supplies the clamp the paper omits: `double fpe = 0; ... if(lceN > 1.0){ ... } return fpe;`. Defaults `constructProperty_FmaxMuscleStrain(0.6)` and `constructProperty_KshapePassive(5.0)` — the paper's two numbers. |
| **Millard et al. 2013** — ASME *J Biomech Eng* **135**(2):**021004**, DOI [10.1115/1.4023390](https://doi.org/10.1115/1.4023390) | full text | Sec. 2, verbatim: "Force is also developed when the muscle is stretched **beyond a threshold length**, regardless of whether the muscle is activated, which is represented by the passive-force–length curve *f*^PE(*l̃*^M)". The curve is a quintic Bézier spline fit to experimental data, not an exponential. Its shipped parameters live in `FiberForceLengthCurve` (source read): `strain_at_zero_force` **0.0**, `strain_at_one_norm_force` **0.7**, `stiffness_at_low_force` 0.2, `stiffness_at_one_norm_force` 2.86, `curviness` 0.75, "fit to the experimentally measured fiber-force-length curves of Winters et al. (2010, Fig. 3a)". Zero below optimal is structural, not a clamp: the spline is built with a low-end extrapolation slope of `0.0` from `xZero = 1 + eZero`. |
| **Winters et al. 2011** — *J Biomech* **44**(1):109–115, DOI [10.1016/j.jbiomech.2010.08.033](https://doi.org/10.1016/j.jbiomech.2010.08.033) | full text | The modelling assumption, verbatim: "When not activated, the muscle is assumed to develop force in the passive element for muscle lengths greater than optimal length (L₀). The passive length-tension relationship is generated using the generic non-dimensional model popularized by Zajac (1989)". And the check on it: rabbit TA/EDL/EDII were measured "from −40%L_fn to 40%L_fn in increments of 5%L_fn", passive tension read as "the baseline (preactivation) force", and the model **failed** — ICC 0.70 ± 0.07, "not an accurate predictor of passive tension". The stated reason is the *onset*, not the sign: "The deviation in position results from the model's assumption that passive tension is first developed at L₀. This is not the case for all muscles." |
| **Ward et al. 2020** — *Front Physiol* **11**:211, DOI [10.3389/fphys.2020.00211](https://doi.org/10.3389/fphys.2020.00211) | full text | The physiological claim, stated as such rather than modelled: "**Passive tension is borne by a muscle when it is lengthened beyond slack length.**" Slack length is operationally the "length at which tension was ∼2 μN"; measured slack sarcomere lengths are 2.22–2.38 μm across fibers, bundles and fascicles in three muscles (Table 1), and specimens are only ever "stretched in total to ∼100% strain". |
| **Zajac 1989** — *Crit Rev Biomed Eng* **17**(4):359–411, PMID 2676342 | **`:could-not-obtain`** — abstract only | The abstract does not mention the passive curve. A free full text is indexed on HAL (`hal-04849267`) but the document endpoint serves a bot challenge, and getting past one is not something this workspace does. **Zajac's passive specification reaches this repo only second-hand**, through Winters 2011's description of it quoted above. It is not cited here for anything Winters does not say. |

Two things fall out that neither README knew yesterday.

**suji is not a third model. It is Thelen 2003 with the older-adult strain
parameter, scaled by 0.8.** `suji.methods.muscle/passive-force-n` computes
`0.8 · F_peak · (e^(5x) − 1)/(e^5 − 1)` with `x = (L/L₀ − 1)/0.5`. Substitute
ε₀^M = 0.5 into Thelen's Eq. (3) and the exponent is identical; suji's shape
factor 5.0 *is* Thelen's k^PE. So suji ≡ 0.8 × Thelen(ε₀^M = 0.5). Checked, not
asserted: at 1.30 / 1.50 / 1.60 × optimal that identity reproduces suji's own
published 0.1036 / 0.8000 / 2.1840 fractions of peak to every digit, and
`suji-passive-curve-is-thelen-with-the-older-adult-strain-test` pins biomech's
side of it.

**The literature's own onset assumption is the weakest part of it.** Winters
measured the curve OpenSim ships against real muscle and it failed. So "passive
force is zero below optimal length" is not a measured fact; it is a modelling
convention whose author reports it is wrong for some muscles. What *is* measured
is the sign — Ward's slack length, and the fact that every one of these
protocols only ever stretches. **Nothing measures a muscle belly pushing.**

### What biomech does about it, and why the default did not change

`kotoba.biomech.muscle` now carries both models, selected by `:passive-model`:

```clojure
(require '[kotoba.biomech.muscle :as m])
(def p (assoc (m/make-params) :passive-model :thelen-2003))
(m/passive-force (* 1.30 0.15) p)   ;=> 75.86 N   (Thelen 2003 Eq. 3, ε₀=0.6, k=5)
(m/passive-force (* 0.70 0.15) p)   ;=> 0.0       (tension-only)
(m/passive-force (* 0.70 0.15) (m/make-params))   ;=> -9.0  (default: pushes)
```

`:thelen-2003` is the literature's curve with OpenSim's shipped constants, so
anyone who needs passive muscle force out of this repo now gets the number the
musculoskeletal-simulation field uses instead of deriving it again.

**The default stays `:linear-bidirectional`, and this is a decision, not
inertia.** `default-params` already said the bidirectional spring "is a lumped
mass-spring choice, not a claim about muscle tissue" — a design statement that
predates this investigation and that the sourcing above vindicates rather than
overturns. What was missing was a reason. Measured, there is one:

| protocol: `simulate` 0.5 s at activation 1.0 from L₀ | `:linear-bidirectional` | `:thelen-2003` |
|---|---|---|
| tendon-free `step` — settles at L/L₀ | **0.5037** | **0.1287** |
| …and is that an equilibrium? | yes: held there at zero velocity, \|a\| < 0.5 m/s² — spring push against active pull | no: still accelerating inward at > 40 m/s²; 0.1287 is where the active force–length parabola hit zero below 0.5 L₀ and damping ran the velocity out |
| `step-muscle-tendon`, fixed MTU — settles at L/L₀ | 0.7927 | 0.7914 |
| difference, with a tendon | | **0.17%** |

In the tendon-free path the compressive branch is the *only* thing giving the
mass a static equilibrium below rest length. Removing it does not produce a
better muscle; it produces a belly compressed to 13% of rest length at a length
set by integration history rather than by forces. That is the same error with
no equilibrium.

What actually holds a muscle out in the body is its load, and this repo has
that: in `step-muscle-tendon` at that equilibrium the series tendon carries
822 N against the passive element's 6.2 N (826 N against the tension-only
element's 0), and there the choice is worth 0.17%. So:

- **`:linear-bidirectional` is a numerical boundary for the tendon-free lumped
  path.** It is documented as that and not as tissue. It is the default only
  because changing it would silently multiply every existing passive number in
  this repo by ~8 at +30% stretch, to fix a path that is incomplete anyway.
- **`:thelen-2003` is the tissue model.** Select it whenever passive force is
  the answer rather than a boundary condition — flexion-relaxation, a stretched
  antagonist, %MVC bookkeeping. Prefer `step-muscle-tendon` with it: that
  combination is both the literature's curve and a bounded model.

Both measurements are pinned by tests
(`passive-model-decides-the-tendon-free-equilibrium-test`,
`passive-model-barely-matters-with-a-tendon-test`), so the argument for the
default cannot quietly stop being true. `passive-spring-is-linear-and-bidirectional-test`
is unchanged and still pins 9.0 N at +30% and the negative sign at 0.70 L₀ —
the default's behaviour is untouched, and all 38 tests that existed before this
change still pass with their meaning intact.

### Recommendation for `cloud-itonami/suji` — not carried out here

suji is held by another agent; nothing in that repo was written, and this is a
recommendation for its owner, recorded here so it is durable.

The function is `suji.methods.muscle/passive-force-n`, with the two constants
`passive-slack-frac` (1.0) and `passive-at-stretch` (0.8).

1. **The shape is already right and should be named.** It is Thelen 2003 Eq. (3)
   — not "an exponential", *that* exponential, with Thelen's own k^PE = 5.0. The
   docstring says "Exponential above slack" and cites nobody. Adding the citation
   costs one line and stops the next reader deriving it again.
2. **Decide ε₀^M deliberately.** `passive-at-stretch = 0.8` at 1.5 × optimal is
   arithmetically Thelen's **older-adult** ε₀^M = 0.5, scaled by 0.8. If suji
   means healthy adults — its cervical-extensor PCSAs are from Kamibayashi &
   Richmond 1998 cadavers, not an aged cohort — the young-adult value is
   ε₀^M = 0.6, which is also what OpenSim ships: passive force reaches 1.0 × peak
   at **1.6** × optimal, i.e. `passive-at-stretch` → 1.0 with the normalising
   denominator 1.5 → 1.6. That lowers passive tension at every length below
   1.6 L₀ and so *raises* the %MVC suji reports, which is the conservative
   direction for an actor whose output is a claim about effort.
3. **Clamp above the calibrated range, as `ligament-force-n` already does.**
   `passive-force-n` returns 2.18 × peak active force at 1.6 × optimal — from
   extrapolating a curve normalised at 1.5. suji's own ligament code refuses to
   do exactly this, with a `:ref-stretch` clamp and an `at-limit?` predicate, for
   exactly the reason given here in its comment: "extrapolating gave the nuchal
   ligament 52,312 N at an ordinary forward-head posture". Muscle passive tissue
   is not exempt from that argument. A `passive-at-limit?` companion would let
   consumers show "past the calibrated range" instead of a number.
4. **Keep the onset at optimal length, but stop calling it settled.** Winters
   2011 measured it and it is the part that failed (ICC 0.70 ± 0.07): "the
   model's assumption that passive tension is first developed at L₀ … is not the
   case for all muscles". The convention is fine; a one-line note that it is a
   convention, with the measurement against it, is better than silence.

Nothing here argues suji should adopt biomech's linear spring. It should not:
the sourcing is unanimous that passive muscle is tension-only, and suji is a
static whole-body model with no tendon-free integration to bound, so it has none
of the reason biomech has for keeping one.

## Which tissues are sourced, and which are only plausible

Settled 2026-09-07. **The count was never the problem.** This repo already
carried eleven tissues before this work, and **every one of them cited nobody** —
each `:source` reads `representative; ...` with no author, no year and no DOI.
Adding more of those would have made the table longer and the repo no more
truthful. Measured 2026-09-07 after this change: **3 of 13 entries carry a
citation, 10 do not.**

| tissue | provenance |
|---|---|
| **Annulus fibrosus** | Elliott & Setton 2001 + Iatridis 1998 — **new** |
| **Nucleus pulposus** | Iatridis 1997 + Johannessen & Elliott 2005 — **new** |
| **Ligament** | Neumann 1992 — **value unchanged, citation added** |
| cortical / cancellous bone, skeletal muscle, skin, liver, tendon, cartilage, arterial wall, brain, adipose | `representative; ...`, **uncited**. Not touched here, and not to be read as verified. |

### Why the disc, and not something from an anatomy list

From a consumer, not from anatomy. `cloud-itonami/suji` computes a per-level
spinal compressive force, divides it by a disc area to report `:stress-mpa`, and
cross-checks that against Wilke 1999's in-vivo intradiscal pressure. So it
already reasons about this tissue — and has a **stress with no modulus**, so it
cannot say how far anything deforms. It also carries Nachemson's pressure index,
whose entire content is that *the pressure in the nucleus is not the pressure on
the disc*. That is a material distinction, so this is **two entries and not one
averaged "disc"**.

Ligament earned its place differently: suji clamps ligament force at a
`:ref-stretch` with no modulus behind it, and biomech's ligament entry had a
number with no source. One of those two gaps could be closed by reading.

### What was read

Every number came from an **abstract**, retrieved through the Europe PMC REST
API — PubMed's HTML serves a cookie-consent page, and Europe PMC's article pages
are JavaScript-rendered, so both return something that looks like a document and
contains no abstract. The entries say `:obtained :abstract`, not `:full-text`.
The region- and level-resolved tables in the full texts were **not** read.

| source | what it gave |
|---|---|
| **Elliott & Setton 2001** — *J Biomech Eng* **123**(3):256–263, DOI [10.1115/1.1374202](https://doi.org/10.1115/1.1374202) | Verbatim: "significant inhomogeneity in the linear-region circumferential tensile modulus (17.4+/-14.3 MPa versus 5.6+/-4.7 MPa, outer versus inner sites) and the Poisson's ratio v21 (0.67+/-0.22 versus 1.6+/-0.7, outer versus inner), but not in the axial modulus (0.8+/-0.9 MPa) or the Poisson's ratios V12 (1.8+/-1.4) or v13 (0.6+/-0.7)." |
| **Iatridis et al. 1998** — *J Biomech* **31**(6):535–544, DOI [10.1016/S0021-9290(98)00046-3](https://doi.org/10.1016/S0021-9290(98)00046-3) | Confined compression. H_A0 **0.56 ± 0.21 MPa** normal, 1.10 ± 0.53 degenerate; stiffening coefficient β 2.13 ± 1.48 normal. And verbatim: "Significant effects of degeneration **but not orientation**". |
| **Iatridis et al. 1997** — *J Biomech* **30**(10):1005–1013, DOI [10.1016/S0021-9290(97)00069-9](https://doi.org/10.1016/S0021-9290(97)00069-9) | Verbatim: "the shear stress of the nucleus pulposus relaxed nearly to zero indicative of the fluid nature of the tissue", and dynamically "dynamic modulus (magnitude of G*) ranging from **7 to 20 kPa** and loss angle (delta) ranging from 23 to 30 degrees over the range of angular frequencies tested (**1-100 rad s-1**)". |
| **Johannessen & Elliott 2005** — *Spine* **30**(24):E724–E729, DOI [10.1097/01.brs.0000192236.92867.15](https://doi.org/10.1097/01.brs.0000192236.92867.15) | H_A,eff **1.01 ± 0.43 MPa** nondegenerate; swelling stress 0.138 ± 0.029 MPa. Conclusion verbatim: "**Swelling is the primary load-bearing mechanism**". |
| **Neumann et al. 1992** — *J Biomech* **25**(10):1185–1194, DOI [10.1016/0021-9290(92)90074-B](https://doi.org/10.1016/0021-9290(92)90074-B) | Verbatim: "The average tensile strength, the 'overall' tensile modulus and the 'overall' strain of the ALL at failure were 27.4 MPa (S.D. 5.9), **759 MPa (S.D. 336)** and 4.95% (S.D. 1.51), respectively." At 2.5 mm/s, "approximately 1.0% strain per second". |

### What was refused

**A tissue that cannot be sourced is recorded as absent with the reason.** These
are in `:provenance :unobtained`, and `sources-sought-and-not-obtained-are-recorded-test`
pins that they stay there.

- **Pintar et al. 1992** (*J Biomech* **25**(11):1351–1356, DOI
  [10.1016/0021-9290(92)90290-H](https://doi.org/10.1016/0021-9290(92)90290-H)) —
  `:could-not-obtain :numbers-not-in-abstract`. This is *the* source that would
  settle the per-ligament moduli. Its abstract confirms six lumbar ligaments, 38
  cadavers, 132 samples and "A total of 18 data curves are presented" — and
  publishes **no numeric modulus**. So ligamentum flavum, interspinous and
  supraspinous are **not carried**. That is precisely the gap suji has: its
  `posterior_lumbar_ligaments` bundles exactly those three.
- **Ebara et al. 1996** (*Spine* **21**(4):452–461) — abstract read **in full**
  and it is directional but **non-numeric**: "The anterior anulus fibrosus had
  larger values for tensile moduli and failure stresses than the posterolateral
  anulus. Also, the outer regions … had greater moduli". Recorded as independent
  agreement with Elliott's outer > inner ordering. **No number in any entry comes
  from Ebara.**
- **Density**, for both disc entries. No read source, so no field.
- **A Young's modulus and a Poisson's ratio for the nucleus**, deliberately.
  Iatridis measured its shear stress relaxing *nearly to zero*. `E = 2G(1+ν)`
  would manufacture a modulus for a tissue the source says is not a solid.
  `nucleus-carries-no-youngs-modulus-deliberately-test` pins the absence so
  nobody "completes" the entry later.
- **Articular cartilage, aponeurosis/fascia and nerve** were candidates and were
  not attempted. No consumer in this family calls them today, and three sourced
  tissues were worth more than six more `representative; ...` strings.

### Anisotropy is stated, not averaged

The annulus is the case that makes a single modulus indefensible — and the case
that shows *when* one is fine.

| | circumferential (outer) | axial | ratio |
|---|---|---|---|
| **tension** (Elliott 2001) | 17.4 ± 14.3 MPa | 0.8 ± 0.9 MPa | **21.75×** |
| **confined compression** (Iatridis 1998) | \<no orientation effect detected\> | H_A0 0.56 ± 0.21 MPa | — |

Two things follow that a single averaged number would destroy.

**The same tissue is ~22× anisotropic in tension and not measurably anisotropic
in compression.** Iatridis tested axial and radial specimens and found
"significant effects of degeneration but not orientation". So one number is
defensible for one loading mode and indefensible for the other, in one tissue.

**The scalar `:youngs-modulus` is the axial direction, and the entry says so.**
It is *not* a mean of the three — a mean would be a value measured nowhere. It
is also the **least certain** of them: 0.8 ± 0.9 MPa has a standard deviation
larger than its mean. That is written into the entry rather than smoothed away,
and `annulus-scalar-is-the-axial-direction-not-an-average-test` pins it.

**ν = 0.67 exceeds the isotropic bound of ½, and that is not an error.** The ½
bound follows from *isotropy*; an anisotropic tissue may legitimately exceed it,
and this one does (ν12 = 1.8 ± 1.4 at inner sites). But `kotoba.biomech.fem`
passes `:poissons-ratio` straight into fea's **isotropic** linear-elastic
material, where K = E/(3(1−2ν)) with 1−2ν = −0.34 is a **negative bulk modulus** —
a material that expands when squeezed. Nothing raises; the solve returns finite,
meaningless numbers. So `tissue/isotropically-admissible?` exists and the bridge
refuses. Every shipped tissue that has a Poisson's ratio is below ½, so nothing
that worked before changed.

Rate is stated too, because these tissues are rate-dependent: both annulus
studies are slow quasi-static equilibrium protocols, the ligament number is at
~1.0% strain/s, and the nucleus shear modulus is meaningless without its
frequency band — which is why the entry carries `[1.0 100.0]` rad/s next to it,
and takes the **low end** of 7–20 kPa rather than a midpoint. The abstract does
not say which endpoint belongs to which frequency, so the entry does not claim
one.

### The refusal that makes the entries do work

See `kotoba.biomech.disc`. Composing this repo's tissue data with suji's output
naively gives an impossible answer, and the library says so instead of returning
it:

| | |
|---|---|
| Wilke 1999 relaxed-sitting L4/L5 pressure | 0.46 MPa |
| annulus H_A0 (Iatridis 1998, normal) | 0.56 MPa |
| linear axial strain | **0.821** |
| height loss on a 10 mm disc | **8.21 mm** |

8.2 mm of height loss on a 10 mm disc, from sitting still on a stool. Botsford
1994 measured the diurnal loss in vivo and it is 16.2% of disc **volume** (mean,
lower three lumbar) and 18.7–21.6% per level — and height strain is smaller than
volume fraction, because AP diameter falls as well. The nucleus is stiffer
(1.01 MPa) and still gives 45.5%. Both return
`:refused :beyond-linear-range` with the height loss **withheld, not clamped** —
a clamped number at the limit would get used.

The reasons are in the sources, not in hindsight: H_A0 is the zero-strain
tangent of a model whose own stiffening coefficient is β = 2.13 ± 1.48, so a
linear reading **overstates** deformation; and Johannessen's conclusion is that
the in-vivo load path is swelling, not matrix deformation. The 5% default limit
is labelled in its own docstring as **a convention of this namespace**, not a
number from Iatridis — the source gives the direction of the argument, not a
cut-off.

### Settling that refusal: which explanation the literature supports

The refusal above was right and did not say *why*. Three explanations were open,
and none of them had been measured here:

1. **`H_A` is an equilibrium modulus.** It says where the tissue ends up after
   the fluid has left, not where it is minutes after a load lands. A single
   modulus cannot carry a time scale.
2. **The load is carried by swelling, not by the matrix.** Johannessen &
   Elliott's own conclusion.
3. **The measured quantity is not a stress on the solid phase at all**, so
   feeding it to a solid modulus is a category error — in which case the answer
   is a differently shaped model, not a better number.

(1) and (2) are now implemented in full, with every constant read from a source,
**because they are the two that can be measured and disposed of**. Measuring
them disposes of them. Four readings of the same 0.46 MPa, judged against
Botsford's in-vivo numbers:

| reading | nucleus strain | verdict |
|---|---|---|
| `σ / H_A` — the refusal above | 0.455 | refuted, and by a lot |
| `(σ − P_sw) / H_A` — **(2) removed** | 0.319 | still ~1.5× the largest measured per-level loss, 2× the mean |
| time-dependent, `t` = 16 h — **(1) removed too** | 0.317 | *it barely moves* |
| `((σ / index) − P_sw) / H_A` — **(3) removed** | 0.131 – 0.167 | inside the measured band, or under it |

**Time is real and it is not the explanation.** The nucleus poroelastic time
constant works out at 7.6 h, so sixteen hours of sitting is 2.1 time constants
and the disc is 99.5% of the way to equilibrium — there is no more time left to
spend. At `t = ∞`, where `H_A` is *exactly* the right modulus and no clock is
involved, the prediction is still 32%.

What closes the gap is the third reading, and the third reading is the one that
stops treating a hydrostatic pressure read **in the nucleus** as a stress on a
specimen. Wilke's Methods say what the transducer was in, verbatim: *"a pressure
transducer with a diameter of 1.5 mm was implanted in the nucleus pulposus of a
nondegenerated L4–L5 disc"*. Nachemson measured the quotient between that
pressure and the mean stress on the whole disc and called it the pressure index;
Table 5 gives 1.5–1.7 for normal lumbar discs. Dividing by it is the whole of
the remaining overshoot.

So the module now carries the time path **and still refuses**, because 16.7% is
not inside a linear reading of `H_A0` either, and a number that has stopped
being absurd has not thereby become defensible. What changed is that the refusal
now **names what it is missing** — `:missing` is a vector of keywords, and its
first entry is `:effective-stress-on-solid-phase` — and the note points at
`nucleus-pressure->disc-mean-stress`, which takes the index as a **required
argument** so nobody gets it silently.

#### The one thing this earned

A time constant is not free to assert. `τ = h²/(H_A·k)` is built here from two
confined-compression material constants (Johannessen & Elliott's `H_A,eff` =
1.01 MPa and `ka` = 0.9 × 10⁻¹⁵ m⁴/N·s) and one geometric assumption. van der
Veen 2013 fitted creep time constants to **whole human discs** — a different
experiment on a different preparation — and got **3.6–17 h**. The derived value,
7.6 h, falls inside that band.

Read for what it is: van der Veen fitted the *same* discs over six test
durations and got six answers, which is the authors telling you the quantity is
protocol-dependent (*"The 24h experiment was still too short for an accurate
determination of the parameters"*). Landing inside it clears a real external bar
and is not a validation to a figure. The anulus, on its own `k0`, gives **62 h**
— outside the band, and reported rather than hidden. And the drainage path is
the most leveraged assumption in the whole calculation (`τ` goes as its square),
so it is its own named function: MacLean 2007 measured that *"differences in
endplate permeability conditions had a significant effect on viscoelastic
behaviors"*, which means treating the endplate as a free surface is known to be
wrong in the direction of a `τ` that is too **short**.

#### What was read, and what was not

| source | for | obtained |
|---|---|---|
| Wilke 1999, *Spine* 24(8):755–762, PMID 10222525 | the 0.46 MPa, **and that the transducer was in the nucleus pulposus** | `:abstract` (Europe PMC REST) |
| Iatridis 1998, *J Biomech* 31(6):535–544, PMID 9755038 | H_A0 0.56, β 2.13, σ_offset 0.13 MPa, k0 0.20e−15, M 1.18; and the load-carriage sentence | `:abstract` (Europe PMC REST) |
| Johannessen & Elliott 2005, *Spine* 30(24):E724–9, PMID 16371889 | H_A,eff 1.01, P_sw 0.138 MPa, ka 0.9e−15; and *"Linear biphasic theory was used"* | `:abstract` (Europe PMC REST) |
| Mow, Kuei, Lai & Armstrong 1980, *J Biomech Eng* 102(1):73–84, PMID 7382457 | the biphasic theory, and its authors' own warning about constant permeability | `:abstract` (**Crossref** — see below) |
| Yuan et al. 2018, *Sci Rep* 8:5043, PMC5864912 | `t_g = a²/(k·C₁₁)`, the gel diffusion time, verbatim | `:full-text` (Europe PMC REST XML, CC BY) |
| van der Veen 2013, *J Biomech* 46(12):2101–2103, PMID 23796401 | measured whole-disc creep time constant, 3.6–17 h | `:abstract` (Europe PMC REST) |
| Botsford 1994, *Spine* 19(8):935–940, PMID 8009352 | measured diurnal disc volume loss, 16.2% / 18.7–21.6% | `:abstract` (Europe PMC REST) |
| MacLean 2007, *J Biomech* 40(1):55–63, PMID 16427060 | that endplate permeability changes the viscoelastic behaviour | `:abstract` (Europe PMC REST) |
| Nachemson 1960, *Acta Orthop Scand* XXVIII:269–289 | the pressure index; Table 1's worked arithmetic and Table 5's values | `:full-text` (scanned PDF, text extracted) |
| Tyrrell 1985, *Spine* 10(2):161–164, PMID 4002039 | circadian stature variation, 19.3 mm mean | `:abstract`, **read but not used** — whole-body stature over 23 discs is not a per-disc height strain |
| Nachemson 1966, *Clin Orthop* 45:107–122, PMID 5937361 | — | **`:could-not-obtain`** — no abstract in Europe PMC and no full text reached. Nothing here comes from it |
| Adams & Hutton 1983, PMID 6685921; Adams et al. 1990, PMID 2138156 | corroborating, **non-numeric** on the quantities here | `:abstract`, not used for any number |

Two retrieval notes, because they cost time. **PubMed HTML serves a cookie page
and Europe PMC article pages are JS-rendered** — both return something that
looks like a document and contains no abstract; the Europe PMC **REST** endpoint
(`/webservices/rest/search?query=EXT_ID:<pmid>&resultType=core&format=json`)
works. And when Europe PMC holds no abstract at all, as for Mow 1980, the
**Crossref work record** carries the publisher's own `<jats:abstract>` deposit
— that is where the Mow quotations here come from, and it is marked `:via
:crossref` in the code so the route is visible.

### What this means for `cloud-itonami/suji`'s MPa figures

Read from `cloud-itonami/suji` at `main` on 2026-09-07, not run.

**The good news first: suji already knows the main point of this section.** Its
`spine/nachemson-pressure-index` carries the same Table 5 values from the same
paper, read in full text, and its docstring opens *"A PRESSURE IS NOT A FORCE."*
Its `:stress-mpa` is `force ÷ disc-area`, which is `P / A_d` — a **mean stress
on the whole disc**, the denominator of Nachemson's index, not the nucleus
pressure in its numerator. **Nothing here overturns suji's cross-check**, which
already routes through the index. Four consequences follow, all in a stated
direction:

1. **`:stress-mpa` and a Wilke MPa are not the same quantity, and a suji figure
   that *equals* a Wilke figure at the same posture is off by the index rather
   than agreeing with it.** At a posture where Wilke reads 0.46 MPa in the
   nucleus, the disc mean stress is 0.27–0.31 MPa. A suji level that lands on
   0.46 needs explaining, not celebrating.
2. **`:stress-mpa` is a total stress on a biphasic tissue, not a stress on the
   solid matrix**, and the two are furthest apart exactly where suji operates:
   in a normal, hydrated disc. Iatridis 1998 puts it as a property of
   degeneration, verbatim — degeneration *"suggested a shift in load carriage
   from fluid pressurization and swelling pressure to deformation of the solid
   matrix"*. The healthier the disc, the less of `:stress-mpa` the matrix is
   feeling. So `:stress-mpa` should not be converted to a deformation, a strain,
   or a height change without the biphasic path, and this repo's
   `disc/axial-creep` will refuse to do it for you.
3. **There is a floor below which the tissue feels nothing at all.** The
   confined-compression law is `σ = σ_swelling + H_A·ε`, and σ_swelling is
   0.13 MPa (anulus) / 0.138 MPa (nucleus). Any level whose `:stress-mpa` is
   below about 0.14 predicts **no matrix compression whatever** — the tissue
   takes fluid up instead. suji reports eleven levels including cervical ones
   with small areas *and* small forces; whichever of them sit under that floor
   are levels where a compression figure is real but a deformation figure would
   be meaningless. `axial-creep` returns `:refused :below-swelling-stress`
   there rather than a negative height loss. (Wilke's own overnight observation
   is the same phenomenon from the other side: *"During the night, pressure
   increased from 0.1 to 0.24 MPa."*)
4. **A static `:stress-mpa` is not a height change, because the disc has a
   clock.** 7.6 h for the nucleus. A posture held for a minute and the same
   posture held for a working day produce the same `:stress-mpa` and very
   different discs.

None of this asks suji to change a number. It says what its number is, and what
it is not.

### Two things this work got wrong on the way in

**A search summary said Neumann's ALL modulus was 27.4 MPa.** Reading the
abstract, 27.4 MPa is the tensile **strength**; the modulus is **759 MPa** — 28×
out, and the wrong physical quantity. Trusting it would also have flipped the
verdict on the existing entry: 5.0e8 Pa looks absurd against 27.4 MPa and is in
fact comfortably inside 759 ± 336. **The existing value is unchanged** — it was
right, and merely unsourced.

**The first draft of these entries used `(str "…" "…")` to wrap long strings**,
an idiom copied from Clojure *source* into an EDN *data* file. Nothing evaluates
`str` in EDN. `edn/read-string` **did not throw** — it returned all thirteen
tissues and looked clean, while the field held a `PersistentList` instead of a
string. Same signature as the heredoc `\"` class this workspace already
documents (parse succeeds, value silently wrong) by a different mechanism.
Parsing is not validation; the **type** is.
`no-tissue-field-is-an-unevaluated-form-test` walks every entry and fails on any
list or bare symbol.

### One test that discriminated nothing

Every one of the 22 new tests was broken deliberately and watched fail — 25
mutations of source and data, each applied alone, each restored and verified
byte-identical by `sha256`.

**One break produced no failure at all.** Making `tissue/aggregate-modulus` fall
back to `youngs-modulus` did *not* fail
`aggregate-modulus-is-not-youngs-modulus-test`: its fixture carries **both**
fields, so the `or` never fired and the test sailed through a mutation aimed
straight at it. Two dedicated mutations — reading the `:youngs-modulus` key, and
returning `nil` — showed it does discriminate when the mutation reaches it. A
test that does not fail when the thing it is named for is broken has proved
nothing, and it is only findable by breaking it on purpose.

**The time-path round: 16 new tests, 18 mutations, none sailed through.** Each
was applied alone and each file restored and verified byte-identical by
`sha256`. The one worth naming is `M08`: `missing-quantities` has a `cond->`
whose third clause reads `permeability-strain-coefficient`, and swapping it to
read `nonlinear-stiffening-coefficient` instead is invisible to both real
tissues — the anulus carries **both** coefficients and the nucleus carries
**neither**, so either one alone would pass a mutation aimed straight at it.
That is the same shape as the `or` above. Two single-purpose fixtures, `beta-only`
and `m-only`, exist for no other reason than to reach that clause, and they are
what fails. A branch no fixture can distinguish is a branch no test is testing.

**And one assertion this round got wrong before the tests caught it.** The first
draft of `the-category-error-is-the-size-of-nachemsons-index-test` asserted that
the index-corrected strain lands *at or below* Botsford's 16.2% mean. It is
0.167 — above the mean, below the 18.7–21.6% per-level figures. Inside the band,
not under it. The claim in the table above says so, because the test made it.

## Maturity

| | |
|---|---|
| Role | capability |
| Phase | 1 + 2 — tissue domain + closed-form sim + 3 solver backends |
| Tests | 65 tests, 313 assertions across 8 namespaces, all green (measured 2026-09-07, `clojure -X:test`, exit 0) |
| Lint | 0 errors / 0 warnings (`clojure -M:lint --fail-level error`) |
| Backends | fea (beam2 FEM) · kami-vehicle (mass-spring primitives) · kami-engine-cfd (LBM CFD) |

## What's here

### Tissue-property domain — `kotoba.biomech.tissue`

Biological tissue material-property accessors (`youngs-modulus` /
`shear-modulus` / `poissons-ratio` / `density` / `source`) over a plain-map
tissue record. Thirteen tissues are in `resources/kami/biomech/tissues.edn`. Three of them
carry read citations; see [Which tissues are sourced](#which-tissues-are-sourced-and-which-are-only-plausible)
below for the ones that do not.

```clojure
(require '[kotoba.biomech.tissue :as tissue]
         '[kotoba.biomech.tissue-loader :as loader])   ; JVM

(def cortical (tissue/find-tissue (loader/presets) "Cortical-Bone"))
(tissue/youngs-modulus cortical)   ;=> 1.7e10   (Pa)
```

Numbers are **not** patient-specific; they are population-scale representative
ranges. Each tissue's `:source` field states the range the value was picked
from, and where a citation exists, `:provenance` carries it with what was
actually read.

### Intervertebral disc — `kotoba.biomech.disc`

Two paths. `axial-compression` is one modulus and no clock: stress → strain →
height loss, with a boundary on where the linear reading holds. `axial-creep` is
the biphasic path: a step load held for a time, through a poroelastic time
constant built from the tissue's own permeability, with the swelling stress
subtracted. Both refuse where they must, and the creep path's refusal **names
what it is missing**.

```clojure
(require '[kotoba.biomech.disc :as disc])
(def af (tissue/find-tissue (loader/presets) "Annulus-Fibrosus"))
(def np (tissue/find-tissue (loader/presets) "Nucleus-Pulposus"))

;; inside the linear range, it answers
(:height-loss-mm (disc/axial-compression af (disc/stress-mpa->pa 0.020) 0.010))
;=> 0.357

;; at the pressure a consumer actually has, it refuses
(let [r (disc/axial-compression af (disc/stress-mpa->pa 0.46) 0.010)]
  [(:strain r) (:refused r) (:height-loss-mm r)])
;=> [0.8214285714285714 :beyond-linear-range nil]

;; the time path. tau is derived, not asserted; 7.6 h is inside the 3.6-17 h
;; band van der Veen 2013 measured on whole human discs.
(let [r (disc/axial-creep np (disc/stress-mpa->pa 0.46) 0.010 (* 16 3600))]
  [(:time-constant-h r) (:equilibrium-strain r) (:refused r) (:missing r)])
;=> [7.639652854174306
;    0.3188118811881188
;    :equilibrium-strain-beyond-linear-range
;    [:effective-stress-on-solid-phase
;     :nonlinear-stiffening-never-measured
;     :strain-dependent-permeability-never-measured]]

;; and the conversion the refusal points at -- index REQUIRED, never defaulted
(disc/nucleus-pressure->disc-mean-stress (disc/stress-mpa->pa 0.46) 1.5)
;=> 306666.6666666667

;; below the tissue's own swelling stress it will not run the law backwards
(:refused (disc/axial-creep np (disc/stress-mpa->pa 0.1) 0.010 (* 7 3600)))
;=> :below-swelling-stress
```

### Bone closed-form mechanics — `kotoba.biomech.osteo`

Euler-Bernoulli beam theory: cantilever tip deflection (`F·L³/3EI`), maximum
bending stress (`M·c/I`), plus section helpers (second moment of area,
extreme-fibre distance) for rectangular and solid circular sections.

### Lumped muscle model — `kotoba.biomech.muscle`

1-D mass-spring-damper with an active contractile element, parabolic Hill
force-length scaling, and a velocity relationship covering both concentric
force loss and capped eccentric force enhancement. A first-order neural
excitation-to-activation response drives a tension-only series-elastic tendon,
forming a fixed-length muscle-tendon unit. Semi-implicit Euler integration
uses sub-stepping.

The parallel elastic element has two models, chosen by `:passive-model`:
`:linear-bidirectional` (the default, a lumped numerical boundary) and
`:thelen-2003` (Thelen 2003 Eq. 3 with OpenSim's shipped constants, tension-only
— the musculoskeletal-simulation literature's curve). Which to use, and why the
default is the one that is not the literature's, is settled with sources in
[Which passive model is right](#which-passive-model-is-right-and-what-the-sources-actually-say)
above.

## Phase 2 — solver backends via `:local/root`

Phase 1 stays zero-dep; Phase 2 consumes the existing solver repos as
backends. Each `:local/root` dependency is cloned as a sibling by CI
(kotoba-lang/host `ci.yml` pattern).

### Bone FEM — `kotoba.biomech.fem` → kotoba-lang/fea

Bridges tissue material properties into fea's linear-static **beam2** axial
bar and **tet4** 3-D elasticity solvers. The included tet4 path is a reference
cube mesh; anatomical mesh ingestion and hex8 remain future work.

```clojure
(require '[kotoba.biomech.fem :as fem])
;; 1 m cortical-bone bar, 1000 N axial tension, 4 elements
;; analytic: delta = F·L/E = 5.88e-8 m, sigma = F = 1000 Pa
(def res (fem/solve-axial-bar cortical 1.0 1000.0 4))
(:max-displacement res)   ;=> ~5.88e-8
(:max-stress res)         ;=> ~1000.0
```

### Soft tissue — `kotoba.biomech.softbody` → kotoba-lang/kami-vehicle

A 3-D mass-spring-damper grid for muscle / skin / organ walls. This namespace
is a thin biomech wrapper over kami-vehicle's vehicle-agnostic
`vehicle.softbody` integrator and adds tissue-to-spring parameter mapping.

```clojure
(require '[kotoba.biomech.softbody :as softbody])
;; 3x3 grid, top row anchored, gravity makes the rest sag
(def grid (-> (softbody/make-grid 3 3 0.1 0.1 100.0 5.0)
              (softbody/anchor-row 3 0)))
(def out (softbody/simulate grid 1.0e-3 100))
```

### Blood / air flow — `kotoba.biomech.hemodynamics` → kotoba-lang/kami-engine-cfd

Bridges into the D2Q9 Lattice-Boltzmann solver: a 2-D channel with no-slip
walls, inlet velocity, zero-gradient outlet, and an obstacle (vessel cross-
section / plaque / airway constriction); reads back the drag the flow exerts.

```clojure
(require '[kotoba.biomech.hemodynamics :as hemodynamics])
(def body (hemodynamics/channel-with-obstacle 120 40 30 8 12))
(hemodynamics/obstacle-drag-cd body 100.0 500)   ;=> averaged Cd (finite, positive)
```

## Roadmap

| phase | concern | backend | status |
|---|---|---|---|
| 2 | bone FEM (axial bar) | kotoba-lang/fea | **landed** |
| 2.1 | bone FEM (3-D tet4 reference mesh) | kotoba-lang/fea | **landed** |
| 2 | soft tissue mass-spring | kotoba-lang/kami-vehicle | **landed** |
| 2 | blood / air flow (LBM) | kotoba-lang/kami-engine-cfd | **landed** |
| 2→3 | anatomical mesh ingestion / hex8 | kotoba-lang/fea | not implemented |
| 3 | thermoregulation / moisture (DEC voxel PDE) | kotoba-lang/kami-engine | blocked — DEC solver not yet implemented in pure-Clojure kami-engine (former Rust workspace removed) |

3-D rendering of any sim output uses the kotoba-lang/kami-engine stack
(repo-wide 3D mandate). This repo owns the biomech *domain + simulation*,
not rendering.

## Tests

```bash
clojure -X:test
clojure -M:lint
```

CI here is the **murakumo fleet**, not GitHub Actions (ADR-2607300900). Measured
2026-09-07, `GET /repos/kotoba-lang/biomech/actions/permissions` returned
`{"enabled": false}` — Actions is switched off for this repo, so the CI badge
this README used to carry could never have gone green. The badge and the inert
`.github/workflows/ci.yml` are removed. **Nothing replaced them: no fleet-ci gate
has been landed for this repo yet.** The commands above are what actually runs
the suite, by hand, today.

## License

Apache License 2.0.
