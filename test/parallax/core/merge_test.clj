(ns parallax.core.merge-test
  (:require [clojure.test :refer [deftest is testing]]
            [parallax.core.merge :refer [merge-node-3way]]))

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
