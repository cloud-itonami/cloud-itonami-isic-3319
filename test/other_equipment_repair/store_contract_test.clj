(ns other-equipment-repair.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db + langchain-store) store satisfy the
  same contract is what makes 'swap the SSoT for Datomic / kotoba-server'
  a configuration change, not a rewrite -- see `installation.store-
  contract-test` for the same pattern on the sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [other-equipment-repair.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Kōji's Repair Co-op -- Upright Piano Restoration (customer intake #4471)" (:name (store/equipment s "equipment-1"))))
      (is (= "JPN" (:jurisdiction (store/equipment s "equipment-1"))))
      (is (true? (:equipment-verified? (store/equipment s "equipment-1"))))
      (is (false? (:safety-concern-unresolved? (store/equipment s "equipment-1"))))
      (is (false? (:equipment-verified? (store/equipment s "equipment-3"))) "equipment-3 seeded not-yet-verified")
      (is (true? (:safety-concern-unresolved? (store/equipment s "equipment-4"))) "equipment-4 seeded with an unresolved concern")
      (is (= "USA" (:jurisdiction (store/equipment s "equipment-5"))))
      (is (= "DEU" (:jurisdiction (store/equipment s "equipment-6"))))
      (is (= ["equipment-1" "equipment-2" "equipment-3" "equipment-4" "equipment-5" "equipment-6"]
             (mapv :id (store/all-equipment s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/repair-record-log-history s)))
      (is (= [] (store/schedule-proposal-history s)))
      (is (= [] (store/safety-concern-flag-history s)))
      (is (= [] (store/supply-order-proposal-history s)))
      (is (zero? (store/next-repair-record-sequence s "JPN")))
      (is (zero? (store/next-schedule-sequence s "JPN")))
      (is (zero? (store/next-safety-concern-sequence s "JPN")))
      (is (zero? (store/next-supply-order-sequence s "JPN")))
      (is (= 2 (count (:safety-contacts (store/equipment s "equipment-1"))))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "log-repair-record commits a patch and appends a repair-record-log entry"
        (store/commit-record! s {:op :log-repair-record :path ["equipment-1"]
                                 :value {:id "equipment-1" :diagnostic-notes "loose hinge"}})
        (is (= "loose hinge" (:diagnostic-notes (store/equipment s "equipment-1"))))
        (is (= "Kōji's Repair Co-op -- Upright Piano Restoration (customer intake #4471)" (:name (store/equipment s "equipment-1"))) "unrelated field preserved")
        (is (= "JPN-RPR-000000" (get (first (store/repair-record-log-history s)) "record_id")))
        (is (= 1 (store/next-repair-record-sequence s "JPN"))))
      (testing "schedule-repair-operation drafts a record and advances the sequence"
        (store/commit-record! s {:op :schedule-repair-operation :path ["equipment-1"]
                                 :value {:equipment-id "equipment-1" :window {}}})
        (is (= "JPN-SCH-000000" (get (first (store/schedule-proposal-history s)) "record_id")))
        (is (= "schedule-proposal-draft" (get (first (store/schedule-proposal-history s)) "kind")))
        (is (= 1 (store/next-schedule-sequence s "JPN"))))
      (testing "flag-safety-concern sets safety-concern-unresolved? true, drafts a record + notice document"
        (store/commit-record! s {:op :flag-safety-concern :path ["equipment-1"]
                                 :value {:equipment-id "equipment-1" :concern-description "test crack"}})
        (is (true? (:safety-concern-unresolved? (store/equipment s "equipment-1"))))
        (is (= "JPN-SCF-000000" (get (first (store/safety-concern-flag-history s)) "record_id")))
        (is (some? (get (first (store/safety-concern-flag-history s)) "document")))
        (is (= 1 (store/next-safety-concern-sequence s "JPN"))))
      (testing "log-repair-record can resolve the concern"
        (store/commit-record! s {:op :log-repair-record :path ["equipment-1"]
                                 :value {:id "equipment-1" :safety-concern-unresolved? false}})
        (is (false? (:safety-concern-unresolved? (store/equipment s "equipment-1"))))
        (is (= 2 (store/next-repair-record-sequence s "JPN"))))
      (testing "order-supplies drafts a record and advances the sequence"
        (store/commit-record! s {:op :order-supplies :path ["equipment-1"]
                                 :value {:equipment-id "equipment-1" :items ["hinge"] :cost-usd 300}})
        (is (= "JPN-SUP-000000" (get (first (store/supply-order-proposal-history s)) "record_id")))
        (is (= "supply-order-proposal-draft" (get (first (store/supply-order-proposal-history s)) "kind")))
        (is (= 1 (store/next-supply-order-sequence s "JPN"))))
      (testing "sequences are jurisdiction-scoped, not global"
        (store/commit-record! s {:op :log-repair-record :path ["equipment-5"]
                                 :value {:id "equipment-5" :diagnostic-notes "fence misaligned"}})
        (is (= "USA-RPR-000000" (get (last (store/repair-record-log-history s)) "record_id"))
            "USA gets its own independent sequence starting at 0, not continuing JPN's"))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/equipment s "nope")))
    (is (= [] (store/all-equipment s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/repair-record-log-history s)))
    (is (zero? (store/next-repair-record-sequence s "JPN")))
    (store/with-equipment s {"x" {:id "x" :name "n" :jurisdiction "JPN"
                                  :equipment-verified? true
                                  :safety-concern-unresolved? false
                                  :status :intake}})
    (is (= "n" (:name (store/equipment s "x"))))
    (is (true? (:equipment-verified? (store/equipment s "x"))))))
