(ns kotoba.biomech.hemodynamics-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.biomech.hemodynamics :as hemodynamics]))

(deftest obstacle-drag-is-finite-and-positive-test
  ;; 120x40 channel, 8x12 obstacle at x=30, Re=100, 500 LBM steps.
  (let [body (hemodynamics/channel-with-obstacle 120 40 30 8 12)
        cd   (hemodynamics/obstacle-drag-cd body 100.0 500)]
    (is (number? cd))
    (is (pos? cd))))

(deftest step-flow-returns-state-and-drag-test
  (let [body (hemodynamics/channel-with-obstacle 80 30 20 6 10)
        lbm  (hemodynamics/new-flow body 100.0)
        [lbm' drag] (hemodynamics/step-flow lbm)]
    (is (some? lbm'))
    (is (number? drag))))
