(ns kotoba.biomech.disc-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.biomech.disc :as disc]))

;; Local fixtures mirroring the shipped presets, so this namespace stays pure
;; .cljc and does no I/O. The loader test pins that these match the resource.
(def annulus
  {:name "Annulus-Fibrosus"
   :tissue-type :disc
   :sub-type :annulus-fibrosus
   :model {:type :anisotropic-linear-elastic
           :youngs-modulus 8.0e5
           :poissons-ratio 0.67
           :aggregate-modulus 5.6e5}
   :source "Iatridis 1998 H_A0 0.56 +/- 0.21 MPa (normal)"})

(def nucleus
  {:name "Nucleus-Pulposus"
   :tissue-type :disc
   :sub-type :nucleus-pulposus
   :model {:type :biphasic
           :youngs-modulus nil
           :shear-modulus 7.0e3
           :aggregate-modulus 1.01e6}
   :source "Johannessen & Elliott 2005 H_A,eff 1.01 +/- 0.43 MPa"})

(def modulus-less
  {:name "Liver" :tissue-type :organ
   :model {:type :hyperelastic :youngs-modulus 3.0e3}})

(deftest axial-strain-is-stress-over-aggregate-modulus-test
  (is (= 0.5 (disc/axial-strain 1.0 2.0)))
  (is (= 0.8214285714285714 (disc/axial-strain 460000.0 560000.0)))
  (testing "an absent or non-positive modulus yields no strain, rather than throwing"
    (is (nil? (disc/axial-strain 460000.0 nil)))
    (is (nil? (disc/axial-strain 460000.0 0.0)))
    (is (nil? (disc/axial-strain 460000.0 -1.0)))
    (is (nil? (disc/axial-strain nil 560000.0)))))

(deftest wilke-in-vivo-pressure-is-refused-not-answered-test
  ;; THE HEADLINE. Wilke 1999's relaxed-sitting L4/L5 pressure is 0.46 MPa, the
  ;; number cloud-itonami/suji cross-checks against. Read linearly against the
  ;; annulus reference aggregate modulus it gives 82% strain -- 8.2 mm of height
  ;; loss on a 10 mm disc, from sitting still. The library must not hand that
  ;; back as a result.
  (let [r (disc/axial-compression annulus (disc/stress-mpa->pa 0.46) 0.010)]
    (is (= 460000.0 (:stress-pa r)))
    (is (= 0.8214285714285714 (:strain r))
        "the linear strain is still reported -- it is the evidence for the refusal")
    (is (true? (:beyond-linear-range? r)))
    (is (= :beyond-linear-range (:refused r)))
    (is (nil? (:height-loss-mm r))
        "withheld, NOT clamped: a clamped number at the limit would be used")
    (is (nil? (:height-loss-m r)))
    (is (re-find #"OVERSTATES" (:note r)))))

(deftest the-nucleus-is-refused-at-the-same-pressure-test
  ;; Stiffer in confined compression than the annulus (1.01 vs 0.56 MPa), so the
  ;; strain is smaller -- and still 46%, still far outside a linear reading.
  (let [r (disc/axial-compression nucleus (disc/stress-mpa->pa 0.46) 0.010)]
    (is (= 0.45544554455445546 (:strain r)))
    (is (= :beyond-linear-range (:refused r)))
    (is (nil? (:height-loss-mm r)))))

(deftest in-range-compression-does-return-a-height-loss-test
  ;; The namespace is not merely a refusal. Inside the linear range it answers.
  (let [r (disc/axial-compression annulus (disc/stress-mpa->pa 0.020) 0.010)]
    (is (nil? (:refused r)))
    (is (false? (:beyond-linear-range? r)))
    (is (= 0.03571428571428571 (:strain r)))
    (is (= 0.35714285714285715 (:height-loss-mm r)))
    (is (= 3.5714285714285714E-4 (:height-loss-m r)))
    (is (= 5.6e5 (:aggregate-modulus-pa r)))
    (is (= "Annulus-Fibrosus" (:tissue r)))))

(deftest the-limit-is-inclusive-test
  ;; 0.028 MPa against 0.56 MPa is exactly 0.05, the default limit. `>=` means
  ;; the boundary itself is outside. Pinned so the comparison cannot drift to `>`
  ;; without a test saying so.
  (let [at (disc/axial-compression annulus (disc/stress-mpa->pa 0.028) 0.010)]
    (is (= 0.05 (:strain at)))
    (is (= :beyond-linear-range (:refused at))))
  (is (false? (disc/beyond-linear-range? 0.049)))
  (is (true? (disc/beyond-linear-range? 0.05)))
  (testing "a caller may widen the limit and own the result"
    (let [r (disc/axial-compression annulus (disc/stress-mpa->pa 0.46) 0.010 1.0)]
      (is (nil? (:refused r)))
      (is (= 8.214285714285714 (:height-loss-mm r))))))

(deftest a-tissue-with-no-aggregate-modulus-is-refused-test
  ;; Liver has a Young's modulus and no aggregate modulus. The namespace must not
  ;; silently substitute one for the other -- they are different experiments.
  (let [r (disc/axial-compression modulus-less 1000.0 0.010)]
    (is (= :no-aggregate-modulus (:refused r)))
    (is (nil? (:strain r)))
    (is (nil? (:height-loss-mm r)))
    (is (true? (:beyond-linear-range? r))
        "an answer that could not be computed is not an answer inside the range")))

(deftest stress-mpa->pa-test
  (is (= 460000.0 (disc/stress-mpa->pa 0.46)))
  (is (= 1.0e6 (disc/stress-mpa->pa 1.0)))
  (is (nil? (disc/stress-mpa->pa nil))))
