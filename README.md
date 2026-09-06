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

## Maturity

| | |
|---|---|
| Role | capability |
| Phase | 1 + 2 — tissue domain + closed-form sim + 3 solver backends |
| Tests | 38 tests, 123 assertions across 7 namespaces, all green (measured 2026-09-07, `clojure -X:test`, exit 0) |
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
