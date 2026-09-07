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
    ;; MUSCLE'S MODULI ARE NO LONGER LEGACY. 1.0e4 cited nobody and is 1.7x
    ;; Koo's measured SLACK shear modulus, i.e. a stretched muscle sitting in a
    ;; field callers read as muscle at rest. E follows the entry's own
    ;; long-standing 3G convention. nu and density stay legacy.
    (is (= 1.74e4 (E "Skeletal-Muscle"))) (is (= 0.45 (nu "Skeletal-Muscle"))) (is (= 1060 (d "Skeletal-Muscle")))
    (is (= 5.8e3  (tissue/shear-modulus (tissue/find-tissue ps "Skeletal-Muscle"))))
    ;; SKIN'S MODULUS IS NO LONGER LEGACY. 1.0e5 cited nobody and sits below
    ;; every quantity read for human skin; 1.18e6 is Ni Annaidh's measured
    ;; initial slope. nu and density are still the legacy values.
    (is (= 1.18e6 (E "Skin")))            (is (= 0.45 (nu "Skin")))            (is (= 1100 (d "Skin")))
    ;; LIVER'S MODULUS IS NO LONGER LEGACY. 3.0e3 cited nobody and looks like
    ;; an MR-elastography SHEAR stiffness written into a Young's-modulus field;
    ;; 6.0e3 is E = 3G from Rouviere's measured 2.0 kPa. nu and density are
    ;; still the legacy values.
    (is (= 6.0e3  (E "Liver")))           (is (= 0.45 (nu "Liver")))           (is (= 1060 (d "Liver")))
    (is (= 1.2e9  (E "Tendon")))          (is (= 0.40 (nu "Tendon")))          (is (= 1100 (d "Tendon")))
    ;; CARTILAGE'S POISSON'S RATIO IS NO LONGER LEGACY. 0.40 cited nobody;
    ;; Keenan 2009 measured 0.00-0.05 in human cartilage and 0.05 is the high
    ;; end of that band. The modulus and density are still the legacy values.
    (is (= 8.0e5  (E "Cartilage")))       (is (= 0.05 (nu "Cartilage")))       (is (= 1100 (d "Cartilage")))
    ;; THE ARTERIAL WALL'S MODULUS IS NO LONGER LEGACY. 5.0e5 cited nobody,
    ;; described the MEDIA rather than the whole wall, and lies below the whole
    ;; 0.97-1.39 MPa band Koullias measured. nu and density stay legacy.
    (is (= 1.18e6 (E "Arterial-Wall")))   (is (= 0.45 (nu "Arterial-Wall")))   (is (= 1060 (d "Arterial-Wall")))
    ;; BRAIN'S MODULUS IS NO LONGER LEGACY. 3.0e3 cited nobody and claimed an
    ;; MR-elastography provenance no source read here supplies; 2.1e3 is
    ;; 3*mu_inf for Budday's gray matter cortex. nu and density stay legacy.
    (is (= 2.1e3  (E "Brain")))           (is (= 0.45 (nu "Brain")))           (is (= 1040 (d "Brain")))
    ;; ADIPOSE'S MODULUS IS NO LONGER LEGACY. 3.0e3 cited nobody and is within
    ;; 4% of Alkhouli's OMENTAL initial modulus rather than the subcutaneous
    ;; one; 1.6e3 is the subcutaneous value. nu and density stay legacy.
    (is (= 1.6e3  (E "Adipose-Tissue")))  (is (= 0.45 (nu "Adipose-Tissue")))  (is (= 950  (d "Adipose-Tissue")))
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

(deftest skin-scalar-is-the-initial-slope-and-the-other-regime-is-carried-test
  ;; ONE SPECIMEN SET, TWO MODULI, 70x APART. Ni Annaidh's abstract names both,
  ;; so choosing one and hiding the other would be choosing on the reader's
  ;; behalf without telling them. The scalar is the initial slope; the
  ;; linear-region value is beside it with its own SD.
  (let [t (tissue-named "Skin")
        rg #(get-in t [:model :strain-regimes % :youngs-modulus])]
    (is (= 1.18e6 (tissue/youngs-modulus t)))
    (is (= (rg :initial) (tissue/youngs-modulus t))
        "the scalar must BE the initial-slope entry, not merely near it")
    (is (= 8.33e7 (rg :linear-region)))
    (is (double? (rg :linear-region)))
    (is (= 8.8e5  (get-in t [:model :strain-regimes :initial :sd])))
    (is (= 3.49e7 (get-in t [:model :strain-regimes :linear-region :sd])))
    (testing "the two regimes really are ~70x apart, which is why both are here"
      (is (< 69.0 (/ (rg :linear-region) (rg :initial)) 71.0)))
    (testing "and the old 1.0e5 is below even the initial slope minus one SD"
      (is (< 1.0e5 (- (rg :initial) (get-in t [:model :strain-regimes :initial :sd])))))
    (is (= :sourced (tissue/scalar-provenance t)))))

(deftest skin-anisotropy-is-established-but-its-values-were-not-obtained-test
  ;; THE HONEST SHAPE OF A MEASURED-BUT-UNPUBLISHED ANISOTROPY. The MANOVA
  ;; proves orientation matters; the abstract prints no per-orientation modulus.
  ;; :directional therefore carries the basis and an explicit marker, and
  ;; directional-modulus answers nil for every direction rather than handing
  ;; back the orientation-averaged scalar dressed as a direction.
  (let [t (tissue-named "Skin")]
    (is (some? (tissue/directional t)))
    (is (= :not-in-abstract (get-in t [:model :directional :per-orientation-values])))
    (doseq [dir [:along-langer-lines :across-langer-lines :axial :circumferential-outer]]
      (is (nil? (tissue/directional-modulus t dir))
          (str "skin must not answer a modulus for direction " dir)))
    (testing "and the quotation carries the significance levels, not a value"
      (is (re-find #"dependent upon the orientation of the Langer lines \(P<0\.0001-P=0\.046\)"
                   (read-matching "Skin" #"Langer"))))))

(deftest liver-youngs-modulus-is-derived-from-the-measured-shear-stiffness-test
  ;; MR ELASTOGRAPHY DOES NOT REPORT A YOUNG'S MODULUS. It reports a shear
  ;; stiffness, and the old entry carried an MRE-sized number in the
  ;; Young's-modulus field. The measured quantity is now in :shear-modulus and
  ;; the scalar is the identity E = 3G applied to it, so the derivation can be
  ;; checked here rather than believed.
  (let [t (tissue-named "Liver")]
    (is (= 2.0e3 (tissue/shear-modulus t)) "Rouviere's measured shear stiffness")
    (is (= 3.0e2 (get-in t [:model :shear-modulus-sd])))
    (is (= 6.0e3 (tissue/youngs-modulus t)))
    (is (= (* 3.0 (tissue/shear-modulus t)) (tissue/youngs-modulus t))
        "E must BE 3G, not merely near it -- the identity is the whole claim")
    (testing "and nu = 0.45 would move it by less than the source's own SD"
      (let [e-at-nu (* 2.0 (tissue/shear-modulus t)
                       (+ 1.0 (tissue/poissons-ratio t)))]
        (is (< (abs (- (tissue/youngs-modulus t) e-at-nu))
               (* 3.0 (get-in t [:model :shear-modulus-sd])))
            "3% from the nu choice against 15% from the measurement spread")))
    (is (= :sourced (tissue/scalar-provenance t)))))

(deftest liver-capsule-source-supplies-no-number-and-says-so-test
  ;; A SOURCE READ, WITH NUMBERS, THAT BACKS NOTHING HERE. Karimi & Shojaei
  ;; measured Glisson's capsule, not the parenchyma. Filing it under :sources
  ;; without a marker would let a reader attribute the entry's scalars to it;
  ;; filing it under :unobtained would be false, because it was obtained.
  (let [t (tissue-named "Liver")
        srcs (tissue/sources t)
        cap (first (filter #(= "29131053" (:pmid %)) srcs))
        parenchyma (first (filter #(= "16864671" (:pmid %)) srcs))]
    (is (some? cap))
    (is (false? (:supplies-number? cap))
        "the capsule paper must declare that it backs no value in this entry")
    (is (nil? (:supplies-number? parenchyma))
        "and absence of the key must still mean 'this one does supply numbers'")
    (testing "the capsule numbers are carried under their own key, not as liver"
      (is (= 1.216e4 (get-in t [:model :capsule :tensile-youngs-modulus-axial])))
      (is (not= (get-in t [:model :capsule :tensile-youngs-modulus-axial])
                (tissue/youngs-modulus t))))))

(deftest adipose-scalar-is-subcutaneous-not-the-stiffer-omental-depot-test
  ;; PAIRED SAMPLES FROM THE SAME 19 SUBJECTS, TWO DEPOTS, SIGNIFICANTLY
  ;; DIFFERENT. The old 3.0e3 sat within 4% of the OMENTAL initial modulus, so
  ;; a body model asking for "fat" was quietly getting visceral fat. The scalar
  ;; now names its depot and the other depot is carried, not averaged in.
  (let [t (tissue-named "Adipose-Tissue")
        at #(get-in t [:model :sites %1 %2])]
    (is (= 1.6e3 (tissue/youngs-modulus t)))
    (is (= (at :subcutaneous :initial-youngs-modulus) (tissue/youngs-modulus t))
        "the scalar must BE the subcutaneous initial entry")
    (is (not= (at :omental :initial-youngs-modulus) (tissue/youngs-modulus t)))
    (is (= 2.9e3  (at :omental :initial-youngs-modulus)))
    (is (= 1.17e4 (at :subcutaneous :final-youngs-modulus)))
    (is (= 3.2e4  (at :omental :final-youngs-modulus)))
    (testing "omental is stiffer than subcutaneous at BOTH ends of the curve"
      (is (< (at :subcutaneous :initial-youngs-modulus)
             (at :omental :initial-youngs-modulus)))
      (is (< (at :subcutaneous :final-youngs-modulus)
             (at :omental :final-youngs-modulus))))
    (testing "and the retired 3.0e3 really was nearer omental than subcutaneous"
      (is (< (abs (- 3.0e3 (at :omental :initial-youngs-modulus)))
             (abs (- 3.0e3 (at :subcutaneous :initial-youngs-modulus))))))))

(deftest adipose-abstract-came-from-crossref-not-europe-pmc-test
  ;; A RETRIEVAL FACT WORTH PINNING. Europe PMC returns this PMID with no
  ;; abstractText at all, so a search that used only Europe PMC would have
  ;; recorded these numbers as unobtainable. Every source in the file now says
  ;; how it was reached, and this is the one where it mattered.
  (let [t (tissue-named "Adipose-Tissue")
        src (first (tissue/sources t))]
    (is (= :crossref (:retrieved-via src)))
    (is (= :abstract (:obtained src)))
    (is (re-find #"initial 1\.6 \+/- 0\.8 \(means \+/- SD\) and 2\.9 \+/- 1\.5 kPa"
                 (:read src))
        "the quotation must carry both depots' initial moduli")
    (testing "and every other source in the catalogue also declares its route"
      (doseq [tis (loader/presets)
              s   (concat (tissue/sources tis) (tissue/unobtained tis))]
        (is (or (nil? (:retrieved-via s))
                (contains? #{:crossref :europe-pmc-rest} (:retrieved-via s)))
            (str (:name tis) ": unknown retrieval route "
                 (pr-str (:retrieved-via s))))))))

(deftest adipose-anisotropy-is-known-but-unquantified-test
  ;; Sommer 2013 establishes that the tissue is anisotropic and prints no
  ;; parameter. The entry carries an isotropic scalar BECAUSE the numbers that
  ;; would replace it were not obtained -- not because the anisotropy is absent.
  ;; Those are different states and the file must not collapse them.
  (let [t (tissue-named "Adipose-Tissue")
        un (first (tissue/unobtained t))]
    (is (= 1 (count (tissue/unobtained t))))
    (is (= "23811521" (:pmid un)))
    (is (= :numbers-not-in-abstract (:could-not-obtain un)))
    (is (= :not-in-abstract (get-in t [:model :directional :per-direction-values])))
    (is (nil? (tissue/directional-modulus t :along-septa))
        "no direction may be answered, since none was measured")))

(deftest brain-single-modulus-is-carried-with-the-span-that-defeats-it-test
  ;; THE ENTRY'S OWN CLAIM IS THAT ITS SCALAR IS NOT DEFENSIBLE ALONE, so the
  ;; test asserts the span rather than the number. Two regions x two time scales
  ;; from one paper give a 9x range, and pre-conditioning multiplies gray matter
  ;; by up to three on top of that. A consumer reading only :youngs-modulus is
  ;; taking one corner of that box.
  (let [t (tissue-named "Brain")
        at #(get-in t [:model :sites %1 %2])]
    (is (= :viscoelastic (get-in t [:model :type]))
        "the source fits a finite viscoelastic model; time is not a refinement here")
    (is (= 2.1e3 (tissue/youngs-modulus t)))
    (is (= (at :cortex-gray-matter :youngs-modulus-equilibrium)
           (tissue/youngs-modulus t))
        "the scalar must BE the gray-matter equilibrium entry")
    (is (= 7.0e2 (tissue/shear-modulus t)) "mu_inf for the cortex, as measured")
    (is (= (* 3.0 (tissue/shear-modulus t)) (tissue/youngs-modulus t))
        "and the scalar must BE 3*mu_inf, the identity the entry claims")
    (testing "instantaneous is 3.9x equilibrium in the SAME region"
      (let [r (/ (at :cortex-gray-matter :youngs-modulus-instantaneous)
                 (at :cortex-gray-matter :youngs-modulus-equilibrium))]
        (is (< 3.8 r 4.0))))
    (testing "and the two reported regions differ by another 2.3x at equilibrium"
      (let [r (/ (at :cortex-gray-matter :youngs-modulus-equilibrium)
                 (at :corona-radiata-white-matter :youngs-modulus-equilibrium))]
        (is (< 2.2 r 2.4))))
    (testing "the carried range must span every value the entry itself holds"
      (let [[lo hi] (get-in t [:model :youngs-modulus-range])
            vals [(at :cortex-gray-matter :youngs-modulus-equilibrium)
                  (at :cortex-gray-matter :youngs-modulus-instantaneous)
                  (at :corona-radiata-white-matter :youngs-modulus-equilibrium)
                  (at :corona-radiata-white-matter :youngs-modulus-instantaneous)]]
        (is (= lo (apply min vals)))
        (is (= hi (apply max vals)))))))

(deftest brain-regions-without-published-parameters-are-marked-not-omitted-test
  ;; Budday tested FOUR regions and the abstract prints parameters for TWO.
  ;; Leaving the other two out would read as "only two regions exist"; marking
  ;; them says the measurement was made and the numbers were not obtained.
  (let [t (tissue-named "Brain")]
    (is (= :parameters-not-in-abstract (get-in t [:model :sites :basal-ganglia])))
    (is (= :parameters-not-in-abstract (get-in t [:model :sites :corpus-callosum])))
    (testing "and the pre-conditioning factor is stored as the upper bound it is"
      (is (= 3.0 (get-in t [:model :preconditioning :gray-matter-softening-factor])))
      (is (re-find #"up to a factor three"
                   (get-in t [:model :preconditioning :note])))
      (is (re-find #"UPPER BOUND"
                   (get-in t [:model :preconditioning :note]))))))

(deftest muscle-shear-modulus-is-the-slack-value-and-the-slope-is-carried-test
  ;; A PASSIVE MUSCLE MODULUS IS A BOUNDARY CONDITION, NOT A MATERIAL CONSTANT.
  ;; Koo's whole result is that the modulus rises exponentially once the joint
  ;; passes the slack angle, so the entry carries the slack value AND the angle
  ;; it holds at AND the rate of departure from it. Carrying the scalar alone
  ;; would be carrying the least informative third of the measurement.
  (let [t (tissue-named "Skeletal-Muscle")
        ps #(get-in t [:model :passive-stretch %])]
    (is (= 5.8e3 (tissue/shear-modulus t)))
    (is (= (ps :slack-shear-modulus) (tissue/shear-modulus t))
        "the scalar must BE the slack entry, not a value near it")
    (is (= 1.9e3   (get-in t [:model :shear-modulus-sd])))
    (is (= 10.9    (ps :slack-angle-deg)))
    (is (= 6.3     (ps :slack-angle-sd-deg)))
    (is (= 0.0347  (ps :elasticity-rate-per-deg)))
    (is (= 0.0082  (ps :elasticity-rate-sd-per-deg)))
    (testing "the slack angle's SD is more than half its mean, which is why a
              single joint-angle-free modulus cannot be a material constant"
      (is (> (ps :slack-angle-sd-deg) (* 0.5 (ps :slack-angle-deg)))))
    (testing "and E remains the entry's declared 3G convention, now on the new G"
      (is (= 1.74e4 (tissue/youngs-modulus t)))
      (is (< (abs (- (tissue/youngs-modulus t)
                     (* 3.0 (tissue/shear-modulus t))))
             1.0)))
    (is (= :sourced (tissue/scalar-provenance t)))))

(deftest muscle-direction-is-explicitly-uncertified-not-assumed-isotropic-test
  ;; Muscle is transversely isotropic, Gennisson confirms the anisotropy is
  ;; measurable, and NEITHER abstract gives a value -- Koo's does not even state
  ;; the probe orientation. So the entry marks the direction as uncertified.
  ;; "We did not read which shear modulus this is" and "this tissue is
  ;; isotropic" are different claims and only one of them is true.
  (let [t (tissue-named "Skeletal-Muscle")]
    (is (= :not-in-abstract
           (get-in t [:model :directional :per-direction-values])))
    (is (= :not-stated-in-abstract
           (get-in t [:model :directional :probe-orientation])))
    (doseq [dir [:along-fibre :cross-fibre :axial]]
      (is (nil? (tissue/directional-modulus t dir))))
    (let [un (first (tissue/unobtained t))]
      (is (= "20420970" (:pmid un)))
      (is (= :numbers-not-in-abstract (:could-not-obtain un))))))

(deftest arterial-scalar-is-inside-the-measured-band-the-old-one-missed-test
  ;; THE OLD VALUE WAS NOT MERELY UNCITED, IT WAS OUTSIDE THE MEASUREMENT.
  ;; Koullias reports 1.18 +/- 0.21 MPa for normal aortas, a band of
  ;; 0.97-1.39 MPa. The retired 5.0e5 is below all of it. Asserting the band
  ;; rather than the number is what makes this a claim about evidence.
  (let [t (tissue-named "Arterial-Wall")
        e  (tissue/youngs-modulus t)
        sd (get-in t [:model :youngs-modulus-sd])]
    (is (= 1.18e6 e))
    (is (= 2.1e5 sd))
    (testing "the scalar is the reported mean, so it sits at the band's centre"
      (is (< (- e sd) e (+ e sd))))
    (testing "and the retired 5.0e5 is below the band's lower bound"
      (is (< 5.0e5 (- e sd))))
    (is (= :circumferential (get-in t [:model :directional :scalar-direction]))
        "a pressure-diameter modulus is circumferential and must say so")))

(deftest arterial-layer-structure-is-carried-without-inventing-layer-moduli-test
  ;; Holzapfel identifies the retired value as a MEDIA figure and states the
  ;; media longitudinally is the SOFTEST layer -- which is why using it for the
  ;; whole wall understated the wall. His abstract publishes no layer modulus,
  ;; so the entry carries thickness fractions and an ordering and marks the
  ;; moduli absent. Supplying none is the result; inventing three would not be.
  (let [t (tissue-named "Arterial-Wall")
        L #(get-in t [:model :layers %])
        holz (first (filter #(= "16006541" (:pmid %)) (tissue/sources t)))]
    (is (false? (:supplies-number? holz)))
    (is (= :not-in-abstract (L :per-layer-moduli)))
    (is (re-find #"intima is the stiffest layer" (:read holz)))
    (is (re-find #"media in the longitudinal direction is the softest" (:read holz)))
    (testing "the three thickness fractions are carried and must sum to ~1"
      (let [s (+ (L :thickness-fraction-adventitia)
                 (L :thickness-fraction-media)
                 (L :thickness-fraction-intima))]
        (is (< 1.02 s 1.04)
            (str "Holzapfel's own fractions 0.4/0.36/0.27 sum to " s
                 " -- carried as published, not renormalised to hide it"))))))
