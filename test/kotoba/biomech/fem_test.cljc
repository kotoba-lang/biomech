(ns kotoba.biomech.fem-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.biomech.fem :as fem]))

(def cortical
  {:name "Cortical-Bone"
   :tissue-type :bone
   :model {:type :linear-elastic :youngs-modulus 1.7e10 :poissons-ratio 0.30 :density 1900}})

(defn- rel= [a b tol]
  (< (Math/abs (- a b)) (* tol (max 1.0 (Math/abs a) (Math/abs b)))))

(deftest tissue->fea-material-test
  (let [mat (fem/tissue->fea-material cortical)]
    (is (= :linear-elastic (get-in mat [:model :type])))
    (is (rel= (get-in mat [:model :youngs-modulus]) 1.7e10 1e-9))
    (is (= 0.30 (get-in mat [:model :poissons-ratio])))
    (is (= "Cortical-Bone" (:name mat)))))

(deftest axial-bar-mesh-has-fixed-and-tip-sets-test
  (let [m (fem/axial-bar-mesh 1.0 4)]
    (is (= [0] (get-in m [:node-sets "fixed"])))
    (is (= [4] (get-in m [:node-sets "tip"])))))

(deftest axial-bar-fem-matches-analytic-test
  ;; 1 m cortical-bone bar, 1000 N axial tension, 4 elements, A = 1 (fea default).
  ;; analytic: delta = F*L/E = 1000*1/1.7e10 = 5.88e-8 m. sigma = F/A = 1000 Pa.
  (let [res   (fem/solve-axial-bar cortical 1.0 1000.0 4)
        delta (:max-displacement res)
        sigma (:max-stress res)]
    (is (pos? delta))
    (is (rel= delta (/ 1000.0 1.7e10) 1e-3))
    (is (rel= sigma 1000.0 1e-3))))

(deftest axial-bar-fem-scales-linearly-with-load-test
  ;; Doubling the load should double the displacement (linear-static).
  (let [d1 (:max-displacement (fem/solve-axial-bar cortical 1.0 500.0 4))
        d2 (:max-displacement (fem/solve-axial-bar cortical 1.0 1000.0 4))]
    (is (rel= (/ d2 d1) 2.0 1e-3))))

(deftest tet4-cube-3d-fem-finite-displacement-test
  ;; 1 cm cortical-bone cube, 10 N +z load, bottom face fixed.
  ;; 3-D tet4 path (fea tet4 assembly): displacement finite, top moves +z.
  (let [res (fem/solve-tet4-cube cortical 0.01 10.0)
        delta (:max-displacement res)
        top-node-z (nth (nth (:displacement res) 6) 2)] ; node 6 = (L,L,L)
    (is (pos? delta))
    (is (pos? top-node-z))))

(deftest tet4-cube-stiffer-displaces-less-test
  ;; Doubling E should reduce displacement (linear-elastic).
  (let [mk (fn [E] {:name "t" :tissue-type :bone
                    :model {:type :linear-elastic :youngs-modulus E :poissons-ratio 0.3}})
        d1 (:max-displacement (fem/solve-tet4-cube (mk 1.0e10) 0.01 10.0))
        d2 (:max-displacement (fem/solve-tet4-cube (mk 2.0e10) 0.01 10.0))]
    (is (< d2 d1))))
