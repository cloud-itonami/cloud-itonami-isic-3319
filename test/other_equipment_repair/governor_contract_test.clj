(ns other-equipment-repair.governor-contract-test
  "The governor contract as executable tests -- the repair-of-other-
  equipment-coordination analog of `installation.governor-contract-
  test`. The single invariant under test:

    Repair Advisor never schedules a repair operation, files a safety-
    concern flag or a supply order the Repair Governor would reject;
    `:flag-safety-concern` NEVER auto-commits at any phase; `:log-repair-
    record` (no direct capital/safety risk), `:schedule-repair-operation`
    (governor-clean) and `:order-supplies` (governor-clean AND below the
    cost threshold) MAY auto-commit when clean; and every decision
    (commit OR hold) leaves exactly one ledger fact. Every committed
    record's `:effect` is `:propose` -- this actor never performs a
    real-world actuation."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [other-equipment-repair.store :as store]
            [other-equipment-repair.operation :as op]
            [other-equipment-repair.governor :as governor]
            [other-equipment-repair.phase :as phase]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :repair-technician :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

;; ----------------------------- :log-repair-record -----------------------------

(deftest clean-log-repair-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-repair-record :subject "equipment-1" :patch {:id "equipment-1" :diagnostic-notes "loose hinge"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "loose hinge" (:diagnostic-notes (store/equipment db "equipment-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= "JPN-RPR-000000" (get (first (store/repair-record-log-history db)) "record_id")))))

(deftest log-repair-record-can-resolve-a-safety-concern
  (let [[db actor] (fresh)]
    (exec-op actor "t1b" {:op :log-repair-record :subject "equipment-4" :patch {:id "equipment-4" :safety-concern-unresolved? false}} operator)
    (is (false? (:safety-concern-unresolved? (store/equipment db "equipment-4"))))))

;; ----------------------------- :schedule-repair-operation -----------------------------

(deftest clean-schedule-repair-operation-auto-commits
  (testing "equipment-1 is fully clean (verified, no unresolved concern) -- auto-commits at phase 3, UNLIKE cloud-itonami-isic-3320's schedule op"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-repair-operation :subject "equipment-1" :window {}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= "JPN-SCH-000000" (get (first (store/schedule-proposal-history db)) "record_id")))
      (is (= 1 (count (store/schedule-proposal-history db)))))))

(deftest fabricated-jurisdiction-is-held
  (testing "equipment-2 (ATL, no spec-basis in other-equipment-repair.facts) -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :schedule-repair-operation :subject "equipment-2" :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-legal-basis} (-> (store/ledger db) first :basis)))
      (is (empty? (store/schedule-proposal-history db)) "no schedule proposal recorded"))))

(deftest not-independently-verified-equipment-is-held
  (testing "equipment-3 has equipment-verified? false -> HARD hold, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :schedule-repair-operation :subject "equipment-3" :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:equipment-not-verified} (-> (store/ledger db) first :basis)))
      (is (empty? (store/schedule-proposal-history db))))))

(deftest unresolved-safety-concern-is-held
  (testing "equipment-4 has safety-concern-unresolved? true on file -> HARD hold, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :schedule-repair-operation :subject "equipment-4" :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unresolved-safety-concern} (-> (store/ledger db) first :basis)))
      (is (empty? (store/schedule-proposal-history db))))))

(deftest qualitative-jurisdiction-never-fabricates-a-numeric-hold-and-still-auto-commits-when-clean
  (testing "equipment-6 (DEU/EU, qualitative) -- no numeric bright line, but governor-clean so it still auto-commits"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :schedule-repair-operation :subject "equipment-6" :window {}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= "DEU-SCH-000000" (get (first (store/schedule-proposal-history db)) "record_id"))))))

(deftest usa-cross-jurisdiction-happy-path-also-auto-commits
  (testing "equipment-5 (USA, honestly qualitative) -- governor-clean, auto-commits"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :schedule-repair-operation :subject "equipment-5" :window {}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= "USA-SCH-000000" (get (first (store/schedule-proposal-history db)) "record_id"))))))

;; ----------------------------- :flag-safety-concern -----------------------------

(deftest flag-safety-concern-always-escalates-even-when-clean
  (testing "equipment-1 is fully clean -- :flag-safety-concern STILL always interrupts, unconditionally"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t10" {:op :flag-safety-concern :subject "equipment-1"
                                   :concern-type :equipment-hazard
                                   :concern-description "cracked cast-iron frame observed"} operator)]
      (is (= :interrupted (:status r1)))
      (let [r2 (approve! actor "t10")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:safety-concern-unresolved? (store/equipment db "equipment-1"))))
        (is (some? (get (first (store/safety-concern-flag-history db)) "document")) "rendered notice document present")))))

(deftest flag-safety-concern-triggers-notification-only-after-approval
  (let [[_db actor] (fresh)
        r1 (exec-op actor "t11" {:op :flag-safety-concern :subject "equipment-1"
                                 :concern-type :certification-lapse :concern-description "recall notice unresolved"} operator)]
    (is (nil? (:notify-result (:state r1))) "no notify before human approval")
    (let [r2 (approve! actor "t11")
          notify-result (:notify-result (:state r2))]
      (is (= 2 (count notify-result)) "one result entry per equipment-1 safety-contact")
      (is (every? #(= :sent (get-in % [:mail :status])) notify-result))
      (is (every? #(= :sent (get-in % [:phone :status])) notify-result)))))

;; ----------------------------- :order-supplies -----------------------------

(deftest order-supplies-below-threshold-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t12" {:op :order-supplies :subject "equipment-1"
                                  :items ["hinge"] :cost-usd 300 :vendor "Local Repair Supply Co."} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/supply-order-proposal-history db))))))

(deftest order-supplies-above-threshold-escalates
  (let [[db actor] (fresh)
        r1 (exec-op actor "t13" {:op :order-supplies :subject "equipment-1"
                                 :items ["replacement-soundboard"] :cost-usd 8000 :vendor "Heirloom Restoration Supply"} operator)]
    (is (= :interrupted (:status r1)) "above cost threshold -- always a human's call")
    (let [r2 (approve! actor "t13")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count (store/supply-order-proposal-history db)))))))

;; ----------------------------- closed op-allowlist -----------------------------

(deftest op-outside-the-closed-allowlist-is-held
  (testing "an op outside {:log-repair-record :schedule-repair-operation :flag-safety-concern :order-supplies} -> HARD hold, never reaches a human, regardless of what the advisor's default branch returns"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :direct-equipment-command :subject "equipment-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unknown-op} (-> (store/ledger db) first :basis))))))

;; ----------------------------- effect / forbidden-action-class (direct governor/check) -----------------------------
;; The mock advisor never produces these -- they exercise the governor's
;; defense-in-depth against a hypothetically compromised/malfunctioning
;; advisor, so they are tested directly against `governor/check` rather
;; than through the full actor (which can only ever see what the mock
;; advisor actually proposes).

(deftest effect-not-propose-is-a-permanent-hard-violation
  (let [db (store/seed-db)
        request {:op :log-repair-record :subject "equipment-1"}
        proposal {:summary "s" :rationale "r" :cites ["x"] :effect :direct-write
                  :value {:id "equipment-1"} :stake nil :confidence 0.99}
        verdict (governor/check request {} proposal db)]
    (is (:hard? verdict))
    (is (some #{:effect-not-propose} (map :rule (:violations verdict))))
    (is (not (:ok? verdict)))))

(deftest forbidden-action-class-markers-are-permanent-hard-violations
  (doseq [marker [:repair-equipment-control? :diagnostic-tool-control? :direct-actuation? :return-to-service-sign-off?]]
    (testing marker
      (let [db (store/seed-db)
            request {:op :schedule-repair-operation :subject "equipment-1"}
            proposal {:summary "s" :rationale "r" :cites ["x"] :effect :propose
                      :value {marker true} :stake :schedule-repair-operation :confidence 0.99}
            verdict (governor/check request {} proposal db)]
        (is (:hard? verdict))
        (is (some #{:forbidden-action-class} (map :rule (:violations verdict))))))))

;; ----------------------------- ledger discipline -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-repair-record :subject "equipment-1" :patch {:id "equipment-1" :diagnostic-notes "x"}} operator)
      (exec-op actor "b" {:op :schedule-repair-operation :subject "equipment-2" :window {}} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

(deftest approver-rejection-is-held-not-committed
  (let [[db actor] (fresh)
        r1 (exec-op actor "t15" {:op :flag-safety-concern :subject "equipment-1"
                                 :concern-type :equipment-hazard :concern-description "test"} operator)]
    (is (= :interrupted (:status r1)))
    (let [r2 (g/run* actor {:approval {:status :rejected :by "op-1"}} {:thread-id "t15" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/safety-concern-flag-history db))))))

;; ----------------------------- phase structural invariants (belt-and-suspenders) -----------------------------

(deftest flag-never-auto-at-any-phase
  (testing "structural invariant: :flag-safety-concern is never auto-eligible, even when clean, at any phase"
    (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit)))
        ":flag-safety-concern must escalate to a human even when the governor is clean at phase 3")))

(deftest schedule-repair-operation-is-auto-eligible-at-phase-3-unlike-3320
  (testing "structural invariant: this actor's narrower escalation surface auto-commits a governor-clean schedule at phase 3"
    (is (= :commit (:disposition (phase/gate 3 {:op :schedule-repair-operation} :commit))))))
