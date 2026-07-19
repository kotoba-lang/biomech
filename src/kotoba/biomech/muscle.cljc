(ns kotoba.biomech.muscle
  "1-D lumped mass-spring-damper muscle model with an active contractile
  element (Hill-type reduced to linear). Zero-dep, pure .cljc.

  The muscle is a single point mass attached to a fixed origin by a
  parallel passive element (spring + damper, rest length L0) and an
  active contractile element that pulls toward the origin (shortening)
  with force = activation * active-max-force (0 <= activation <= 1).

  Sign convention: :length is the mass position along the muscle axis
  measured from the origin [m]; positive = stretched beyond rest length.
  The restoring spring force, damping, and active contractile pull all
  act in the negative direction (toward the origin / shortening) when the
  muscle is stretched and/or activating.

  `step` advances state by semi-implicit Euler with sub-stepping for
  stability. This is a *lumped* teaching/prototyping model, not a FEM
  muscle. Phase 2 routes large-scale soft-tissue deformation through
  kotoba-lang/kami-vehicle's XPBD mass-spring solver instead.")

(def default-params
  "Representative lumped params for an adult skeletal-muscle belly:
  mass ~300 g, rest length ~15 cm, peak isometric force ~1 kN, passive
  stiffness tuned so a +30% stretch gives ~40 N passive restoring force.
  Numbers are order-of-magnitude teaching defaults, not patient-specific."
  {:mass 0.3
   :passive-stiffness 200.0        ; N/m
   :damping 5.0                    ; N·s/m
   :active-max-force 1000.0        ; N
   :rest-length 0.15               ; m
   :optimal-length 0.15            ; m, peak of the active force-length curve
   :max-shortening-velocity 1.0})  ; m/s, |v| at which active force -> 0

(defn make-state
  "Initial muscle state. length [m], velocity [m/s] (default 0)."
  ([length] (make-state length 0.0))
  ([length velocity]
   {:length (double length) :velocity (double velocity)}))

(defn make-params
  "Params map; overrides (keyword or string keys) merge onto default-params."
  ([] default-params)
  ([overrides] (merge default-params (update-keys overrides keyword))))

(defn- clip-activation [a]
  (let [x (double a)] (max 0.0 (min 1.0 x))))

(defn force-length-factor
  "Hill-type active force-length scaling: the active contractile force peaks
  at the muscle's optimal length and falls off as a parabola outside the
  physiological range. Returns a multiplier in [0, 1] — 1.0 at length =
  optimal-length, ~0 outside [0.5, 1.5] * optimal-length."
  [length optimal-length]
  (let [ratio (/ (double length) (double optimal-length))]
    (max 0.0 (- 1.0 (* 4.0 (- ratio 1.0) (- ratio 1.0))))))

(defn force-velocity-factor
  "Hill force-velocity (concentric): active force falls linearly toward 0 as
  shortening velocity grows, reaching 0 at v = -v-max. Eccentric
  (lengthening, v > 0) is capped at 1.0 — no eccentric boost in this
  simplified lumped model. v is muscle length velocity [m/s] (negative =
  shortening)."
  [v v-max]
  (let [v (double v) vmax (double v-max)]
    (if (neg? v)
      (max 0.0 (/ (+ vmax v) vmax))   ; 1 at v=0, 0 at v=-vmax
      1.0)))

(defn acceleration
  "Acceleration [m/s^2] of the mass given state, params, activation (0..1).

  m*a = -k*(L - L0)              ; passive spring restoring (toward L0)
        - c*v                    ; viscous damping (opposes velocity)
        - act*fl(L)*Fmax         ; active contractile pull (toward origin),
                                 ; scaled by Hill force-length factor fl

  i.e. for a stretched (L > L0), outward-moving (v > 0), activating
  muscle, all three force contributions are negative — sign kept explicit
  so the physics reads."
  [{:keys [length velocity]}
   {:keys [mass passive-stiffness damping active-max-force rest-length optimal-length max-shortening-velocity]}
   activation]
  (let [opt       (or optimal-length rest-length)
        vmax      (or max-shortening-velocity 1.0)
        f-spring  (* -1.0 passive-stiffness (- length rest-length))
        f-damper  (* -1.0 damping velocity)
        fl        (force-length-factor length opt)
        fv        (force-velocity-factor velocity vmax)
        f-active  (* -1.0 (clip-activation activation) fl fv active-max-force)]
    (/ (+ f-spring f-damper f-active) mass)))

(defn step
  "Advance muscle state by dt [s] under activation (0..1). Semi-implicit
  Euler with substeps (default 8) for numerical stability."
  ([state params activation dt]
   (step state params activation dt 8))
  ([state params activation dt substeps]
   (let [n   (max 1 (int substeps))
         sdt (/ (double dt) n)]
     (loop [i 0 st state]
       (if (>= i n)
         st
         (let [{:keys [velocity]} st
               a     (acceleration st params activation)
               v-new (+ velocity (* a sdt))
               l-new (+ (:length st) (* v-new sdt))]
           (recur (inc i) {:length l-new :velocity v-new})))))))

(defn simulate
  "Run n steps of size dt from init under constant activation. Returns a
  vector of n successor states (init itself is NOT included — the caller
  already holds it)."
  [init params activation dt n]
  (loop [i 0 st init acc []]
    (if (>= i n)
      acc
      (let [next (step st params activation dt)]
        (recur (inc i) next (conj acc next))))))
