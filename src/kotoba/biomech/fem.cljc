(ns kotoba.biomech.fem
  "Bone FEM via kotoba-lang/fea (Phase 2 backend).

  Bridges biomech tissue material properties into fea's linear-static
  beam2 solver. Scope matches fea Phase-1 exactly:
  - fea assembles :beam2 (1-D bar) elements only (hex8/tet4 raise
    :unsupported-element), so this namespace models slender bone as an
    axial bar.
  - fea's beam2 uses a unit cross-section (A = 1 m²) and axial loading
    only (not transverse bending). Stresses therefore come back as
    Pa = N/m² (since A = 1), and the analytic reference is delta = F·L/E,
    sigma = F/A = F.
  - For 3-D bone FEM (arbitrary geometry, bending), wait for fea's
    element-assembly expansion; this namespace will grow then."
  (:require [kotoba.fea.mesh :as fea-mesh]
            [kotoba.fea.solver :as fea-solver]
            [kotoba.fea.boundary :as fea-boundary]
            [kotoba.biomech.tissue :as tissue]))

(defn tissue->fea-material
  "Build a fea :linear-elastic material map from a biomech tissue.
  Uses the tissue's youngs-modulus / poissons-ratio / density. Tissues
  whose :model/:type is not :linear-elastic are still projected to
  linear-elastic using their effective youngs-modulus — a modelling
  simplification (fea Phase-1 is linear-static only)."
  [t]
  {:name (str (or (:name t) "tissue"))
   :model {:type :linear-elastic
           :youngs-modulus (double (or (tissue/youngs-modulus t) 1.0e6))
           :poissons-ratio (double (or (tissue/poissons-ratio t) 0.3))
           :density (double (or (tissue/density t) 1000.0))}})

(defn axial-bar-mesh
  "Build a 1-D beam2 mesh along +X from x=0 to x=length, `divisions`
  beam2 elements. Node-set \"fixed\" = root node (id 0); \"tip\" = last
  node. Returns a fea mesh map."
  [length divisions]
  (let [n (max 1 (int divisions))
        dx (/ (double length) n)
        {:keys [mesh ids]}
        (loop [i 0 m (fea-mesh/new-mesh) ids []]
          (if (> i n)
            {:mesh m :ids ids}
            (let [x (* dx i)
                  {:keys [mesh id]} (fea-mesh/add-node m [(double x) 0.0 0.0])]
              (recur (inc i) mesh (conj ids id)))))
        mesh-with-elems
        (loop [j 0 m mesh]
          (if (>= j n)
            m
            (let [e (fea-mesh/beam2 j [(nth ids j) (nth ids (inc j))])]
              (recur (inc j) (fea-mesh/add-element m e)))))]
    (-> mesh-with-elems
        (fea-mesh/create-node-set "fixed" [(first ids)])
        (fea-mesh/create-node-set "tip" [(peek ids)]))))

(defn solve-axial-bar
  "Solve a 1-D axial bar of tissue `t`, length L [m], `divisions` beam2
  elements, under axial tip load F [N] along +X (positive = tension).
  Root node is fully fixed. Returns fea's result map
  (:max-displacement, :max-stress, :displacement, :stress, ...).

  Analytic reference (fea A = 1 m²): delta = F·L / E, sigma = F / A = F."
  [t length force divisions]
  (let [mesh (axial-bar-mesh length divisions)
        mat  (tissue->fea-material t)
        bcs  [(fea-boundary/displacement "fixed" fea-boundary/dof-all [0.0 0.0 0.0])
              (fea-boundary/force "tip" [(double force) 0.0 0.0])]]
    (fea-solver/solve-linear-static mesh mat bcs)))
