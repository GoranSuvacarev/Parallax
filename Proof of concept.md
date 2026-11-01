
# Parallax: Native 3-Way Spatial Merge Tool for Game Scenes

> **Project outcome:** Design and Implementation of a System for Semantic and Spatial 3-Way Merging of Video Game Scenes 
> **Tech Stack:** 100% Pure Clojure (JVM, `deps.edn`), JavaFX via `cljfx`, Godot 4.x  
> **Interface:** Native Desktop GUI Application 
> **Status:** Proof of Concept

## Chapter 1: Problem Statement: Why Git Fails on Scene Graphs

Standard Version Control Systems (Git/VCS) treat files as flat streams of text lines. However, scenes in modern game engines (such as Godot 4's `.tscn` format) are **hierarchical spatial graphs (Scene Graphs)**.

This mismatch creates three major failure modes in collaborative game development:
1. **False Textual Conflicts:** If Developer A repositions an enemy in world space, and Developer B modifies that same enemy's movement speed in script parameters, Git reports a text-line collision within the `.tscn` file—even though these changes are semantically disjoint and completely safe to merge automatically.
2. **Silent Spatial Regressions (Semantic Collisions):** Git lacks geometric awareness. If Developer A places a solid wall at coordinate $(X, Y)$, and Developer B places a doorway at that exact coordinate, Git reports a clean merge. In the running game, the level is broken because two solid physics colliders intersect in the same space.
3. **Absence of Spatial Context:** Traditional diff utilities only display code lines. Developers have no visual context showing *where* an entity originally sat in the level, *where* it moved, and how that transformation affects nearby geometry.

**Project Parallax Goal:** Build a standalone, pure Clojure desktop application that parses Godot 4 scene files into an immutable Abstract Syntax Tree / Intermediate Representation (AST/IR), runs a property-level 3-way merge, visually detects spatial overlaps, and presents an interactive 2D blueprint desktop GUI for human-in-the-loop conflict resolution.

---

## Chapter 2: Macro-Level Architecture (Pure Clojure Desktop)

Parallax is invoked directly by Git as an external merge tool (`git mergetool`), running entirely within the JVM without spawning any local web servers or web browsers.

```text
[ Git CLI ] (invokes tool on conflict: git mergetool)
     │
     ▼
[ Parallax CLI Runner (Clojure JVM) ]
     │  1. Reads Base, Ours, and Theirs .tscn files from disk via clojure.java.io
     │  2. Runs the State-Machine Parser to convert .tscn into Clojure EDN maps
     │  3. Executes the 3-Way Merge Engine and AABB spatial collision checks
     ▼
[ Native Desktop GUI Window (cljfx / JavaFX) ]
     │  1. Left Pane: Interactive Scene Tree Hierarchy with conflict status badges
     │  2. Center Viewport: 2D Spatial Canvas (shows Base ghost wireframes & candidate vectors)
     │  3. Right Pane: Property Inspector with 1-click resolution buttons
     ▼
[ User clicks "Save & Resolve" ]
     │  1. Serializer converts the resolved EDN state back into valid Godot 4 .tscn syntax
     │  2. Overwrites the target file on disk
     │  3. Closes the JavaFX window and exits -> Git marks conflict as resolved
```

---

## Chapter 3: Per-Node State Machine Decision Matrix

For every unique entity identifier (`unique_id` or canonical scene path) present across the union of node sets $(Base \cup Ours \cup Theirs)$, the merge engine executes a strictly formalized state machine:

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
                                                                                              CONFLICT
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

Because the core engine operates on immutable data structures and pure functions, it runs natively on the JVM with zero external runtime dependencies.

```clojure
(ns parallax.core
  (:require [clojure.set :as set]))

(defn merge-node-3way
  "Performs a semantic 3-way merge on individual node properties.
   
   Inputs:
     base, ours, theirs - Clojure maps representing the node's property states
   Output:
     {:resolved  {...}  ;; Cleanly merged properties
      :conflicts [...]} ;; Conflicting fields requiring manual resolution"
  [base ours theirs]
  (let [;; Set union of all property keys across all three revisions
        all-keys (set/union (set (keys base))
                            (set (keys ours))
                            (set (keys theirs)))]
    (reduce
     (fn [acc k]
       (let [b (get base k)
             o (get ours k)
             t (get theirs k)]
         (cond
           ;; Case 1: No change occurred, or both branches made an identical change
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

## Chapter 5: Verification Test: Real-World Godot 4 Scene Node

Validating the merge logic against node `NPCLeft` (`unique_id = 884256495`) extracted from my 2D top-down shooter:

### Scenario:
* **Base:** Object sits at coordinate `[358.0, 132.0]`, has animation `"default"`, and speed `6.0`.
* **Ours:** Level design change moves the entity in world space to `[400.0, 132.0]`.
* **Theirs:** Gameplay programming change adjusts animation to `"walk"` and speed to `10.0`.

```clojure
(def base-node
  {:id 884256495
   :name "NPCLeft"
   :position [358.0 132.0]
   :animation "default"
   :speed 6.0})

(def ours-node
  {:id 884256495
   :name "NPCLeft"
   :position [400.0 132.0]  ;; Spatial level change
   :animation "default"
   :speed 6.0})

(def theirs-node
  {:id 884256495
   :name "NPCLeft"
   :position [358.0 132.0]
   :animation "walk"        ;; Gameplay logic change
   :speed 10.0})

;; Run evaluation:
(merge-node-3way base-node ours-node theirs-node)
```

### Result:
```clojure
;; Textual Git flags this entire block as an unresolvable collision.
;; Parallax recognizes property independence and executes a clean merge:
{:resolved
 {:id 884256495
  :name "NPCLeft"
  :position [400.0 132.0]   ;; Automatically accepts Ours position
  :animation "walk"         ;; Automatically merges Theirs animation
  :speed 10.0}              ;; Automatically merges Theirs speed
 :conflicts []}             ;; Zero conflicts!
```
