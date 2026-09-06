(ns kotoba.biomech.tissue
  "Biological tissue material-property domain.
  Pure data + pure functions, portable .cljc.

  A tissue is a plain map:
    {:name        string?       ; human label, e.g. \"Cortical-Bone\"
     :tissue-type keyword?      ; :bone :muscle :skin :organ :tendon ...
     :sub-type    keyword?      ; optional refinement (:cortical :cancellous)
     :model       {:type        keyword?   ; :linear-elastic :viscoelastic ...
                   :youngs-modulus number? ; Pa
                   :shear-modulus   number?; Pa
                   :poissons-ratio  number?; dimensionless
                   :density         number?}; kg/m^3
     :source      string?}      ; literature provenance / representative-range note

  Representative literature values are loaded from
  resources/kami/biomech/tissues.edn via kotoba.biomech.tissue-loader (JVM).
  Numbers are NOT patient-specific; they are population-scale ranges —
  the :source field states the range each representative value was picked
  from. Phase 2 wires :type tags to actual solver material models
  (fea linear-elastic, kami-vehicle soft-body, etc.)."
  (:require [clojure.string :as str]))

(def tissue-types
  "Enumerated top-level tissue categories this domain models.

  :disc is the intervertebral disc. It is a top-level category rather than a
  :sub-type of anything because the disc is TWO materials that differ by orders
  of magnitude — annulus fibrosus and nucleus pulposus — and a consumer that
  averages them has lost the distinction Nachemson's pressure index exists to
  make."
  #{:bone :muscle :skin :organ :tendon :cartilage :ligament :vasculature :disc})

(def model-types
  "Constitutive-model tags. Phase 1 keeps these descriptive — Phase 2 routes
  them to solver backends (:linear-elastic -> fea, :viscoelastic/
  :hyperelastic -> kami-vehicle soft-body, etc.)."
  #{:linear-elastic :viscoelastic :hyperelastic :rigid
    ;; The tissue has a measured direction dependence that a single modulus
    ;; cannot carry. Its scalar :youngs-modulus is ONE stated direction (the
    ;; entry's :source says which); the rest is under :directional.
    :anisotropic-linear-elastic
    ;; Solid matrix + interstitial fluid. Its stiffness is an AGGREGATE modulus
    ;; from a confined-compression equilibrium, not a Young's modulus, and it
    ;; may legitimately have no Young's modulus at all.
    :biphasic})

(defn tissue?
  "True if m has the shape of a tissue record (required keys + typed values)."
  [m]
  (and (map? m)
       (string? (:name m))
       (contains? tissue-types (:tissue-type m))
       (map? (:model m))))

(defn tissue-type [t] (:tissue-type t))
(defn sub-type    [t] (:sub-type t))
(defn model       [t] (:model t))
(defn source      [t] (:source t))

(defn youngs-modulus
  "Young's modulus E [Pa], or nil if the model has none."
  [t] (get-in t [:model :youngs-modulus]))

(defn shear-modulus
  "Shear modulus G [Pa], or nil."
  [t] (get-in t [:model :shear-modulus]))

(defn poissons-ratio
  "Poisson's ratio ν (dimensionless), or nil."
  [t] (get-in t [:model :poissons-ratio]))

(defn density
  "Density ρ [kg/m³], or nil."
  [t] (get-in t [:model :density]))

(defn aggregate-modulus
  "Confined-compression aggregate modulus H_A [Pa], or nil.

  NOT A YOUNG'S MODULUS, and deliberately a separate field. H_A is the
  equilibrium stiffness of a laterally CONFINED specimen, so it is larger than
  the unconfined modulus of the same tissue and describes a different
  experiment. Writing it into :youngs-modulus would be a category error that no
  reader of the resulting number could detect.

  Carried by the biphasic tissues (annulus fibrosus, nucleus pulposus) whose
  literature reports compression this way."
  [t] (get-in t [:model :aggregate-modulus]))

(defn directional
  "The tissue's direction-resolved properties map, or nil.

  Present when one modulus is not enough to describe the tissue. The scalar
  accessors above still answer — with ONE stated direction, named in :source —
  so existing callers keep working; this is where a caller looks when the
  direction matters. See kotoba.biomech.tissue/directional-modulus."
  [t] (get-in t [:model :directional]))

(defn directional-modulus
  "Young's modulus [Pa] in a named direction, or nil if the tissue does not
  carry that direction.

  Directions are the keys of the :directional map (e.g. :circumferential-outer,
  :circumferential-inner, :axial). Returning nil for an unknown direction is the
  point: a caller asking for a direction this tissue was never measured in gets
  nothing, not a substituted average."
  [t direction]
  (get-in t [:model :directional direction :youngs-modulus]))

(defn provenance
  "The tissue's provenance map, or nil. {:sources [...] :unobtained [...]}."
  [t] (:provenance t))

(defn sources
  "Vector of sources actually read for this tissue's numbers (possibly empty)."
  [t] (vec (get-in t [:provenance :sources])))

(defn unobtained
  "Vector of sources that were sought and could NOT be obtained (possibly empty).

  A non-empty vector is a statement about what this entry does not know. It is
  recorded so that the absence is visible instead of being mistaken for a gap
  nobody looked at."
  [t] (vec (get-in t [:provenance :unobtained])))

(defn isotropically-admissible?
  "True if this tissue's Poisson's ratio can be used by an ISOTROPIC linear-elastic
  solver.

  WHY THIS EXISTS. Isotropic elasticity requires -1 < nu < 1/2. The bound is a
  consequence of isotropy, not a law about materials: an ANISOTROPIC tissue may
  legitimately measure nu > 1/2, and the human anulus fibrosus does — Elliott &
  Setton 2001 report nu12 = 1.8 +/- 1.4 and nu21 = 1.6 +/- 0.7 at inner sites.

  Feeding such a value to an isotropic solver does not raise anything. It
  computes a bulk modulus K = E / (3(1 - 2nu)), and at nu = 0.67 the denominator
  is negative, so K is NEGATIVE: a material that expands when you squeeze it.
  The solve returns numbers and they are meaningless. kotoba.biomech.fem passes
  :poissons-ratio straight into fea's isotropic linear-elastic material, which
  is exactly this path.

  FAIL-CLOSED ON ABSENCE. A tissue with no Poisson's ratio returns false: this
  predicate cannot certify a value it does not have. Callers that want the
  historical `nil -> 0.3` default must apply it themselves and remain
  responsible for it."
  [t]
  (let [nu (poissons-ratio t)]
    (boolean (and (number? nu)
                  (< -1.0 (double nu) 0.5)))))

(defn find-tissue
  "Look up a tissue by name in a presets collection (vector of tissue maps).
  Match is case-insensitive on :name. Returns the tissue map or nil."
  [presets name]
  (let [needle (str/lower-case (str name))]
    (->> presets
         (filter tissue?)
         (some #(when (= (str/lower-case (:name %)) needle) %)))))
