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

(deftest force-velocity-factor-test
  ;; Hill force-velocity: concentric force falls toward zero; eccentric force
  ;; rises above isometric force and is capped at the configured maximum.
  (let [vmax 1.0]
    (is (rel= (muscle/force-velocity-factor 0.0 vmax) 1.0))      ; isometric
    (is (rel= (muscle/force-velocity-factor -0.5 vmax) 0.5))     ; half max shortening
    (is (zero? (muscle/force-velocity-factor -1.0 vmax)))        ; at v_max -> 0
    (is (rel= (muscle/force-velocity-factor 0.5 vmax) 1.25))     ; eccentric boost
    (is (rel= (muscle/force-velocity-factor 2.0 vmax) 1.5))      ; default cap
    (is (rel= (muscle/force-velocity-factor 0.5 vmax 1.8) 1.4))))

(deftest eccentric-force-exceeds-isometric-test
  (let [p (muscle/make-params)
        length (:optimal-length p)
        isometric (muscle/acceleration (muscle/make-state length 0.0) p 1.0)
        lengthening (muscle/acceleration (muscle/make-state length 0.5) p 1.0)]
    ;; Remove the damping contribution before comparing contractile force.
    (is (< (+ (* (:mass p) lengthening) (* (:damping p) 0.5))
           (* (:mass p) isometric)))))

(deftest force-velocity-requires-positive-vmax-test
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (muscle/force-velocity-factor -0.1 0.0))))

(deftest activation-dynamics-test
  (let [p (muscle/make-params)
        rising (muscle/activation-step 0.0 1.0 p 0.015)
        falling (muscle/activation-step 1.0 0.0 p 0.015)]
    (is (< 0.0 rising 1.0))
    (is (< 0.0 falling 1.0))
    ;; Deactivation has the longer time constant and therefore changes less.
    (is (> falling (- 1.0 rising)))
    (is (= 1.0 (muscle/activation-step 1.0 2.0 p 1.0)))
    (is (= 0.0 (muscle/activation-step 0.0 -1.0 p 1.0)))))

(deftest tendon-is-tension-only-test
  (let [p (muscle/make-params)
        st (muscle/make-state (:rest-length p))
        slack-total (+ (:rest-length p) (:tendon-slack-length p))]
    (is (zero? (muscle/tendon-force st p slack-total)))
    (is (zero? (muscle/tendon-force st p (- slack-total 0.01))))
    (is (rel= (muscle/tendon-force st p (+ slack-total 0.01))
              (* 0.01 (:tendon-stiffness p))))))

(deftest muscle-tendon-excitation-test
  (let [p (muscle/make-params)
        st (muscle/make-state (:rest-length p))
        mtu-length (+ (:rest-length p) (:tendon-slack-length p))
        out (muscle/simulate-muscle-tendon st p 1.0 mtu-length 1.0e-3 50)
        final (peek out)]
    (is (< 0.0 (:activation final) 1.0))
    (is (< (:length final) (:rest-length p)))
    (is (pos? (muscle/tendon-force final p mtu-length)))))

;; ---------------------------------------------------------------------------
;; Boundary with cloud-itonami/suji (measured 2026-09-07)
;;
;; suji carries its own Hill force-length in `suji.methods.muscle`, and its
;; docstring already says the duplication is deliberate. Running BOTH over the
;; same normalized grid in one JVM showed the ACTIVE curve is the same closed
;; form to floating point (worst |diff| 6.7e-16 across 17 points), and the
;; PASSIVE element is a different model entirely (see README).
;;
;; THIS TEST PINS ONLY THE biomech HALF. It cannot notice suji changing —
;; automating the cross-check would put suji on this repo's classpath, and suji
;; declines to depend on biomech for the mirror-image reason (biomech pulls
;; three solver repos it does not want in a browser bundle). The comparison is
;; therefore run by hand; the numbers and the method are in the README.
;; ---------------------------------------------------------------------------

(def ^:private suji-comparison-grid
  "[length/optimal, expected force-length factor] — the exact grid the
  2026-09-07 cross-repo comparison used."
  [[0.40 0.00] [0.50 0.00] [0.60 0.36] [0.70 0.64] [0.75 0.75] [0.80 0.84]
   [0.90 0.96] [0.95 0.99] [1.00 1.00] [1.05 0.99] [1.10 0.96] [1.20 0.84]
   [1.25 0.75] [1.30 0.64] [1.40 0.36] [1.50 0.00] [1.60 0.00]])

(deftest force-length-grid-pinned-against-suji-test
  (let [opt (:optimal-length (muscle/make-params))]
    (doseq [[ratio expected] suji-comparison-grid]
      (is (rel= expected (muscle/force-length-factor (* ratio opt) opt))
          (str "force-length at " ratio " x optimal")))))

(deftest passive-spring-is-linear-and-bidirectional-test
  ;; The measured behaviour the default-params docstring used to misstate:
  ;; f_passive = k*(L - L0), 9.0 N at +30% stretch (NOT the ~40 N once claimed),
  ;; and non-zero BELOW rest length too — this element pushes back when
  ;; compressed, where suji's tension-only passive term is exactly zero there.
  (let [p (muscle/make-params)
        k (:passive-stiffness p)
        l0 (:rest-length p)
        ;; with v=0 and activation=0 the only force is the spring, so
        ;; f_passive = -m*a is measured through the same code the sim runs.
        f-passive (fn [l] (- (* (:mass p) (muscle/acceleration (muscle/make-state l 0.0 0.0) p 0.0))))]
    (is (rel= 9.0 (f-passive (* 1.30 l0))) "+30% stretch is 9.0 N, not 40 N")
    (is (rel= (* k 0.30 l0) (f-passive (* 1.30 l0))) "and it is exactly k*(L-L0)")
    (is (rel= 0.0 (f-passive l0)))
    (is (neg? (f-passive (* 0.70 l0)))
        "compressed below rest length the spring pushes out — suji's passive term is 0 here")))
