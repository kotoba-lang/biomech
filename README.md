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
`test/kotoba/biomech/muscle_test.cljc` pins **biomech's half** of the grid above,
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

## Maturity

| | |
|---|---|
| Role | capability |
| Phase | 1 + 2 — tissue domain + closed-form sim + 3 solver backends |
| Tests | 44 tests, 147 assertions across 7 namespaces, all green (measured 2026-09-07, `clojure -X:test`, exit 0) |
| Lint | 0 errors / 0 warnings (`clojure -M:lint --fail-level error`) |
| Backends | fea (beam2 FEM) · kami-vehicle (mass-spring primitives) · kami-engine-cfd (LBM CFD) |

## What's here

### Tissue-property domain — `kotoba.biomech.tissue`

Biological tissue material-property accessors (`youngs-modulus` /
`shear-modulus` / `poissons-ratio` / `density` / `source`) over a plain-map
tissue record. Representative literature values for cortical / cancellous
bone, skeletal muscle, skin, liver, tendon are in
`resources/kami/biomech/tissues.edn`.

```clojure
(require '[kotoba.biomech.tissue :as tissue]
         '[kotoba.biomech.tissue-loader :as loader])   ; JVM

(def cortical (tissue/find-tissue (loader/presets) "Cortical-Bone"))
(tissue/youngs-modulus cortical)   ;=> 1.7e10   (Pa)
```

Numbers are **not** patient-specific; they are population-scale representative
ranges. Each tissue's `:source` field states the range the value was picked
from.

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
