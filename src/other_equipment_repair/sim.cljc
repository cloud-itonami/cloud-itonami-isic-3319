(ns other-equipment-repair.sim
  "Demo driver -- `clojure -M:dev:run`. Walks equipment/work-orders
  through a full coordination episode, exercising every governor check:
  log a repair record (auto-commits) -> propose a repair-operation
  schedule (governor-clean, equipment-verified, AUTO-COMMITS at phase 3)
  -> flag a safety concern (ALWAYS escalates, human approves, safety-
  concern notice actually 'sent' via the mock notifier) -> log the
  concern's resolution (auto-commits) -> re-propose the schedule now that
  the concern is resolved (AUTO-COMMITS) -> order supplies below the cost
  threshold (AUTO-COMMITS) -> order supplies above the cost threshold
  (escalates, approved) -> then FOUR HARD holds that never reach a human
  at all: an uncovered jurisdiction, a not-independently-verified
  equipment record, an unresolved safety concern, and an op outside the
  closed four-op allowlist -- then a cross-jurisdiction (USA, honestly
  qualitative) and a DEU/EU (honestly qualitative, never fabricates a
  numeric lead-time) schedule walkthrough. Finally prints the audit
  ledger + every coordination-artifact history."
  (:require [langgraph.graph :as g]
            [other-equipment-repair.store :as store]
            [other-equipment-repair.notify :as notify]
            [other-equipment-repair.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :repair-technician :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        notifier (notify/mock-notifier)
        actor (op/build db {:notifier notifier})]
    (println "== log-repair-record equipment-1 (diagnostic finding, no ground-truth booleans touched) (AUTO-COMMITS) ==")
    (println (exec! actor "t1" {:op :log-repair-record :subject "equipment-1"
                                :patch {:id "equipment-1" :diagnostic-notes "hammer action loose on C4"}} operator))

    (println "== schedule-repair-operation equipment-1 (clean, equipment-verified -- AUTO-COMMITS at phase 3) ==")
    (println (exec! actor "t2" {:op :schedule-repair-operation :subject "equipment-1"
                                :window {:proposed-start-date "2026-08-01" :proposed-end-date "2026-08-03"}
                                :notes "ハンマーアクション調整、鍵盤の再整音"} operator))

    (println "== flag-safety-concern equipment-1 (ALWAYS escalates -- human approves; safety-concern notice actually sent via mock notifier) ==")
    (let [r (exec! actor "t3" {:op :flag-safety-concern :subject "equipment-1"
                               :concern-type :equipment-hazard
                               :concern-description "鋳鉄製フレームに微細なひび割れの可能性、詳細点検が必要。"} operator)]
      (println r)
      (println (approve! actor "t3")))
    (println "-- sent log --")
    (println (notify/sent-log notifier))

    (println "== log-repair-record equipment-1: concern resolved after inspection (AUTO-COMMITS) ==")
    (println (exec! actor "t4" {:op :log-repair-record :subject "equipment-1"
                                :patch {:id "equipment-1" :safety-concern-unresolved? false}} operator))

    (println "== schedule-repair-operation equipment-1 AGAIN, now that the concern is resolved (AUTO-COMMITS) ==")
    (println (exec! actor "t5" {:op :schedule-repair-operation :subject "equipment-1"
                                :window {:proposed-start-date "2026-08-05" :proposed-end-date "2026-08-06"}
                                :notes "フレーム点検後の再整音・最終テスト"} operator))

    (println "== order-supplies equipment-1, cost below threshold (AUTO-COMMITS at phase 3) ==")
    (println (exec! actor "t6" {:op :order-supplies :subject "equipment-1"
                                :items ["piano-hammer-felt-set" "tuning-pin-set"]
                                :cost-usd 800 :vendor "Local Piano Parts Co."} operator))

    (println "== order-supplies equipment-1, cost ABOVE threshold (escalates -- human approves) ==")
    (let [r (exec! actor "t7" {:op :order-supplies :subject "equipment-1"
                               :items ["replacement-soundboard"] :cost-usd 8000 :vendor "Heirloom Piano Restoration Supply"} operator)]
      (println r)
      (println (approve! actor "t7")))

    (println "== schedule-repair-operation equipment-2 (ATL, no spec-basis -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :schedule-repair-operation :subject "equipment-2" :window {}} operator))

    (println "== schedule-repair-operation equipment-3 (equipment-verified? false -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :schedule-repair-operation :subject "equipment-3" :window {}} operator))

    (println "== schedule-repair-operation equipment-4 (safety-concern-unresolved? true on file -> HARD hold) ==")
    (println (exec! actor "t10" {:op :schedule-repair-operation :subject "equipment-4" :window {}} operator))

    (println "== :direct-equipment-command equipment-1 (outside the closed 4-op allowlist -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t11" {:op :direct-equipment-command :subject "equipment-1"} operator))

    (println "== USA (honestly qualitative -- no fixed federal numeric lead-time) -- schedule-repair-operation equipment-5 (AUTO-COMMITS, governor-clean) ==")
    (println (exec! actor "t12" {:op :schedule-repair-operation :subject "equipment-5"
                                 :window {:proposed-start-date "2026-09-01" :proposed-end-date "2026-09-02"}} operator))

    (println "== DEU/EU (qualitative -- no fixed numeric lead-time, never fabricated) -- schedule-repair-operation equipment-6 (AUTO-COMMITS, governor-clean) ==")
    (println (exec! actor "t13" {:op :schedule-repair-operation :subject "equipment-6"
                                 :window {:proposed-start-date "2026-09-10" :proposed-end-date "2026-09-11"}} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== repair-record-log ==")
    (doseq [r (store/repair-record-log-history db)] (println r))

    (println "== schedule-proposal history ==")
    (doseq [r (store/schedule-proposal-history db)] (println r))

    (println "== safety-concern-flag history ==")
    (doseq [r (store/safety-concern-flag-history db)] (println (get r "document")))

    (println "== supply-order-proposal history ==")
    (doseq [r (store/supply-order-proposal-history db)] (println r))))
