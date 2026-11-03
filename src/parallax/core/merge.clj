(ns parallax.core.merge
  (:require [clojure.set :as set]))

(defn merge-node-3way
  "Performs a 3-way merge on a single scene node's property maps.
   Accepts `base` (common ancestor), `ours` (local branch), and `theirs` (remote branch).
   Returns a map with `:resolved` (cleanly merged properties) and `:conflicts`
   (vector of conflict maps `{:field k, :base b, :ours o, :theirs t}`)."
  [base ours theirs]
  (let [all-keys (distinct (concat (keys base) (keys ours) (keys theirs)))]
    (reduce
      (fn [acc k]
        (let [b (get base k)
              o (get ours k)
              t (get theirs k)]
          (cond
            ;; No changes, or identical changes in both Ours and Theirs
            (= o t)
            (if (contains? ours k)
              (update acc :resolved assoc k o)
              acc)

            ;; One-sided modification by Theirs (Ours matches Base)
            (= o b)
            (if (contains? theirs k)
              (update acc :resolved assoc k t)
              acc)

            ;; One-sided modification by Ours (Theirs matches Base)
            (= t b)
            (if (contains? ours k)
              (update acc :resolved assoc k o)
              acc)

            ;; Value collision: both Ours and Theirs changed the property to different values
            :else
            (update acc :conflicts conj {:field  k
                                         :base   b
                                         :ours   o
                                         :theirs t}))))
      {:resolved  {}
       :conflicts []}
      all-keys)))
