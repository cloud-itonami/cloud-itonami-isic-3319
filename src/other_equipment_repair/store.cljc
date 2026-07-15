(ns other-equipment-repair.store
  "SSoT for the repair-of-other-equipment-coordination actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/other_equipment_repair/store_contract_test.clj), which is the
  whole point: the actor, the Repair Governor and the audit ledger never
  know which SSoT they run on.

  `DatomicStore` uses `langchain-store.core` (ADR-2607141600) for the
  EDN-blob codec, `:db.unique/identity` schema and the seq-keyed
  event-log read/append pattern, instead of hand-rolling `enc`/`dec*`.

  This actor has FOUR coordination-proposal ops, each with its OWN
  append-only history collection and jurisdiction-scoped sequence
  counter: `:log-repair-record` (repair-record-log), `:schedule-repair-
  operation` (schedule-proposal), `:flag-safety-concern` (safety-concern-
  flag) and `:order-supplies` (supply-order-proposal). UNLIKE every
  sibling actor's `Store` that models a one-time double-actuation-guarded
  real-world event, none of these is that -- every op may recur any
  number of times for the same equipment/work-order (a piece of equipment
  gets logged repeatedly over its repair lifecycle, may be rescheduled,
  may accumulate multiple safety-concern flags, may place multiple supply
  orders) BECAUSE this actor never actually operates repair equipment/
  diagnostic tools or signs off on return-to-service -- it only ever
  proposes, logs and schedules (`:effect :propose` unconditionally, see
  `other-equipment-repair.governor` ns docstring). The equipment/work-
  order's own `:equipment-verified?` / `:safety-concern-unresolved?`
  ground-truth fields are what the Repair Governor independently
  re-checks before a `:schedule-repair-operation` / `:flag-safety-
  concern` / `:order-supplies` proposal may ever commit (see
  `other-equipment-repair.governor`).

  The ledger stays append-only on every backend: 'which equipment/work-
  order was logged, which repair operation was proposed, which safety
  concern was flagged and notified, which supply order was proposed, on
  what jurisdictional basis, approved by whom' is always a query over an
  immutable log."
  (:require [other-equipment-repair.registry :as registry]
            [langchain-store.core :as ls]
            [langchain.db :as d]))

(defprotocol Store
  (equipment [s id])
  (all-equipment [s])
  (ledger [s])
  (repair-record-log-history [s] "the append-only repair-record-log history (other-equipment-repair.registry drafts)")
  (schedule-proposal-history [s] "the append-only repair-operation schedule-proposal history")
  (safety-concern-flag-history [s] "the append-only safety-concern-flag history")
  (supply-order-proposal-history [s] "the append-only supply-order-proposal history")
  (next-repair-record-sequence [s jurisdiction])
  (next-schedule-sequence [s jurisdiction])
  (next-safety-concern-sequence [s jurisdiction])
  (next-supply-order-sequence [s jurisdiction])
  (commit-record! [s record] "apply a committed op's PROPOSAL record to the SSoT -- see ns docstring, never a real-world actuation")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-equipment [s equipment-map] "replace/seed the equipment/work-order directory (map id->equipment)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained equipment/work-order set covering the happy
  path (equipment-1, JPN), the uncovered-jurisdiction / not-verified /
  unresolved-safety-concern failure modes (equipment-2..equipment-4), a
  cross-jurisdiction (USA) happy path (equipment-5), and the honestly-
  qualitative (DEU/EU) jurisdiction that never fabricates a numeric
  lead-time (equipment-6) -- so the actor + tests run offline."
  []
  {:equipment
   {"equipment-1" {:id "equipment-1" :name "Kōji's Repair Co-op -- Upright Piano Restoration (customer intake #4471)"
                   :jurisdiction "JPN" :equipment-verified? true
                   :safety-concern-unresolved? false
                   :safety-contacts [{:name "Kōji (repair technician)" :email "koji@example.com" :phone "+819000000011"}
                                     {:name "Aiko (shop safety officer)" :email "aiko@example.com" :phone "+819000000012"}]
                   :status :ready-to-schedule}
    "equipment-2" {:id "equipment-2" :name "Atlantis Sporting Goods -- Kayak Hull Repair"
                   :jurisdiction "ATL" :equipment-verified? true
                   :safety-concern-unresolved? false
                   :safety-contacts []
                   :status :intake}
    "equipment-3" {:id "equipment-3" :name "山田家具工房 アンティーク椅子修理"
                   :jurisdiction "JPN" :equipment-verified? false
                   :safety-concern-unresolved? false
                   :safety-contacts [{:name "Yamada (repair technician)" :email "yamada@example.com" :phone "+819000000013"}]
                   :status :unverified}
    "equipment-4" {:id "equipment-4" :name "ことぶき体育器具 トレッドミル修理"
                   :jurisdiction "JPN" :equipment-verified? true
                   :safety-concern-unresolved? true
                   :safety-contacts [{:name "Watanabe (repair technician)" :email "watanabe@example.com" :phone "+819000000014"}
                                     {:name "Yamamoto (shop safety officer)" :email "yamamoto@example.com" :phone "+819000000015"}]
                   :status :concern-open}
    "equipment-5" {:id "equipment-5" :name "Riverside Community Woodshop -- Table Saw Fence Repair"
                   :jurisdiction "USA" :equipment-verified? true
                   :safety-concern-unresolved? false
                   :safety-contacts [{:name "Casey (repair technician)" :email "casey@example.com" :phone "+15550000011"}]
                   :status :ready-to-schedule}
    "equipment-6" {:id "equipment-6" :name "Alt-Reparaturwerkstatt -- Fahrradergometer-Reparatur"
                   :jurisdiction "DEU" :equipment-verified? true
                   :safety-concern-unresolved? false
                   :safety-contacts [{:name "Müller (repair technician)" :email "mueller@example.com" :phone "+4915000000011"}]
                   :status :ready-to-schedule}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- log-repair-record!
  [s equipment-id patch]
  (let [a (equipment s equipment-id)
        jurisdiction (or (:jurisdiction patch) (:jurisdiction a) "UNKNOWN")
        seq-n (next-repair-record-sequence s jurisdiction)
        result (registry/register-repair-record equipment-id jurisdiction seq-n)]
    {:result result :jurisdiction jurisdiction :equipment-patch patch}))

(defn- schedule-repair-operation!
  [s equipment-id]
  (let [a (equipment s equipment-id)
        seq-n (next-schedule-sequence s (:jurisdiction a))
        result (registry/register-schedule-proposal equipment-id (:jurisdiction a) seq-n)]
    {:result result}))

(defn- flag-safety-concern!
  [s equipment-id concern-description]
  (let [a (equipment s equipment-id)
        seq-n (next-safety-concern-sequence s (:jurisdiction a))
        result (registry/register-safety-concern-flag equipment-id (:jurisdiction a) seq-n)
        concern-number (get result "concern_number")
        doc (registry/render-safety-concern-notice a concern-number concern-description)]
    {:result (assoc-in result ["record" "document"] doc)
     :equipment-patch {:safety-concern-unresolved? true}}))

(defn- order-supplies!
  [s equipment-id]
  (let [a (equipment s equipment-id)
        seq-n (next-supply-order-sequence s (:jurisdiction a))
        result (registry/register-supply-order-proposal equipment-id (:jurisdiction a) seq-n)]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (equipment [_ id] (get-in @a [:equipment id]))
  (all-equipment [_] (sort-by :id (vals (:equipment @a))))
  (ledger [_] (:ledger @a))
  (repair-record-log-history [_] (:repair-record-log @a))
  (schedule-proposal-history [_] (:schedule-proposals @a))
  (safety-concern-flag-history [_] (:safety-concern-flags @a))
  (supply-order-proposal-history [_] (:supply-order-proposals @a))
  (next-repair-record-sequence [_ jurisdiction] (get-in @a [:repair-record-sequences jurisdiction] 0))
  (next-schedule-sequence [_ jurisdiction] (get-in @a [:schedule-sequences jurisdiction] 0))
  (next-safety-concern-sequence [_ jurisdiction] (get-in @a [:safety-concern-sequences jurisdiction] 0))
  (next-supply-order-sequence [_ jurisdiction] (get-in @a [:supply-order-sequences jurisdiction] 0))
  (commit-record! [s {:keys [op path value]}]
    (let [equipment-id (first path)]
      (case op
        :log-repair-record
        (let [{:keys [result jurisdiction equipment-patch]} (log-repair-record! s equipment-id value)]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:repair-record-sequences jurisdiction] (fnil inc 0))
                         (update-in [:equipment equipment-id] merge equipment-patch)
                         (update :repair-record-log registry/append result))))
          result)

        :schedule-repair-operation
        (let [{:keys [result]} (schedule-repair-operation! s equipment-id)
              jurisdiction (:jurisdiction (equipment s equipment-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:schedule-sequences jurisdiction] (fnil inc 0))
                         (update :schedule-proposals registry/append result))))
          result)

        :flag-safety-concern
        (let [{:keys [result equipment-patch]} (flag-safety-concern! s equipment-id (:concern-description value))
              jurisdiction (:jurisdiction (equipment s equipment-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:safety-concern-sequences jurisdiction] (fnil inc 0))
                         (update-in [:equipment equipment-id] merge equipment-patch)
                         (update :safety-concern-flags registry/append result))))
          result)

        :order-supplies
        (let [{:keys [result]} (order-supplies! s equipment-id)
              jurisdiction (:jurisdiction (equipment s equipment-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:supply-order-sequences jurisdiction] (fnil inc 0))
                         (update :supply-order-proposals registry/append result))))
          result)

        nil))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-equipment [s equipment-map] (when (seq equipment-map) (swap! a assoc :equipment equipment-map)) s))

(defn seed-db
  "A MemStore seeded with the demo equipment/work-order set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger []
                           :repair-record-sequences {} :repair-record-log []
                           :schedule-sequences {} :schedule-proposals []
                           :safety-concern-sequences {} :safety-concern-flags []
                           :supply-order-sequences {} :supply-order-proposals []))))

;; ----------------------------- DatomicStore (langchain.db + langchain-store) -----------------------------

(def ^:private equipment-spec
  "langchain-store.core field-spec for the `equipment` entity -- drives
  `map->tx`/`pull->map`/`pull-pattern` from data instead of hand-written
  triples (ADR-2607141600)."
  {:id                          {:attr :equipment/id}
   :name                        {:attr :equipment/name}
   :jurisdiction                {:attr :equipment/jurisdiction}
   :equipment-verified?         {:attr :equipment/equipment-verified? :coerce boolean}
   :safety-concern-unresolved?  {:attr :equipment/safety-concern-unresolved? :coerce boolean}
   :diagnostic-notes            {:attr :equipment/diagnostic-notes}
   :safety-contacts             {:attr :equipment/safety-contacts-edn :blob? true :default []}
   :status                      {:attr :equipment/status}})

(def ^:private equipment-pull (ls/pull-pattern equipment-spec))

(defn- equipment->tx [m] (ls/map->tx equipment-spec m))
(defn- pull->equipment [pulled] (ls/pull->map equipment-spec :id pulled))

(def ^:private schema
  (ls/identity-schema [:equipment/id
                       :ledger/seq
                       :repair-record-log/seq
                       :schedule-proposal/seq
                       :safety-concern-flag/seq
                       :supply-order-proposal/seq
                       :repair-record-sequence/jurisdiction
                       :schedule-sequence/jurisdiction
                       :safety-concern-sequence/jurisdiction
                       :supply-order-sequence/jurisdiction]))

(defrecord DatomicStore [conn]
  Store
  (equipment [_ id]
    (pull->equipment (d/pull (d/db conn) equipment-pull [:equipment/id id])))
  (all-equipment [_]
    (->> (d/q '[:find [?id ...] :where [?e :equipment/id ?id]] (d/db conn))
         (map #(pull->equipment (d/pull (d/db conn) equipment-pull [:equipment/id %])))
         (sort-by :id)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (repair-record-log-history [_] (ls/read-stream conn :repair-record-log/seq :repair-record-log/record))
  (schedule-proposal-history [_] (ls/read-stream conn :schedule-proposal/seq :schedule-proposal/record))
  (safety-concern-flag-history [_] (ls/read-stream conn :safety-concern-flag/seq :safety-concern-flag/record))
  (supply-order-proposal-history [_] (ls/read-stream conn :supply-order-proposal/seq :supply-order-proposal/record))
  (next-repair-record-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :repair-record-sequence/jurisdiction ?j] [?e :repair-record-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-schedule-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :schedule-sequence/jurisdiction ?j] [?e :schedule-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-safety-concern-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :safety-concern-sequence/jurisdiction ?j] [?e :safety-concern-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-supply-order-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :supply-order-sequence/jurisdiction ?j] [?e :supply-order-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (commit-record! [s {:keys [op path value]}]
    (let [equipment-id (first path)]
      (case op
        :log-repair-record
        (let [{:keys [result jurisdiction equipment-patch]} (log-repair-record! s equipment-id value)
              next-n (inc (next-repair-record-sequence s jurisdiction))]
          (d/transact! conn
                       [(equipment->tx (assoc equipment-patch :id equipment-id))
                        {:repair-record-sequence/jurisdiction jurisdiction :repair-record-sequence/next next-n}])
          (ls/append-blob! conn :repair-record-log/seq :repair-record-log/record
                           (count (repair-record-log-history s)) (get result "record"))
          result)

        :schedule-repair-operation
        (let [{:keys [result]} (schedule-repair-operation! s equipment-id)
              jurisdiction (:jurisdiction (equipment s equipment-id))
              next-n (inc (next-schedule-sequence s jurisdiction))]
          (d/transact! conn [{:schedule-sequence/jurisdiction jurisdiction :schedule-sequence/next next-n}])
          (ls/append-blob! conn :schedule-proposal/seq :schedule-proposal/record
                           (count (schedule-proposal-history s)) (get result "record"))
          result)

        :flag-safety-concern
        (let [{:keys [result equipment-patch]} (flag-safety-concern! s equipment-id (:concern-description value))
              jurisdiction (:jurisdiction (equipment s equipment-id))
              next-n (inc (next-safety-concern-sequence s jurisdiction))]
          (d/transact! conn
                       [(equipment->tx (assoc equipment-patch :id equipment-id))
                        {:safety-concern-sequence/jurisdiction jurisdiction :safety-concern-sequence/next next-n}])
          (ls/append-blob! conn :safety-concern-flag/seq :safety-concern-flag/record
                           (count (safety-concern-flag-history s)) (get result "record"))
          result)

        :order-supplies
        (let [{:keys [result]} (order-supplies! s equipment-id)
              jurisdiction (:jurisdiction (equipment s equipment-id))
              next-n (inc (next-supply-order-sequence s jurisdiction))]
          (d/transact! conn [{:supply-order-sequence/jurisdiction jurisdiction :supply-order-sequence/next next-n}])
          (ls/append-blob! conn :supply-order-proposal/seq :supply-order-proposal/record
                           (count (supply-order-proposal-history s)) (get result "record"))
          result)

        nil))
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-equipment [s equipment-map]
    (when (seq equipment-map) (d/transact! conn (mapv equipment->tx (vals equipment-map)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:equipment
  ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [equipment]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-equipment s equipment))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo equipment/work-order set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
