(ns other-equipment-repair.registry-test
  (:require [clojure.test :refer [deftest is]]
            [other-equipment-repair.registry :as r]))

;; ----------------------------- register-repair-record -----------------------------

(deftest repair-record-assigns-a-number
  (let [result (r/register-repair-record "equipment-1" "JPN" 7)]
    (is (= (get result "repair_record_number") "JPN-RPR-000007"))
    (is (= (get-in result ["record" "equipment_id"]) "equipment-1"))
    (is (= (get-in result ["record" "kind"]) "repair-record-log-entry"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest repair-record-validation-rules
  (is (thrown? Exception (r/register-repair-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-repair-record "equipment-1" "" 0)))
  (is (thrown? Exception (r/register-repair-record "equipment-1" "JPN" -1))))

;; ----------------------------- register-schedule-proposal -----------------------------

(deftest schedule-proposal-assigns-a-number
  (let [result (r/register-schedule-proposal "equipment-1" "JPN" 3)]
    (is (= (get result "schedule_number") "JPN-SCH-000003"))
    (is (= (get-in result ["record" "kind"]) "schedule-proposal-draft"))))

(deftest schedule-proposal-validation-rules
  (is (thrown? Exception (r/register-schedule-proposal "" "JPN" 0)))
  (is (thrown? Exception (r/register-schedule-proposal "equipment-1" "" 0)))
  (is (thrown? Exception (r/register-schedule-proposal "equipment-1" "JPN" -1))))

;; ----------------------------- register-safety-concern-flag -----------------------------

(deftest safety-concern-flag-assigns-a-number
  (let [result (r/register-safety-concern-flag "equipment-1" "JPN" 0)]
    (is (= (get result "concern_number") "JPN-SCF-000000"))
    (is (= (get-in result ["record" "kind"]) "safety-concern-flag-draft"))))

(deftest safety-concern-flag-validation-rules
  (is (thrown? Exception (r/register-safety-concern-flag "" "JPN" 0)))
  (is (thrown? Exception (r/register-safety-concern-flag "equipment-1" "JPN" -1))))

;; ----------------------------- register-supply-order-proposal -----------------------------

(deftest supply-order-proposal-assigns-a-number
  (let [result (r/register-supply-order-proposal "equipment-1" "JPN" 0)]
    (is (= (get result "order_number") "JPN-SUP-000000"))
    (is (= (get-in result ["record" "kind"]) "supply-order-proposal-draft"))))

(deftest supply-order-proposal-validation-rules
  (is (thrown? Exception (r/register-supply-order-proposal "" "JPN" 0)))
  (is (thrown? Exception (r/register-supply-order-proposal "equipment-1" "" 0)))
  (is (thrown? Exception (r/register-supply-order-proposal "equipment-1" "JPN" -1))))

;; ----------------------------- render-safety-concern-notice -----------------------------

(def sample-equipment
  {:id "equipment-1" :name "Kōji's Repair Co-op -- Upright Piano Restoration (customer intake #4471)" :jurisdiction "JPN"})

(deftest safety-concern-notice-cites-legal-basis-inline
  (let [doc (r/render-safety-concern-notice sample-equipment "JPN-SCF-000000" "鋳鉄製フレームに微細なひび割れの可能性")]
    (is (re-find #"JPN-SCF-000000" doc))
    (is (re-find #"労働安全衛生規則" doc))
    (is (re-find #"laws\.e-gov\.go\.jp" doc))
    (is (re-find #"ひび割れ" doc))
    (is (re-find #"licensed repair" doc) "honestly disclaims return-to-service sign-off authority")))

(deftest safety-concern-notice-is-honest-about-uncovered-jurisdiction
  (let [doc (r/render-safety-concern-notice (assoc sample-equipment :jurisdiction "ATL") "ATL-SCF-000000" "test")]
    (is (re-find #"NOT COVERED" doc))))

;; ----------------------------- append -----------------------------

(deftest history-is-append-only
  (let [c1 (r/register-repair-record "equipment-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-repair-record "equipment-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-RPR-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-RPR-000001" (get-in hist2 [1 "record_id"])))))
