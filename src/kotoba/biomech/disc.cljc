(ns kotoba.biomech.disc
  "Axial compression of an intervertebral disc. Zero-dep, pure .cljc.

  WHY THIS EXISTS. cloud-itonami/suji computes, for every intervertebral level,
  an axial force and divides it by a disc area to report a `:stress-mpa`. It then
  cross-checks that against Wilke et al. 1999's in-vivo intradiscal pressure. So
  a consumer of this family already has a STRESS on a disc and no way to turn it
  into a DEFORMATION, because until now this repo carried no disc tissue at all.

  This namespace closes that step — and its most useful behaviour is that it
  REFUSES to close it at the stress the consumer actually has.

  THE MEASUREMENT THAT MOTIVATES THE REFUSAL. Wilke's relaxed-sitting L4/L5
  pressure is 0.46 MPa. The annulus fibrosus reference aggregate modulus from
  Iatridis et al. 1998 is 0.56 MPa. Dividing gives a linear axial strain of
  0.82 — EIGHTY-TWO PERCENT. On a 10 mm disc that is 8.2 mm of height loss, from
  a disc that is 10 mm tall, at the load of sitting still on a stool. In-vivo
  diurnal disc height loss over an entire day is on the order of a millimetre.
  The linear answer is not merely imprecise, it is impossible, and a library that
  returned 8.2 mm would be handing a consumer a number that looks like a result.

  WHY IT COMES OUT THAT WAY, FROM THE SOURCES THEMSELVES, NOT FROM HINDSIGHT:

  - H_A0 IS A REFERENCE MODULUS OF A NONLINEAR MODEL. The same sentence of
    Iatridis 1998 that reports 0.56 +/- 0.21 MPa also reports a nonlinear
    stiffening coefficient beta = 2.13 +/- 1.48 for normal tissue. H_A0 is the
    stiffness at the zero-strain reference state; the tissue stiffens from there.
    Using it as if it were constant therefore OVERSTATES deformation, and
    overstates it more the further you go.
  - IN VIVO THE LOAD IS NOT CARRIED BY THE SOLID MATRIX. Johannessen & Elliott
    2005 conclude, verbatim, `Swelling is the primary load-bearing mechanism in
    both nondegenerate and degenerate nucleus pulposus`. An elastic modulus
    describes matrix deformation, which is not the path the load takes.
  - A CONFINED-COMPRESSION MODULUS IS AN EQUILIBRIUM PROPERTY OF A CONFINED
    SPECIMEN. A disc in a living spine is neither that specimen nor at
    equilibrium.

  So this namespace computes the linear strain, states it, and withholds the
  height loss whenever the linear reading cannot be defended. This is the same
  shape as `suji.methods.muscle/ligament-at-limit?`, which refuses to extrapolate
  a ligament curve past its calibration for the same reason: its author found
  that extrapolating gave the nuchal ligament 52,312 N at an ordinary posture.

  WHAT THIS IS NOT. Not a disc model. There is no fluid phase, no swelling
  pressure, no time, no creep, no annulus/nucleus interaction, no nonlinearity —
  a real disc is a pressurised vessel whose annulus is loaded in hoop tension by
  a nucleus that behaves as a fluid, and none of that is here. This is the one
  linear step from stress to strain, with an honest boundary on where it holds."
  (:require [kotoba.biomech.tissue :as tissue]))

(def default-linear-strain-limit
  "Strain past which this namespace stops reporting a height loss (0.05 = 5%).

  THIS NUMBER IS A MODELLING CONVENTION OF THIS NAMESPACE. It is NOT from
  Iatridis 1998 and is not claimed to be. What the source supplies is the
  DIRECTION of the argument — a nonlinear stiffening coefficient beta =
  2.13 +/- 1.48, which says the linear reading degrades as strain grows and in
  which direction it errs. It does not supply a cut-off, and inventing one and
  attributing it would be worse than choosing one and saying so.

  Callers who want a different boundary should pass their own; callers who want
  none can pass a limit of 1.0 and own the result."
  0.05)

(defn axial-strain
  "Linear axial strain (dimensionless) for a compressive `stress-pa` against an
  aggregate modulus `modulus-pa`.

  epsilon = sigma / H_A. Sign convention: both arguments are positive magnitudes
  for compression, and the returned strain is a positive magnitude of shortening.

  Returns nil rather than throwing when the modulus is absent or non-positive —
  a tissue with no modulus (the nucleus pulposus carries no Young's modulus at
  all, deliberately) must not produce a strain."
  [stress-pa modulus-pa]
  (when (and (number? stress-pa) (number? modulus-pa)
             (pos? (double modulus-pa)))
    (/ (double stress-pa) (double modulus-pa))))

(defn beyond-linear-range?
  "True when `strain` is at or past `limit`, i.e. when a linear modulus should no
  longer be read as a deformation. Nil strain is beyond the range: an answer that
  could not be computed is not an answer inside the range."
  ([strain] (beyond-linear-range? strain default-linear-strain-limit))
  ([strain limit]
   (or (nil? strain)
       (>= (double strain) (double limit)))))

(defn axial-compression
  "Compress a disc tissue axially and report what can and cannot be defended.

  Arguments: a tissue map `t` (annulus fibrosus or nucleus pulposus from the
  presets), the axial compressive stress in PASCALS, the unloaded disc height in
  METRES, and optionally a strain limit.

  NOTE ON UNITS. Consumers quote disc stress in MPa because that is the unit
  in-vivo disc tolerances are published in — cloud-itonami/suji reports
  `:stress-mpa`. This function takes PASCALS, like every other modulus and stress
  in this repo. Multiply by 1e6 at the boundary; `stress-mpa->pa` does it.

  Returns a map:
    :strain                 linear axial strain, always present when computable
    :height-loss-m/-mm      NIL when the linear reading is beyond its range
    :refused                nil, or a keyword saying why no height loss is given
    :beyond-linear-range?   boolean
    :aggregate-modulus-pa   the modulus actually used
    :tissue, :source        what answered, and its provenance

  THE HEIGHT LOSS IS WITHHELD, NOT CLAMPED. Clamping would return a number at the
  limit, and a caller would use it. The absence is the message."
  ([t stress-pa height-m]
   (axial-compression t stress-pa height-m default-linear-strain-limit))
  ([t stress-pa height-m limit]
   (let [h-a    (tissue/aggregate-modulus t)
         strain (axial-strain stress-pa h-a)
         beyond (beyond-linear-range? strain limit)
         base   {:tissue (:name t)
                 :aggregate-modulus-pa h-a
                 :stress-pa (when (number? stress-pa) (double stress-pa))
                 :strain strain
                 :beyond-linear-range? beyond
                 :limit (double limit)
                 :source (tissue/source t)}]
     (cond
       (nil? h-a)
       (assoc base :height-loss-m nil :height-loss-mm nil
              :refused :no-aggregate-modulus
              :note (str "tissue " (pr-str (:name t))
                         " carries no confined-compression aggregate modulus,"
                         " so no axial strain can be computed from it"))

       (nil? strain)
       (assoc base :height-loss-m nil :height-loss-mm nil
              :refused :strain-not-computable
              :note "stress and a positive aggregate modulus are both required")

       beyond
       (assoc base :height-loss-m nil :height-loss-mm nil
              :refused :beyond-linear-range
              :note (str "linear strain " strain " is at or past the limit " limit
                         "; H_A0 is the zero-strain reference modulus of a"
                         " NONLINEAR model (Iatridis 1998 reports a stiffening"
                         " coefficient beta = 2.13 +/- 1.48 for normal tissue),"
                         " so a linear reading OVERSTATES deformation here and"
                         " the height loss is withheld rather than clamped"))

       :else
       (let [loss-m (* (double height-m) (double strain))]
         (assoc base
                :height-loss-m loss-m
                :height-loss-mm (* 1000.0 loss-m)
                :refused nil
                :note nil))))))

(defn stress-mpa->pa
  "Megapascals to pascals. Present because the consumers of this namespace quote
  disc stress in MPa (suji's `:stress-mpa`, Wilke's published pressures) while
  this repo works in Pa, and a silent factor of 1e6 in that conversion is exactly
  the kind of error a disc stress is large enough to hide."
  [mpa]
  (when (number? mpa) (* 1.0e6 (double mpa))))
