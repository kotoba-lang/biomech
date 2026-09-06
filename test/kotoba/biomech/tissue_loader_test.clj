(ns kotoba.biomech.tissue-loader-test
  "JVM test that the bundled tissues.edn resource loads and contains the full
  tissue catalogue (cortical/cancellous bone, muscle, skin, organ, tendon, the
  Phase-2.2 additions cartilage, ligament, vasculature, brain, adipose, and the
  Phase-2.3 intervertebral-disc pair)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [kotoba.biomech.tissue-loader :as loader]
            [kotoba.biomech.tissue :as tissue]))

(deftest presets-load-and-contain-catalogue-test
  (let [presets (loader/presets)]
    (is (<= 13 (count presets)))
    (doseq [name ["Cortical-Bone" "Cancellous-Bone" "Skeletal-Muscle" "Skin"
                  "Liver" "Tendon" "Cartilage" "Ligament" "Arterial-Wall"
                  "Brain" "Adipose-Tissue"
                  "Annulus-Fibrosus" "Nucleus-Pulposus"]]
      (is (tissue/find-tissue presets name)
          (str "expected tissue " name " in presets")))
    ;; every entry must pass the tissue? shape check
    (is (every? tissue/tissue? presets))))

(deftest legacy-tissue-scalars-are-unchanged-test
  ;; PINS THE OLD BEHAVIOUR ACROSS THE SCHEMA EXTENSION. Every value here
  ;; predates the anisotropy work. If adding :directional / :aggregate-modulus /
  ;; :provenance ever perturbs a scalar a consumer already reads, this fails.
  (let [ps (loader/presets)
        E  #(tissue/youngs-modulus (tissue/find-tissue ps %))
        nu #(tissue/poissons-ratio (tissue/find-tissue ps %))
        d  #(tissue/density (tissue/find-tissue ps %))]
    (is (= 1.7e10 (E "Cortical-Bone")))   (is (= 0.30 (nu "Cortical-Bone")))   (is (= 1900 (d "Cortical-Bone")))
    (is (= 5.0e8  (E "Cancellous-Bone"))) (is (= 0.30 (nu "Cancellous-Bone"))) (is (= 800  (d "Cancellous-Bone")))
    (is (= 3.0e4  (E "Skeletal-Muscle"))) (is (= 0.45 (nu "Skeletal-Muscle"))) (is (= 1060 (d "Skeletal-Muscle")))
    (is (= 1.0e4  (tissue/shear-modulus (tissue/find-tissue ps "Skeletal-Muscle"))))
    (is (= 1.0e5  (E "Skin")))            (is (= 0.45 (nu "Skin")))            (is (= 1100 (d "Skin")))
    (is (= 3.0e3  (E "Liver")))           (is (= 0.45 (nu "Liver")))           (is (= 1060 (d "Liver")))
    (is (= 1.2e9  (E "Tendon")))          (is (= 0.40 (nu "Tendon")))          (is (= 1100 (d "Tendon")))
    ;; CARTILAGE'S POISSON'S RATIO IS NO LONGER LEGACY. 0.40 cited nobody;
    ;; Keenan 2009 measured 0.00-0.05 in human cartilage and 0.05 is the high
    ;; end of that band. The modulus and density are still the legacy values.
    (is (= 8.0e5  (E "Cartilage")))       (is (= 0.05 (nu "Cartilage")))       (is (= 1100 (d "Cartilage")))
    (is (= 5.0e5  (E "Arterial-Wall")))   (is (= 0.45 (nu "Arterial-Wall")))   (is (= 1060 (d "Arterial-Wall")))
    (is (= 3.0e3  (E "Brain")))           (is (= 0.45 (nu "Brain")))           (is (= 1040 (d "Brain")))
    (is (= 3.0e3  (E "Adipose-Tissue")))  (is (= 0.45 (nu "Adipose-Tissue")))  (is (= 950  (d "Adipose-Tissue")))
    (testing "the ligament was re-sourced, NOT re-valued"
      ;; Neumann 1992 gives an ALL 'overall' tensile modulus of 759 MPa (S.D.
      ;; 336), i.e. 423-1095 MPa. 5.0e8 Pa was already inside that band, so
      ;; adding the citation changed no number.
      (is (= 5.0e8 (E "Ligament")))
      (is (= 0.40  (nu "Ligament")))
      (is (= 1100  (d "Ligament"))))))

(deftest disc-entries-carry-read-provenance-test
  (let [ps (loader/presets)]
    (doseq [nm ["Annulus-Fibrosus" "Nucleus-Pulposus" "Ligament"]]
      (let [t (tissue/find-tissue ps nm)
            srcs (tissue/sources t)]
        (is (seq srcs) (str nm " must cite at least one source actually read"))
        (doseq [s srcs]
          (is (string? (:citation s)) (str nm " citation must be a string"))
          (is (string? (:doi s))      (str nm " must carry a DOI"))
          (is (string? (:pmid s))     (str nm " must carry a PMID"))
          (is (string? (:read s))     (str nm " must record what was read"))
          (is (= :abstract (:obtained s))
              (str nm ": every number came from an abstract, not a full text"))
          (is (re-find #"\d" (:read s))
              (str nm ": the read record must contain the numbers it justifies")))))))

(deftest sources-sought-and-not-obtained-are-recorded-test
  ;; A value that cannot be sourced is recorded as absent WITH THE REASON, rather
  ;; than invented or silently skipped.
  (let [ps (loader/presets)
        af (tissue/find-tissue ps "Annulus-Fibrosus")
        lg (tissue/find-tissue ps "Ligament")]
    (is (= 1 (count (tissue/unobtained af))))
    (is (= :numbers-not-in-abstract
           (:could-not-obtain (first (tissue/unobtained af))))
        "Ebara 1996: abstract read in full, directional but non-numeric")
    (is (= 1 (count (tissue/unobtained lg))))
    (is (= :numbers-not-in-abstract
           (:could-not-obtain (first (tissue/unobtained lg))))
        "Pintar 1992 would settle the per-ligament moduli and publishes none")))

(deftest annulus-scalar-is-the-axial-direction-not-an-average-test
  ;; THE ANISOTROPY IS STATED, NOT AVERAGED. The single number the legacy
  ;; accessor returns is one measured direction, and it is the axial one --
  ;; the axis a spinal consumer loads -- not the mean of the three.
  (let [af (tissue/find-tissue (loader/presets) "Annulus-Fibrosus")]
    (is (= 8.0e5 (tissue/youngs-modulus af)))
    (is (= (tissue/directional-modulus af :axial) (tissue/youngs-modulus af)))
    (is (= 1.74e7 (tissue/directional-modulus af :circumferential-outer)))
    (is (= 5.6e6  (tissue/directional-modulus af :circumferential-inner)))
    (testing "circumferential is ~22x the axial value; a mean would be neither"
      (is (= 21.75 (/ (tissue/directional-modulus af :circumferential-outer)
                      (tissue/directional-modulus af :axial)))))
    (testing "and it is refused by the isotropic path"
      (is (= 0.67 (tissue/poissons-ratio af)))
      (is (false? (tissue/isotropically-admissible? af))))))

(deftest nucleus-carries-no-youngs-modulus-deliberately-test
  ;; Iatridis 1997 measured this tissue's shear stress relaxing NEARLY TO ZERO.
  ;; E = 2G(1+nu) would manufacture a Young's modulus for a tissue the source
  ;; says is not a solid. The absence is the honest answer and is pinned so that
  ;; nobody "completes" the entry later.
  (let [np (tissue/find-tissue (loader/presets) "Nucleus-Pulposus")]
    (is (nil? (tissue/youngs-modulus np)))
    (is (nil? (tissue/poissons-ratio np)))
    (is (= 7.0e3 (tissue/shear-modulus np))
        "the LOW end of the measured 7-20 kPa band, not a midpoint")
    (is (= [7.0e3 2.0e4] (get-in np [:model :shear-modulus-range])))
    (is (= [1.0 100.0] (get-in np [:model :angular-frequency-range-rad-s]))
        "a shear modulus without its frequency band would be meaningless here")
    (is (= 1.01e6 (tissue/aggregate-modulus np)))
    (is (false? (tissue/isotropically-admissible? np))
        "fail-closed: no Poisson's ratio means no certification")))

(deftest no-tissue-field-is-an-unevaluated-form-test
  ;; REGRESSION GUARD FOR A REAL MISTAKE MADE WRITING THESE ENTRIES.
  ;; The first draft wrapped long strings as (str "..." "..."), an idiom copied
  ;; from Clojure source into an EDN data file. Nothing evaluates `str` in EDN.
  ;; edn/read-string did NOT throw -- it returned the full catalogue and looked
  ;; clean, while the field held a PersistentList instead of a string. Parsing
  ;; is not validation; the TYPE is. Symbols are checked too: a stray bare symbol
  ;; in a key position is the signature of the heredoc backslash-quote class.
  (let [ps (loader/presets)
        bad (atom [])]
    (doseq [t ps]
      (walk/postwalk
       (fn [x]
         (when (list? x)   (swap! bad conj [(:name t) :unevaluated-list (pr-str x)]))
         (when (symbol? x) (swap! bad conj [(:name t) :bare-symbol (pr-str x)]))
         x)
       t)
      (when-not (string? (:source t))
        (swap! bad conj [(:name t) :source-not-a-string (type (:source t))])))
    ;; One assertion, so the suite's assertion count stays a meaningful number
    ;; rather than being dominated by a tree walk.
    (is (= [] @bad)
        (str "unevaluated forms or bare symbols in tissues.edn: " (pr-str @bad)))
    (is (pos? (count ps)) "and the walk must have had something to walk")))

(deftest disc-biphasic-fields-are-numbers-not-merely-present-test
  ;; THE TYPE IS THE TEST, not the presence. `edn/read-string` will hand back a
  ;; PersistentList for `(str "0.9" "e-15")` and it prints in a listing exactly
  ;; like a value; `some?` would pass on it and every arithmetic use of it would
  ;; then throw somewhere else entirely. These five fields are new and are the
  ;; inputs to a time constant, so they are checked as doubles and pinned to the
  ;; values their abstracts state.
  (let [ps (loader/presets)
        af (tissue/find-tissue ps "Annulus-Fibrosus")
        np (tissue/find-tissue ps "Nucleus-Pulposus")]
    (testing "anulus, Iatridis 1998: sigma(offset) 0.13 MPa, k0 0.20e-15, beta 2.13, M 1.18"
      (is (double? (tissue/swelling-stress af)))
      (is (= 1.3e5 (tissue/swelling-stress af)))
      (is (double? (tissue/permeability af)))
      (is (= 2.0e-16 (tissue/permeability af)))
      (is (double? (tissue/nonlinear-stiffening-coefficient af)))
      (is (= 2.13 (tissue/nonlinear-stiffening-coefficient af)))
      (is (double? (tissue/permeability-strain-coefficient af)))
      (is (= 1.18 (tissue/permeability-strain-coefficient af))))
    (testing "nucleus, Johannessen & Elliott 2005: Psw 0.138 MPa, ka 0.9e-15"
      (is (double? (tissue/swelling-stress np)))
      (is (= 1.38e5 (tissue/swelling-stress np)))
      (is (double? (tissue/permeability np)))
      (is (= 9.0e-16 (tissue/permeability np))))
    (testing "and the nucleus has NO nonlinear parameters, because none were measured"
      ;; Johannessen & Elliott state verbatim that they used linear biphasic
      ;; theory. nil here is a claim about the literature, not an omission, and
      ;; kotoba.biomech.disc reports it as :nonlinear-stiffening-never-measured
      ;; rather than as an unused coefficient.
      (is (nil? (tissue/nonlinear-stiffening-coefficient np)))
      (is (nil? (tissue/permeability-strain-coefficient np))))
    (testing "the single-phase tissues have none of it, and are not given defaults"
      (let [liver (tissue/find-tissue ps "Liver")]
        (is (nil? (tissue/swelling-stress liver)))
        (is (nil? (tissue/permeability liver)))))))

(deftest disc-read-records-quote-the-permeability-sentences-test
  ;; Every number added above must be traceable to a quotation in the entry, and
  ;; the quotation must contain the number. A citation without its sentence is
  ;; how a plausible value gets attributed to a paper that does not contain it.
  (let [ps (loader/presets)
        read-of (fn [nm needle]
                  (->> (tissue/sources (tissue/find-tissue ps nm))
                       (map :read)
                       (filter #(re-find needle %))
                       first))]
    (is (re-find #"0\.20\+/-0\.10 x 10\(-15\) m4/N-s and 1\.18\+/-1\.30"
                 (or (read-of "Annulus-Fibrosus" #"k0") "")))
    (is (re-find #"ka = 0\.9 \+/- 0\.43 x 10\(-15\) m4/N-s nondegenerate"
                 (or (read-of "Nucleus-Pulposus" #"ka") "")))
    (is (re-find #"Linear biphasic theory was used"
                 (or (read-of "Nucleus-Pulposus" #"Linear biphasic") "")))
    (testing "and the sentence that justifies reading sigma(offset) as swelling pressure"
      (is (re-find #"shift in load carriage from fluid pressurization and swelling pressure to deformation of the solid matrix"
                   (or (read-of "Annulus-Fibrosus" #"shift in load carriage") ""))))))

;; ---------------------------------------------------------------------------
;; PROVENANCE FOR THE TEN TISSUES THAT CITED NOBODY.
;;
;; Every test below pins ONE claim: that a specific number in tissues.edn is the
;; number a specific paper reports, in the words that paper used. The pattern is
;; deliberate -- an assertion on the value alone would pass against a value
;; someone invented, and an assertion on the citation alone would pass against a
;; citation attached to the wrong number. Pinning the value AND requiring the
;; quotation that justifies it to contain that value is what makes the pair
;; inseparable.
;; ---------------------------------------------------------------------------

(defn- ^:private tissue-named [nm]
  (tissue/find-tissue (loader/presets) nm))

(defn- ^:private read-matching
  "The :read text of the first source of tissue `nm` matching `needle`, or \"\"."
  [nm needle]
  (or (->> (tissue/sources (tissue-named nm))
           (map :read)
           (filter #(re-find needle %))
           first)
      ""))

(deftest tendon-modulus-is-maganaris-own-number-test
  ;; The tendon is the one entry where the carried value and the source's value
  ;; are the SAME number rather than one bounding the other. That distinction is
  ;; the whole content of :scalar, so it is asserted here and not assumed.
  (let [t (tissue-named "Tendon")]
    (is (= 1.2e9 (tissue/youngs-modulus t)))
    (is (= :sourced (tissue/scalar-provenance t))
        "1.2 GPa is Maganaris & Paul's own figure, not a value inside a band")
    (is (re-find #"Young's modulus at maximum isometric load were 161 N mm-1 and 1\.2 GPa"
                 (read-matching "Tendon" #"1\.2 GPa"))
        "the quotation must contain the very number the entry carries")
    (testing "and the regime that makes 1.2 GPa a floor rather than a ceiling"
      (is (re-find #"operates within the elastic 'toe' region"
                   (read-matching "Tendon" #"toe"))))))

(deftest cartilage-aggregate-modulus-is-no-longer-a-youngs-modulus-test
  ;; THE CORRECTION IS THE FIELD, NOT THE NUMBER. The entry's own :source text
  ;; always said "aggregate modulus", and the value sat in :youngs-modulus,
  ;; where kotoba.biomech.fem reads it and hands it to an isotropic solver as a
  ;; Young's modulus. Nothing raised, because the two coincide to 0.53% at
  ;; Keenan's measured Poisson's ratio -- which is why this went unnoticed and
  ;; why a test on the VALUE alone could never have caught it.
  (let [t (tissue-named "Cartilage")]
    (is (= :biphasic (get-in t [:model :type]))
        "the source fits a linear biphasic model, not a hyperelastic one")
    (is (= 8.0e5 (tissue/aggregate-modulus t))
        "H_A now has the field kotoba.biomech.tissue/aggregate-modulus names")
    (is (double? (tissue/aggregate-modulus t)))
    (is (= [4.8e5 1.58e6] (get-in t [:model :aggregate-modulus-range]))
        "Keenan's five-site range, so the scalar can be seen sitting inside it")
    (is (= :unsourced-but-bounded (tissue/scalar-provenance t))
        "8.0e5 is inside 0.48-1.58 MPa and is nobody's measurement")))

(deftest cartilage-poissons-ratio-was-corrected-downward-with-a-source-test
  ;; 0.40 -> 0.05. The old value cited nobody; a near-zero Poisson's ratio is
  ;; what a biphasic fit of cartilage reports. The consequence is not cosmetic:
  ;; K = E/(3(1-2nu)) is 1.667E at 0.40 and 0.370E at 0.05, so the old value
  ;; made the tissue 4.5x stiffer in bulk than the measurement.
  (let [t (tissue-named "Cartilage")
        K #(/ 1.0 (* 3.0 (- 1.0 (* 2.0 %))))]
    (is (= 0.05 (tissue/poissons-ratio t)))
    (is (= [0.0 0.05] (get-in t [:model :poissons-ratio-range])))
    (is (true? (tissue/isotropically-admissible? t))
        "0.05 is well inside -1 < nu < 1/2, so the fea bridge still accepts it")
    (testing "the old value overstated the bulk modulus by 4.5x"
      (is (< 4.4 (/ (K 0.40) (K 0.05)) 4.6)))
    (testing "and the quotation carries every one of Keenan's three coefficients"
      (is (re-find #"aggregate moduli \(0\.48-1\.58 MPa\), Poisson's ratio \(0\.00-0\.05\) and permeability"
                   (read-matching "Cartilage" #"0\.48-1\.58"))))))

(deftest cartilage-permeability-is-a-named-endpoint-not-a-midpoint-test
  ;; Same convention the nucleus already uses for its |G*| band: take an
  ;; endpoint the source printed and say which, rather than averaging two
  ;; numbers into a third that appears nowhere.
  (let [t (tissue-named "Cartilage")]
    (is (= 1.7e-15 (tissue/permeability t)))
    (is (double? (tissue/permeability t)))
    (is (= [1.7e-15 5.4e-15] (get-in t [:model :permeability-range])))
    (is (= (tissue/permeability t) (first (get-in t [:model :permeability-range])))
        "the scalar must BE an endpoint of the range, not merely inside it")))
