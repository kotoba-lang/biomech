(ns kotoba.biomech.muscle
  "1-D lumped mass-spring-damper muscle model with an active contractile
  element (Hill-type reduced to linear). Zero-dep, pure .cljc.

  The muscle is a single point mass attached to a fixed origin by a
  parallel passive element (spring + damper, rest length L0) and an
  active contractile element that pulls toward the origin (shortening),
  scaled by activation, force-length, and force-velocity relationships.
  The muscle-tendon path adds first-order excitation/activation dynamics and
  a tension-only series-elastic tendon; the original instantaneous-activation
  `step` API remains available for compatibility.

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
   :max-shortening-velocity 1.0     ; m/s, |v| at which active force -> 0
   :eccentric-max-factor 1.5        ; max active force while lengthening / Fmax
   :activation-time-constant 0.015  ; s, excitation rising response
   :deactivation-time-constant 0.050 ; s, excitation falling response
   :tendon-slack-length 0.20        ; m
   :tendon-stiffness 20000.0})      ; N/m, linear series-elastic element

(defn- clip-activation [a]
  (let [x (double a)] (max 0.0 (min 1.0 x))))

(defn make-state
  "Initial muscle state. length [m], velocity [m/s], activation [0,1]."
  ([length] (make-state length 0.0 0.0))
  ([length velocity]
   (make-state length velocity 0.0))
  ([length velocity activation]
   {:length (double length)
    :velocity (double velocity)
    :activation (clip-activation activation)}))

(defn make-params
  "Params map; overrides (keyword or string keys) merge onto default-params."
  ([] default-params)
  ([overrides] (merge default-params (update-keys overrides keyword))))

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
  shortening velocity grows, reaching 0 at v = -v-max. During eccentric
  contraction (lengthening, v > 0), force rises linearly from the isometric
  value to `eccentric-max-factor` at v-max and remains capped there. v is
  muscle length velocity [m/s] (negative = shortening)."
  ([v v-max]
   (force-velocity-factor v v-max 1.5))
  ([v v-max eccentric-max-factor]
   (let [v (double v)
         vmax (double v-max)
         eccentric-max (max 1.0 (double eccentric-max-factor))]
     (when-not (pos? vmax)
       (throw (ex-info "v-max must be positive" {:v-max v-max})))
    (if (neg? v)
      (max 0.0 (/ (+ vmax v) vmax))   ; 1 at v=0, 0 at v=-vmax
      (min eccentric-max
           (+ 1.0 (* (- eccentric-max 1.0) (/ v vmax))))))))

(defn activation-step
  "Advance neural activation toward excitation over dt [s]. Rising and
  falling responses use separate time constants and an exact exponential
  update, so the result remains in [0,1] for any non-negative dt."
  [activation excitation
   {:keys [activation-time-constant deactivation-time-constant]}
   dt]
  (let [a (clip-activation activation)
        e (clip-activation excitation)
        tau (double (if (> e a)
                      (or activation-time-constant 0.015)
                      (or deactivation-time-constant 0.050)))
        dt (double dt)]
    (when-not (pos? tau)
      (throw (ex-info "activation time constant must be positive" {:tau tau})))
    (when (neg? dt)
      (throw (ex-info "dt must be non-negative" {:dt dt})))
    (clip-activation (+ e (* (- a e) (Math/exp (/ (- dt) tau)))))))

(defn tendon-force
  "Tensile force [N] in a linear series-elastic tendon. `mtu-length` is the
  total muscle-tendon-unit length; the contractile-element length comes from
  state. Tendon is tension-only and produces zero force below slack length."
  [{:keys [length]}
   {:keys [tendon-slack-length tendon-stiffness]}
   mtu-length]
  (let [slack (double (or tendon-slack-length 0.20))
        stiffness (double (or tendon-stiffness 20000.0))
        extension (- (double mtu-length) (double length) slack)]
    (when (neg? stiffness)
      (throw (ex-info "tendon stiffness must be non-negative"
                      {:tendon-stiffness stiffness})))
    (* stiffness (max 0.0 extension))))

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
   {:keys [mass passive-stiffness damping active-max-force rest-length optimal-length
           max-shortening-velocity eccentric-max-factor]}
   activation]
  (let [opt       (or optimal-length rest-length)
        vmax      (or max-shortening-velocity 1.0)
        f-spring  (* -1.0 passive-stiffness (- length rest-length))
        f-damper  (* -1.0 damping velocity)
        fl        (force-length-factor length opt)
        fv        (force-velocity-factor velocity vmax (or eccentric-max-factor 1.5))
        f-active  (* -1.0 (clip-activation activation) fl fv active-max-force)]
    (/ (+ f-spring f-damper f-active) mass)))

(defn muscle-tendon-acceleration
  "Contractile-element acceleration [m/s^2] in a fixed-length muscle-tendon
  unit. Active/passive muscle force pulls toward shortening; the series
  tendon pulls toward lengthening. State activation, rather than instantaneous
  excitation, drives the active force."
  [state params mtu-length]
  (+ (acceleration state params (or (:activation state) 0.0))
     (/ (tendon-force state params mtu-length) (:mass params))))

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
           (recur (inc i) (assoc st :length l-new :velocity v-new))))))))

(defn step-muscle-tendon
  "Advance a Hill-type muscle-tendon unit by dt [s] under neural excitation.
  Activation dynamics and series-elastic tendon force are integrated within
  each substep. `mtu-length` is held fixed during this step."
  ([state params excitation mtu-length dt]
   (step-muscle-tendon state params excitation mtu-length dt 8))
  ([state params excitation mtu-length dt substeps]
   (let [n (max 1 (int substeps))
         sdt (/ (double dt) n)]
     (loop [i 0 st state]
       (if (>= i n)
         st
         (let [a-new (activation-step (or (:activation st) 0.0)
                                      excitation params sdt)
               active-state (assoc st :activation a-new)
               a (muscle-tendon-acceleration active-state params mtu-length)
               v-new (+ (:velocity st) (* a sdt))
               l-new (+ (:length st) (* v-new sdt))]
           (recur (inc i) (assoc active-state
                                 :length l-new
                                 :velocity v-new))))))))

(defn simulate-muscle-tendon
  "Run n fixed-length muscle-tendon-unit steps; returns successor states."
  [init params excitation mtu-length dt n]
  (loop [i 0 st init acc []]
    (if (>= i n)
      acc
      (let [next (step-muscle-tendon st params excitation mtu-length dt)]
        (recur (inc i) next (conj acc next))))))

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
