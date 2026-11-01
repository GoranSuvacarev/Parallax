# Parallax: Visual 3-Way Spatial Merge Tool for Game Scenes

> **Academic Project:** Design and Implementation of a System for Semantic and Spatial 3-Way Merging of Video Game Scenes Using the Functional Paradigm  
> **Tech Stack:** Clojure / ClojureScript (`shadow-cljs`), Node.js, Web/SVG, Godot 4.x  
> **Status:** Proof of Concept (Early Architectural Design Phase)

---

## Chapter 1: Introduction & Problem Statement: Why Git Fails on Scene Graphs

Standard Version Control Systems (Git/VCS) treat project files as flat sequences of text lines. However, scenes in modern game engines (such as Godot 4's `.tscn` format) are **hierarchical spatial graphs (Scene Graphs)**.

This mismatch leads to three fundamental issues in collaborative game development:
1. **False Textual Conflicts:** If Developer A repositions an enemy within a level, and Developer B tweaks a script parameter on that same enemy, Git reports a text-line collision within the scene file—even though these changes are semantically disjoint and completely safe to merge automatically.
2. **Silent Spatial Regressions (Silent Semantic Bugs):** Git lacks geometric awareness. If Developer A moves a wall to coordinate $(X, Y)$, and Developer B places a door at that exact same coordinate, Git performs a successful, clean merge. In the game, this produces a critical bug: two physical colliders overlapping in the exact same spatial volume.
3. **Absence of Spatial Context:** Conventional diff tools only display text-based diffs. Developers have no visual context showing *where* an entity originally existed in the level, *where* it was moved, or how that transformation impacts nearby level geometry.

**Goal of the Parallax Project:** To design and implement a specialized merge tool that parses scene files into an immutable Abstract Syntax Tree / Intermediate Representation (AST/IR), applies a semantic 3-way merge at the granular property level while preserving node identity, visually detects spatial overlaps, and provides an interactive 2D CAD blueprint viewport in the browser.

---

## Chapter 2: Macro-Level System Architecture

The system is designed as a *Headless CLI Bridge* that integrates natively into standard Git workflows via `git mergetool`, backed by a browser-based UI for visual conflict resolution.

```text
[ Git CLI ] (detects a conflict in level.tscn and triggers: git mergetool)
     │
     ▼
[ CLI Runner (Node.js / ClojureScript) ]
     │  1. Reads three file revisions: Base (common ancestor), Ours, and Theirs
     │  2. Runs a State-Machine Parser to convert .tscn into Clojure EDN maps
     │  3. Spins up an ephemeral local server and opens the default browser
     ▼
[ Core Merge Engine (Pure Clojure Functions) ]
     │  1. Executes the formal decision matrix on every node
     │  2. Calculates AABB intersections (soft spatial conflict detection)
     ▼
[ Web UI Viewport (Reagent + SVG) ]
     │  1. Renders a 2D CAD Blueprint of the level in engine-space coordinates
     │  2. Displays "ghosting" wireframes of past states along with motion vectors
     │  3. Enables human-in-the-loop spatial resolution via mouse clicks
     ▼
[ Serializer back to Disk ]
     │  1. Reconstructs a valid, clean Godot 4 .tscn file
     │  2. Shuts down the local server and exits with status code 0 (Conflict Resolved)
```

---

## Chapter 3: Decision Matrix: Per-Node State Machine

For every unique entity identifier (`unique_id` or canonical scene path) present across the union of node sets $(Base \cup Ours \cup Theirs)$, the algorithm follows a strictly formalized state machine:

```text
                      +-------------------------------------------------------------+
                      |               For each ID in (Base ∪ Ours ∪ Theirs)         |
                      +-------------------------------------------------------------+
                                                     |
                 +-----------------------------------+-----------------------------------+
                 |                                   |                                   |
                 v                                   v                                   v
         [ In Base Only ]                    [ In Base & One Branch ]            [ In Ours & Theirs Only ]
       (Deleted in both)                   (Deleted in one, modified in other)      (Both added a new node)
                 |                                   |                                   |
                 v                                   v                         +---------+---------+
            🟢 DELETED                      🔴 STRUCTURAL CONFLICT             v                   v
          (Auto-resolve)                     (Delete/Modify Conflict)     Same data?          Different?
                                                                         (Auto-merge)      🔴 STRUCTURAL
                                                                                              CONFLIKT
                                                     |
                                                     |
                                                     v
                             +-----------------------------------------------+
                             |      Node exists in all 3 states (B, O, T)    |
                             +-----------------------------------------------+
                                                     |
                 +-------------------+---------------+-------------------+
                 |                   |               |                   |
                 v                   v               v                   v
           Neither modified     Modified by Ours ONLY   Modified by Theirs ONLY   Modified by BOTH
                 |                   |               |                   |
                 v                   v               v                   v
             Keep Base          Accept Ours     Accept Theirs   PROPERTY-LEVEL DIFF
            (No change)         (Auto-merge)     (Auto-merge)            |
                                                                         |
                      +--------------------------------------------------+
                      v
        +---------------------------------------------------------------------------------+
        |                 Analyze Individual Properties (Transform, Properties)           |
        +---------------------------------------------------------------------------------+
                      |                                                                   |
                      v                                                                   v
          Modified Disjoint Properties                                        Modified Identical Property
       (e.g., Ours changed Pos, Theirs changed Speed)                     (Both modified Position to [x, y])
                      |                                                                   |
                      v                                                                   v
             🟢 AUTO-MERGE PROPERTIES                                            🔴 VALUE CONFLICT
            (Both modifications merged)                                         (Requires human resolution)
```

---

## Chapter 4: System Core: Pure Clojure 3-Way Merge Function

Input states are represented as immutable maps, and the function's output deterministically splits into cleanly merged attributes (`:resolved`) and isolated conflicts (`:conflicts`).

```clojure
(ns parallax.core
  (:require [clojure.set :as set]))

(defn merge-node-3way
  "Performs a semantic 3-way merge on individual node properties.
   
   Inputs:
     base, ours, theirs - Clojure maps representing the node's property states
   Output:
     {:resolved  {...}  ;; Map of cleanly and automatically merged properties
      :conflicts [...]} ;; Vector of maps detailing conflicting property fields"
  [base ours theirs]
  (let [;; Set union of all property keys present across all three revisions
        all-keys (set/union (set (keys base))
                            (set (keys ours))
                            (set (keys theirs)))]
    (reduce
     (fn [acc k]
       (let [b (get base k)
             o (get ours k)
             t (get theirs k)]
         (cond
           ;; Case 1: No change occurred, or both branches made an IDENTICAL change
           (= o t)
           (assoc-in acc [:resolved k] o)

           ;; Case 2: Changed ONLY by 'Ours' (Theirs matches Base)
           (= t b)
           (assoc-in acc [:resolved k] o)

           ;; Case 3: Changed ONLY by 'Theirs' (Ours matches Base)
           (= o b)
           (assoc-in acc [:resolved k] t)

           ;; Case 4: Both modified the SAME property to DIFFERENT values -> CONFLICT
           :else
           (update acc :conflicts conj {:field   k
                                        :base    b
                                        :ours    o
                                        :theirs  t}))))
     {:resolved {} :conflicts []}
     all-keys)))
```

---

## Chapter 5: Verification Test: Demonstration on a Godot 4 Scene

Validating the core merge logic against a real-world scenario using node `NPCLeft` (`unique_id = 884256495`) extracted from a 2D top-down shooter game:

### Scenario:
* **Base:** Object sits at coordinate `[358.0, 132.0]`, has animation `"default"`, and speed `6.0`.
* **Ours:** A level designer moves the object in world space to `[400.0, 132.0]`.
* **Theirs:** A gameplay programmer tunes behavior, changing animation to `"walk"` and speed to `10.0`.

### REPL Execution:

```clojure
;; 1. Define input states (Node AST maps)
(def base-node
  {:id 884256495
   :name "NPCLeft"
   :position [358.0 132.0]
   :animation "default"
   :speed 6.0})

(def ours-node
  {:id 884256495
   :name "NPCLeft"
   :position [400.0 132.0]  ;; Spatial change
   :animation "default"
   :speed 6.0})

(def theirs-node
  {:id 884256495
   :name "NPCLeft"
   :position [358.0 132.0]
   :animation "walk"        ;; Logic change
   :speed 10.0})

;; 2. Run the pure merge function
(merge-node-3way base-node ours-node theirs-node)
```

### Evaluated Output:

```clojure
;; Standard line-based Git would declare a textual conflict over this entire block.
;; Our Core engine recognizes property independence and executes a clean merge:
{:resolved
 {:id 884256495
  :name "NPCLeft"
  :position [400.0 132.0]   ;; <-- Automatically accepted new spatial position
  :animation "walk"         ;; <-- Cleanly merged logic change from Theirs
  :speed 10.0}              ;; <-- Cleanly merged speed tuning from Theirs
 :conflicts []}             ;; Zero conflicts!
```

If both branches had changed `:position` to conflicting coordinates, the `:conflicts` vector would return structured data containing the ancestor position alongside both incoming candidate vectors—ready for rendering in the SVG Viewport for spatial selection.
