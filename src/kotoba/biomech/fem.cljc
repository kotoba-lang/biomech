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

;; --- 3-D tet4 path (fea Phase-2: tet4 element assembly) ---

(defn tet4-cube-mesh
  "Cube [0,L]^3 split into 5 tet4 elements (the standard split along the
  0-6 body diagonal). Node-set \"fixed\" = bottom face (z=0, nodes 0-3);
  \"load\" = top face (z=L, nodes 4-7). Returns a fea mesh map."
  [L]
  (let [coords [[0.0 0.0 0.0] [L 0.0 0.0] [L L 0.0] [0.0 L 0.0]
                [0.0 0.0 L] [L 0.0 L] [L L L] [0.0 L L]]
        {:keys [mesh ids]}
        (loop [i 0 m (fea-mesh/new-mesh) ids []]
          (if (= i 8)
            {:mesh m :ids ids}
            (let [{:keys [mesh id]} (fea-mesh/add-node m (coords i))]
              (recur (inc i) mesh (conj ids id)))))]
    (-> mesh
        (fea-mesh/add-element (fea-mesh/tet4 0 [(ids 0) (ids 1) (ids 2) (ids 6)]))
        (fea-mesh/add-element (fea-mesh/tet4 1 [(ids 0) (ids 2) (ids 3) (ids 6)]))
        (fea-mesh/add-element (fea-mesh/tet4 2 [(ids 0) (ids 3) (ids 7) (ids 6)]))
        (fea-mesh/add-element (fea-mesh/tet4 3 [(ids 0) (ids 7) (ids 4) (ids 6)]))
        (fea-mesh/add-element (fea-mesh/tet4 4 [(ids 0) (ids 4) (ids 5) (ids 6)]))
        (fea-mesh/create-node-set "fixed" [(ids 0) (ids 1) (ids 2) (ids 3)])
        (fea-mesh/create-node-set "load"   [(ids 4) (ids 5) (ids 6) (ids 7)]))))

(defn solve-tet4-cube
  "3-D cube of tissue `t`, side L [m], under axial tip load F [N] along +Z
  (positive = tension). Bottom face (z=0) fixed. Returns fea's result map.
  Uses fea's tet4 element assembly (3-D elasticity — bending / shear, not
  just axial), the Phase-2 expansion of fea's solver."
  [t L force]
  (let [mesh (tet4-cube-mesh (double L))
        mat  (tissue->fea-material t)
        bcs  [(fea-boundary/displacement "fixed" fea-boundary/dof-all [0.0 0.0 0.0])
              (fea-boundary/force "load" [0.0 0.0 (double force)])]]
    (fea-solver/solve-linear-static mesh mat bcs)))
