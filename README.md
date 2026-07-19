# kotoba-biomech

[![CI](https://github.com/kotoba-lang/biomech/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/biomech/actions/workflows/ci.yml)

**Biomechanics simulation domain layer in pure Clojure.** 筋肉・骨・皮膚・
内臓の生体力学的な sim を扱う [kotoba-lang](https://github.com/kotoba-lang)
capability library.

専用 repo として存在しなかった（2026-07-19 探索確認: 既存 solver 群はあったが
人体ドメインを束ねる統合層は無かった）ため、既存の solver 資産
（fea / kami-vehicle / kami-engine-cfd / kami-engine DEC）を束ねるドメイン層として
起こした。

No network, no I/O in any `.cljc` domain namespace (the one JVM-only loader,
`kotoba.biomech.tissue-loader`, reads `resources/kami/biomech/tissues.edn` and
is split out as a `.clj` exactly like kotoba-lang/fea's `material-loader`).
Pure data + pure functions, portable `.cljc` across JVM / ClojureScript /
SCI / GraalVM.

## Maturity

| | |
|---|---|
| Role | capability |
| Phase | 1 — tissue-property domain + closed-form / mass-spring sim (zero-dep) |
| Tests | 15 tests, 40 assertions across 3 namespaces, all green |
| Lint | 0 errors (`clojure -M:lint --fail-level error`) |
| Scope | tissue-property data + 1-D lumped muscle model + cantilever bone closed-form |

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

```clojure
(require '[kotoba.biomech.osteo :as osteo])
(def I (osteo/second-moment-area-circle 0.012))           ; r = 12 mm
(osteo/cantilever-tip-deflection 700.0 0.40 1.7e10 I)     ; femur-scale tip load
```

### Lumped muscle model — `kotoba.biomech.muscle`

1-D mass-spring-damper with an active contractile element (Hill-type reduced
to linear), semi-implicit Euler integrator with sub-stepping.

```clojure
(require '[kotoba.biomech.muscle :as muscle])
(def st  (muscle/make-state 0.15))                ; at rest length
(def p   (muscle/make-params))
(def out (muscle/simulate st p 1.0 1.0e-3 50))    ; 50 ms, full activation
```

## Roadmap (Phase 2 — integrate existing solvers via `:local/root`)

Phase 1 is deliberately zero-dep so the tissue domain + closed-form references
stand alone. Phase 2 wires the existing solver repos in as backends:

| concern | backend repo | method |
|---|---|---|
| bone FEM (3-D, arbitrary geometry) | kotoba-lang/fea | linear-static tet/hex |
| soft tissue / muscle / skin (large deformation) | kotoba-lang/kami-vehicle | XPBD mass-spring |
| blood flow / airflow (CFD) | kotoba-lang/kami-engine-cfd | Lattice-Boltzmann (D2Q9/D3Q19) |
| thermoregulation / moisture | kotoba-lang/kami-engine | DEC voxel PDE |

Phase 2 deps land in `deps.edn` as
`io.github.kotoba-lang/fea {:local/root "../fea"}` etc., and CI clones each
dependency as a sibling (see kotoba-lang/host's `ci.yml` for the pattern).

3-D rendering of any sim output uses the kotoba-lang/kami-engine stack
(repo-wide 3D mandate). This repo owns the biomech *domain + simulation*,
not rendering.

## Tests

```bash
clojure -X:test
clojure -M:lint
```

## License

Apache License 2.0.
