(ns parallax.core.merge
  (:require [clojure.set :as set]))

(defn merge-node-3way
  ;;Performs a 3-way merge on a single scene node's property maps.
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

(defn- merge-single-node-entry
  [b-node o-node t-node]
  (cond
    ;; Case 1: Node exists in all 3 revisions (Base, Ours, Theirs)
    (and (some? b-node) (some? o-node) (some? t-node))
    (if (= b-node o-node t-node)
      {:status    :clean
       :resolved  b-node
       :conflicts []}
      (let [{:keys [resolved conflicts]} (merge-node-3way b-node o-node t-node)]
        {:status    (if (empty? conflicts) :auto-merged :property-conflict)
         :resolved  resolved
         :conflicts conflicts}))

    ;; Case 2: Node existed in Base, deleted in both Ours and Theirs
    (and (some? b-node) (nil? o-node) (nil? t-node))
    {:status    :deleted
     :resolved  nil
     :conflicts []}

    ;; Case 3: Node existed in Base, deleted in Ours, present in Theirs
    (and (some? b-node) (nil? o-node) (some? t-node))
    (if (= b-node t-node)
      {:status    :deleted
       :resolved  nil
       :conflicts []}
      {:status    :structural-conflict
       :resolved  nil
       :conflicts [{:type   :delete-modify
                    :base   b-node
                    :ours   nil
                    :theirs t-node}]})

    ;; Case 4: Node existed in Base, present in Ours, deleted in Theirs
    (and (some? b-node) (some? o-node) (nil? t-node))
    (if (= b-node o-node)
      {:status    :deleted
       :resolved  nil
       :conflicts []}
      {:status    :structural-conflict
       :resolved  nil
       :conflicts [{:type   :delete-modify
                    :base   b-node
                    :ours   o-node
                    :theirs nil}]})

    ;; Case 5: Node did not exist in Base; added only in Ours
    (and (nil? b-node) (some? o-node) (nil? t-node))
    {:status    :added
     :resolved  o-node
     :conflicts []}

    ;; Case 6: Node did not exist in Base; added only in Theirs
    (and (nil? b-node) (nil? o-node) (some? t-node))
    {:status    :added
     :resolved  t-node
     :conflicts []}

    ;; Case 7: Node did not exist in Base; added in BOTH Ours and Theirs
    (and (nil? b-node) (some? o-node) (some? t-node))
    (if (= o-node t-node)
      {:status    :added
       :resolved  o-node
       :conflicts []}
      (let [{:keys [resolved conflicts]} (merge-node-3way {} o-node t-node)]
        {:status    :structural-conflict
         :resolved  resolved
         :conflicts [{:type      :add-add
                      :base      nil
                      :ours      o-node
                      :theirs    t-node
                      :sub-diffs conflicts}]}))))

(defn merge-scene-3way
  ;; Performs a 3-way merge across an entire scene of nodes keyed by node-id.
  [base-scene ours-scene theirs-scene]
  (let [all-ids (distinct (concat (keys base-scene)
                                  (keys ours-scene)
                                  (keys theirs-scene)))]
    (reduce
      (fn [acc node-id]
        (let [b-node (get base-scene node-id)
              o-node (get ours-scene node-id)
              t-node (get theirs-scene node-id)
              entry  (merge-single-node-entry b-node o-node t-node)
              status (:status entry)]
          (cond-> (assoc-in acc [:node-results node-id] entry)
            (contains? #{:clean :auto-merged :added :property-conflict} status)
            (assoc-in [:resolved-nodes node-id] (:resolved entry))

            (contains? #{:property-conflict :structural-conflict} status)
            (assoc :has-conflicts? true))))
      {:resolved-nodes {}
       :node-results   {}
       :has-conflicts? false}
      all-ids)))

