(ns kotoba.biomech.tissue-loader-test
  "JVM test that the bundled tissues.edn resource loads and contains the
  full tissue catalogue (cortical/cancellous bone, muscle, skin, organ,
  tendon, plus the Phase-2.2 additions: cartilage, ligament, vasculature,
  brain, adipose)."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.biomech.tissue-loader :as loader]
            [kotoba.biomech.tissue :as tissue]))

(deftest presets-load-and-contain-catalogue-test
  (let [presets (loader/presets)]
    (is (<= 11 (count presets)))
    (doseq [name ["Cortical-Bone" "Cancellous-Bone" "Skeletal-Muscle" "Skin"
                  "Liver" "Tendon" "Cartilage" "Ligament" "Arterial-Wall"
                  "Brain" "Adipose-Tissue"]]
      (is (tissue/find-tissue presets name)
          (str "expected tissue " name " in presets")))
    ;; every entry must pass the tissue? shape check
    (is (every? tissue/tissue? presets))))
