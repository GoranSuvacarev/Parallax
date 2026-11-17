(ns parallax.core.merge-test
  (:require [clojure.test :refer [deftest is testing]]
            [parallax.core.merge :refer [merge-node-3way merge-scene-3way]]))

(deftest merge-node-3way-test
  (testing "Node with no changes across branches"
    (let [base   {:position [100 200] :speed 300 :visible true}
          ours   {:position [100 200] :speed 300 :visible true}
          theirs {:position [100 200] :speed 300 :visible true}
          result (merge-node-3way base ours theirs)]
      (is (= {:position [100 200] :speed 300 :visible true}
             (:resolved result)))
      (is (empty? (:conflicts result)))))

  (testing "Identical changes made by both branches"
    (let [base   {:position [100 200] :speed 300}
          ours   {:position [150 250] :speed 300}
          theirs {:position [150 250] :speed 300}
          result (merge-node-3way base ours theirs)]
      (is (= {:position [150 250] :speed 300}
             (:resolved result)))
      (is (empty? (:conflicts result)))))

  (testing "Independent changes only by Ours or only by Theirs"
    (testing "Only Ours modifies a property"
      (let [base   {:position [0 0] :speed 100}
            ours   {:position [50 50] :speed 100}
            theirs {:position [0 0] :speed 100}
            result (merge-node-3way base ours theirs)]
        (is (= {:position [50 50] :speed 100}
               (:resolved result)))
        (is (empty? (:conflicts result)))))

    (testing "Only Theirs modifies a property"
      (let [base   {:position [0 0] :speed 100}
            ours   {:position [0 0] :speed 100}
            theirs {:position [0 0] :speed 250}
            result (merge-node-3way base ours theirs)]
        (is (= {:position [0 0] :speed 250}
               (:resolved result)))
        (is (empty? (:conflicts result))))))

  (testing "Disjoint changes (Godot scenario: Ours changes spatial :position, Theirs changes logic :animation and :speed)"
    (let [base   {:position [100 100] :animation "idle" :speed 200 :z_index 1}
          ours   {:position [250 180] :animation "idle" :speed 200 :z_index 1}
          theirs {:position [100 100] :animation "run"  :speed 350 :z_index 1}
          result (merge-node-3way base ours theirs)]
      (is (= {:position  [250 180]
              :animation "run"
              :speed     350
              :z_index   1}
             (:resolved result)))
      (is (empty? (:conflicts result)))))

  (testing "Conflicting changes on the same property (both changed :position to different coordinates)"
    (let [base   {:position [100 100] :speed 200}
          ours   {:position [200 150] :speed 200}
          theirs {:position [300 400] :speed 250}
          result (merge-node-3way base ours theirs)]
      (is (= {:speed 250}
             (:resolved result)))
      (is (= [{:field  :position
               :base   [100 100]
               :ours   [200 150]
               :theirs [300 400]}]
             (:conflicts result)))))

  (testing "Addition of completely new properties in Ours or Theirs that did not exist in Base"
    (let [base   {:position [0 0]}
          ours   {:position [0 0] :modulate "#ff0000"}
          theirs {:position [0 0] :collision_layer 2}
          result (merge-node-3way base ours theirs)]
      (is (= {:position        [0 0]
              :modulate        "#ff0000"
              :collision_layer 2}
             (:resolved result)))
      (is (empty? (:conflicts result))))))

(deftest merge-scene-3way-test
  (testing "US1: Shared nodes across all 3 revisions (:clean, :auto-merged, :property-conflict)"
    (let [base   {:player {:id :player :position [0 0] :speed 100}
                  :npc    {:id :npc    :position [358.0 132.0] :animation "default" :speed 6.0}}
          ours   {:player {:id :player :position [0 0] :speed 100}
                  :npc    {:id :npc    :position [400.0 132.0] :animation "default" :speed 6.0}}
          theirs {:player {:id :player :position [0 0] :speed 100}
                  :npc    {:id :npc    :position [358.0 132.0] :animation "walk"    :speed 10.0}}
          result (merge-scene-3way base ours theirs)]
      (is (false? (:has-conflicts? result)))
      (is (= :clean (get-in result [:node-results :player :status])))
      (is (= :auto-merged (get-in result [:node-results :npc :status])))
      (is (= {:id :npc :position [400.0 132.0] :animation "walk" :speed 10.0}
             (get-in result [:resolved-nodes :npc]))))

    (testing "Property conflict on shared node sets :has-conflicts? true"
      (let [base   {:boss {:id :boss :position [100 100] :hp 500}}
            ours   {:boss {:id :boss :position [200 100] :hp 500}}
            theirs {:boss {:id :boss :position [300 100] :hp 600}}
            result (merge-scene-3way base ours theirs)]
        (is (true? (:has-conflicts? result)))
        (is (= :property-conflict (get-in result [:node-results :boss :status])))
        (is (= {:id :boss :hp 600}
               (get-in result [:resolved-nodes :boss])))
        (is (= [{:field :position :base [100 100] :ours [200 100] :theirs [300 100]}]
               (get-in result [:node-results :boss :conflicts]))))))

  (testing "US2: Unilateral node additions (:added) and clean deletions (:deleted)"
    (let [base   {:old-crate {:id :old-crate :position [10 10]}
                  :old-lamp  {:id :old-lamp  :position [20 20]}
                  :old-rock  {:id :old-rock  :position [30 30]}}

          ours   {:old-rock   {:id :old-rock   :position [30 30]}
                  :new-turret {:id :new-turret :position [50 50]}}

          theirs {:old-lamp   {:id :old-lamp   :position [20 20]}
                  :new-pickup {:id :new-pickup :position [80 80]}}
          result (merge-scene-3way base ours theirs)]
      (is (false? (:has-conflicts? result)))
      (is (= :deleted (get-in result [:node-results :old-crate :status])))
      (is (= :deleted (get-in result [:node-results :old-lamp :status])))
      (is (= :deleted (get-in result [:node-results :old-rock :status])))
      (is (= :added   (get-in result [:node-results :new-turret :status])))
      (is (= :added   (get-in result [:node-results :new-pickup :status])))
      (is (= {:new-turret {:id :new-turret :position [50 50]}
              :new-pickup {:id :new-pickup :position [80 80]}}
             (:resolved-nodes result)))))

  (testing "US3: Structural conflicts (:delete-modify and :add-add)"
    (testing "Deleted in Ours, modified in Theirs -> :structural-conflict (:delete-modify)"
      (let [base   {:enemy {:id :enemy :position [10 10] :speed 50}}
            ours   {}
            theirs {:enemy {:id :enemy :position [10 10] :speed 90}}
            result (merge-scene-3way base ours theirs)]
        (is (true? (:has-conflicts? result)))
        (is (= :structural-conflict (get-in result [:node-results :enemy :status])))
        (is (not (contains? (:resolved-nodes result) :enemy)))
        (is (= [{:type   :delete-modify
                 :base   {:id :enemy :position [10 10] :speed 50}
                 :ours   nil
                 :theirs {:id :enemy :position [10 10] :speed 90}}]
               (get-in result [:node-results :enemy :conflicts])))))

    (testing "Modified in Ours, deleted in Theirs -> :structural-conflict (:delete-modify)"
      (let [base   {:enemy {:id :enemy :position [10 10] :speed 50}}
            ours   {:enemy {:id :enemy :position [99 99] :speed 50}}
            theirs {}
            result (merge-scene-3way base ours theirs)]
        (is (true? (:has-conflicts? result)))
        (is (= :structural-conflict (get-in result [:node-results :enemy :status])))
        (is (not (contains? (:resolved-nodes result) :enemy)))))

    (testing "Added in both Ours and Theirs with identical data -> clean :added"
      (let [base   {}
            ours   {:spawn {:id :spawn :position [100 100]}}
            theirs {:spawn {:id :spawn :position [100 100]}}
            result (merge-scene-3way base ours theirs)]
        (is (false? (:has-conflicts? result)))
        (is (= :added (get-in result [:node-results :spawn :status])))
        (is (= {:id :spawn :position [100 100]}
               (get-in result [:resolved-nodes :spawn])))))

    (testing "Added in both Ours and Theirs with different data -> :structural-conflict (:add-add)"
      (let [base   {}
            ours   {:spawn {:id :spawn :position [100 100]}}
            theirs {:spawn {:id :spawn :position [200 200]}}
            result (merge-scene-3way base ours theirs)]
        (is (true? (:has-conflicts? result)))
        (is (= :structural-conflict (get-in result [:node-results :spawn :status])))
        (is (= :add-add (get-in result [:node-results :spawn :conflicts 0 :type])))))))

