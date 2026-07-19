(ns kotoba.biomech.tissue-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.biomech.tissue :as tissue]))

(def cortical
  {:name "Cortical-Bone"
   :tissue-type :bone
   :model {:type :linear-elastic
           :youngs-modulus 1.7e10
           :poissons-ratio 0.30
           :density 1900}})

(def muscle-tissue
  {:name "Skeletal-Muscle"
   :tissue-type :muscle
   :model {:type :viscoelastic
           :shear-modulus 1.0e4
           :youngs-modulus 3.0e4
           :poissons-ratio 0.45
           :density 1060}})

(deftest tissue?-test
  (testing "well-formed tissues pass"
    (is (tissue/tissue? cortical))
    (is (tissue/tissue? muscle-tissue)))
  (testing "malformed maps are rejected"
    (is (not (tissue/tissue? {:name "x"})))                                  ; missing tissue-type/model
    (is (not (tissue/tissue? {:name "x" :tissue-type :made-up :model {}})))  ; bad type
    (is (not (tissue/tissue? "not a map")))
    (is (not (tissue/tissue? nil)))))

(deftest accessors-test
  (is (= :bone (tissue/tissue-type cortical)))
  (is (= :muscle (tissue/tissue-type muscle-tissue)))
  (is (= 1.7e10 (tissue/youngs-modulus cortical)))
  (is (= 0.30 (tissue/poissons-ratio cortical)))
  (is (= 1900 (tissue/density cortical)))
  (is (nil? (tissue/shear-modulus cortical)))              ; linear-elastic has no G
  (is (= 1.0e4 (tissue/shear-modulus muscle-tissue)))      ; viscoelastic has G
  (is (nil? (tissue/source cortical))))                    ; fixture has no source

(deftest find-tissue-test
  (let [presets [cortical muscle-tissue]]
    (is (= cortical (tissue/find-tissue presets "Cortical-Bone")))
    (is (= cortical (tissue/find-tissue presets "cortical-bone")))   ; case-insensitive
    (is (= muscle-tissue (tissue/find-tissue presets "SKELETAL-MUSCLE")))
    (is (nil? (tissue/find-tissue presets "Not-A-Tissue")))
    (is (nil? (tissue/find-tissue [] "Cortical-Bone")))))
