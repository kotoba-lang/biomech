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
  "Enumerated top-level tissue categories this domain models."
  #{:bone :muscle :skin :organ :tendon :cartilage :ligament :vasculature})

(def model-types
  "Constitutive-model tags. Phase 1 keeps these descriptive — Phase 2 routes
  them to solver backends (:linear-elastic -> fea, :viscoelastic/
  :hyperelastic -> kami-vehicle soft-body, etc.)."
  #{:linear-elastic :viscoelastic :hyperelastic :rigid})

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

(defn find-tissue
  "Look up a tissue by name in a presets collection (vector of tissue maps).
  Match is case-insensitive on :name. Returns the tissue map or nil."
  [presets name]
  (let [needle (str/lower-case (str name))]
    (->> presets
         (filter tissue?)
         (some #(when (= (str/lower-case (:name %)) needle) %)))))
