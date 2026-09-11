(ns other-equipment-repair.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-safety-concern` must NEVER be a member of any
  phase's `:auto` set. `:log-repair-record`, `:schedule-repair-operation`
  and `:order-supplies` ARE auto-eligible at phase 3 -- see
  `other-equipment-repair.phase` ns docstring 'Actuation' section, and
  note this actor's `:schedule-repair-operation` auto-eligibility is a
  deliberate DIFFERENCE from `cloud-itonami-isic-3320`'s `:schedule-
  installation-operation` (which is never auto-eligible)."
  (:require [clojure.test :refer [deftest is testing]]
            [other-equipment-repair.phase :as phase]))

(deftest flag-safety-concern-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :flag-safety-concern))
        (str "phase " n " must not auto-commit :flag-safety-concern"))))

(deftest log-repair-record-schedule-repair-operation-and-order-supplies-are-auto-eligible-at-phase-3
  (is (contains? (:auto (get phase/phases 3)) :log-repair-record))
  (is (contains? (:auto (get phase/phases 3)) :schedule-repair-operation))
  (is (contains? (:auto (get phase/phases 3)) :order-supplies)))

(deftest schedule-repair-operation-is-not-auto-eligible-before-phase-3
  (doseq [n [0 1 2]]
    (is (not (contains? (:auto (get phase/phases n)) :schedule-repair-operation))
        (str "phase " n " must not auto-commit :schedule-repair-operation"))))

(deftest write-ops-is-exactly-the-closed-four-op-allowlist
  (is (= #{:log-repair-record :schedule-repair-operation :flag-safety-concern :order-supplies}
         phase/write-ops)))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-1-only-allows-log-repair-record
  (is (= #{:log-repair-record} (:writes (get phase/phases 1)))))

(deftest phase-3-auto-set-is-exactly-log-schedule-and-order
  (is (= #{:log-repair-record :schedule-repair-operation :order-supplies} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-repair-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit)))))

(deftest gate-auto-commits-log-repair-record-when-clean-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :log-repair-record} :commit)))))

(deftest gate-auto-commits-schedule-repair-operation-when-clean-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :schedule-repair-operation} :commit)))))

(deftest gate-auto-commits-order-supplies-when-clean-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :order-supplies} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 1 {:op :flag-safety-concern} :commit))))
  (is (= :hold (:disposition (phase/gate 0 {:op :log-repair-record} :commit)))))

(deftest gate-escalates-schedule-repair-operation-at-phase-2-even-when-clean
  (testing "phase 2 doesn't enable :schedule-repair-operation as a write at all -> HOLD, not escalate"
    (is (= :hold (:disposition (phase/gate 2 {:op :schedule-repair-operation} :commit))))))
