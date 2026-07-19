(ns kotoba.biomech.muscle-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.biomech.muscle :as muscle]))

(defn- rel= [a b] (< (Math/abs (- a b)) 1.0e-9))

(deftest activation-clip-test
  ;; activation is clipped to [0,1] inside acceleration, so act>=1 and act<=0
  ;; saturate.
  (let [st (muscle/make-state 0.20 0.0)
        p  (muscle/make-params)]
    (is (= (muscle/acceleration st p 1.0)
           (muscle/acceleration st p 2.0)))
    (is (= (muscle/acceleration st p 0.0)
           (muscle/acceleration st p -1.0)))))

(deftest passive-rest-equilibrium-test
  ;; At rest length, zero velocity, zero activation -> acceleration is zero
  ;; (no net force on the mass).
  (let [p  (muscle/make-params)
        st (muscle/make-state (:rest-length p) 0.0)]
    (is (zero? (muscle/acceleration st p 0.0)))))

(deftest stretched-spring-accelerates-shortening-test
  ;; Stretched 30% beyond rest, at rest velocity, no activation -> the
  ;; spring pulls the mass back toward the origin, i.e. negative accel.
  (let [p  (muscle/make-params)
        st (muscle/make-state (* 1.3 (:rest-length p)) 0.0)]
    (is (neg? (muscle/acceleration st p 0.0)))))

(deftest active-contraction-shortens-test
  ;; Full activation from rest -> mass shortens (velocity negative, length
  ;; drops below rest length) within 50 ms.
  (let [p   (muscle/make-params)
        st0 (muscle/make-state (:rest-length p) 0.0)
        out (muscle/simulate st0 p 1.0 1.0e-3 50)]
    (is (neg? (:velocity (peek out))))
    (is (< (:length (peek out)) (:rest-length p)))))

(deftest no-activation-stays-at-rest-test
  ;; At rest with zero activation, stepping should not move the mass.
  (let [p   (muscle/make-params)
        st  (muscle/make-state (:rest-length p) 0.0)
        out (muscle/simulate st p 0.0 1.0e-3 50)]
    (is (zero? (:velocity (peek out))))
    (is (rel= (:length (peek out)) (:rest-length p)))))

(deftest step-zero-dt-is-identity-test
  (let [p  (muscle/make-params)
        st (muscle/make-state 0.20 0.05)]
    (is (= st (muscle/step st p 0.5 0.0)))))

(deftest force-length-factor-test
  ;; Hill force-length: peak at optimal length, parabolic fall-off, ~0 at
  ;; the [0.5, 1.5]*optimal edges.
  (let [opt (:optimal-length (muscle/make-params))]
    (is (rel= (muscle/force-length-factor opt opt) 1.0))            ; peak
    (is (rel= (muscle/force-length-factor (* 1.25 opt) opt) 0.75))  ; quarter off
    (is (zero? (muscle/force-length-factor (* 1.5 opt) opt)))       ; upper edge
    (is (zero? (muscle/force-length-factor (* 0.5 opt) opt)))))     ; lower edge
