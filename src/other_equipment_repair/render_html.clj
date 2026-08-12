(ns other-equipment-repair.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had NO
  demo page and no generator at all. This namespace drives the REAL
  actor stack -- `other-equipment-repair.operation` (a compiled langgraph
  StateGraph) -> `other-equipment-repair.governor` ->
  `other-equipment-repair.store` -- through a scenario adapted from this
  repo's own `other-equipment-repair.sim` demo driver (`clojure
  -M:dev:run`, run BEFORE this file was written to confirm it produces a
  sensible ledger against the real seeded equipment ids
  `equipment-1`..`equipment-6`), then renders the resulting store, audit
  ledger and coordination-artifact registers.

  NOTHING on the page is hand-typed. Every equipment id, name,
  jurisdiction, sequence number, disposition, hold rule and hold detail
  string is read back out of the real store/ledger the run produced. The
  action-gate table, the phase ladder and the regulatory spec-basis
  coverage table are derived from the live `other-equipment-repair.
  governor` / `.phase` / `.facts` vars rather than described in prose, so
  they cannot drift away from the code. The scenario INPUTS (which op
  against which equipment id, at which rollout phase) are of course
  authored -- that is what a scenario is -- but no OUTPUT is.

  Where the page cannot honestly show something, it says so instead of
  inventing it: `approver-attribution` re-checks, at render time, which
  registers actually retained the human approver's id, and the page
  prints that per-register result. See `approver-attribution` for what
  this repo's store actually does -- it is NOT the fleet's usual
  all-or-nothing behaviour, and it was MEASURED, not assumed.

  ## Why this scenario

  It walks the seeded equipment through a full coordination episode (log
  a repair record -> propose a repair window -> flag a safety concern ->
  log its resolution -> re-propose the window -> order supplies below and
  then above the cost threshold), adds a phase-2 run to exercise the
  rollout gate's `:phase-approval` escalation, adds two cross-
  jurisdiction windows (USA, DEU/EU -- both honestly qualitative; this
  actor's `facts` catalog has NO `:quantitative` jurisdiction and never
  fabricates a numeric lead-time), flags a concern in an UNCOVERED
  jurisdiction (ATL, whose notice document honestly prints `NOT COVERED`
  and whose contact roster is really empty), and then exercises ALL SIX
  of the Repair Governor's HARD checks, each of which HOLDS without ever
  reaching a human:

    1. `:unknown-op`                -- an op outside the closed four-op allowlist
    2. `:effect-not-propose`        -- a deliberately ROGUE advisor injected over
                                       the SAME store, returning `:effect :actuate`.
                                       This check is unreachable from the shipped
                                       mock advisor (which always says `:propose`),
                                       so the only honest way to demonstrate the
                                       governor's defense-in-depth is to inject a
                                       malfunctioning advisor through the seam
                                       `operation/build` already exposes.
    3. `:forbidden-action-class`    -- a patch carrying this actor's
                                       `:repair-equipment-control?` marker
    4. `:equipment-not-verified`    -- `equipment-3`, `:equipment-verified? false`
    5. `:no-legal-basis`            -- `equipment-2`, jurisdiction ATL, absent
                                       from `other-equipment-repair.facts`
    6. `:unresolved-safety-concern` -- `equipment-4`, concern open on file

  One hold deliberately carries TWO rules at once (`equipment-2`'s
  schedule proposal, after its own safety concern was flagged and
  approved: ATL has no legal basis AND the concern is now open), because
  a governor that can only ever report one violation at a time is a
  weaker claim than one that reports the full set.

  ## Determinism

  Every collaborator in the path is pure or deterministic: the mock
  advisor is a `case` over the request, the registry's reference numbers
  are jurisdiction-scoped zero-padded sequences, the mock notifier writes
  to an atom, and no code in `src/` reads a clock or a RNG. Every set or
  map iterated for the page (`governor/closed-op-allowlist`,
  `phase/phases`, `facts/catalog`) is explicitly sorted here rather than
  iterated in hash order. The page therefore contains NO timestamp and
  NO generated id, and two consecutive runs are byte-identical (verified
  with `cmp`).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [other-equipment-repair.advisor :as advisor]
            [other-equipment-repair.facts :as facts]
            [other-equipment-repair.governor :as governor]
            [other-equipment-repair.notify :as notify]
            [other-equipment-repair.operation :as op]
            [other-equipment-repair.phase :as phase]
            [other-equipment-repair.store :as store]))

(def ^:private operator
  "The same operator context this repo's own `sim` driver uses."
  {:actor-id "op-1" :actor-role :repair-technician :phase 3})

(def ^:private operator-phase-2
  "The SAME operator, earlier in the rollout. At phase 2
  `:log-repair-record` is a permitted write but is NOT auto-eligible, so
  the phase gate escalates it with `:phase-approval` even though the
  governor is completely clean -- the one path on this page where the
  rollout ladder, and not the governor, is what puts a human in the
  loop."
  {:actor-id "op-1" :actor-role :repair-technician :phase 2})

(def ^:private approver
  "The approver id this repo's own `sim` driver resumes with."
  "op-1")

;; ----------------------------- driving the REAL actor -----------------------------

(defn- record!
  "Append one finished graph run to the ordered run log. `result` is the
  raw `langgraph.graph/run*` return value -- everything rendered from it
  is real actor output."
  [runs tid request context result]
  (swap! runs conj {:tid tid
                    :request request
                    :phase (:phase context)
                    :audit (vec (get-in result [:state :audit]))
                    :disposition (get-in result [:state :disposition])})
  result)

(defn- exec!
  "One operation, no human in the loop (auto-commit or HARD hold)."
  ([runs actor tid request] (exec! runs actor tid request operator))
  ([runs actor tid request context]
   (record! runs tid request context
            (g/run* actor {:request request :context context} {:thread-id tid}))))

(defn- run-approve!
  "One operation that the phase gate / governor escalates, then resumed
  by a human approval. The resumed result carries the FULL accumulated
  audit (`:audit`'s reducer is `into`, restored from the checkpointer),
  so only the resumed result is recorded."
  ([runs actor tid request] (run-approve! runs actor tid request operator))
  ([runs actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})
   (record! runs tid request context
            (g/run* actor {:approval {:status :approved :by approver}}
                    {:thread-id tid :resume? true}))))

(def ^:private rogue-advisor
  "A deliberately MALFUNCTIONING advisor -- it claims `:effect :actuate`,
  which the shipped mock advisor can never emit. Injected over the SAME
  store to prove HARD check 2 (`:effect-not-propose`) fires: a
  compromised or broken advisor gains nothing by trying. See ns
  docstring."
  (reify advisor/Advisor
    (-advise [_ _ request]
      {:summary    (str (:subject request) ": ROGUE advisor claiming a real-world actuation")
       :rationale  "この助言者は :effect :propose 以外を返す（本来あり得ない）"
       :cites      [:id]
       :effect     :actuate
       :value      {:id (:subject request)}
       :stake      nil
       :confidence 0.99})))

(defn run-demo!
  "Runs a fresh seeded store through the scenario described in the ns
  docstring. Returns `{:db :notifier :runs}` -- `:runs` is the ordered
  log of real graph results, `:db` the real store the actor wrote."
  []
  (let [db       (store/seed-db)
        notifier (notify/mock-notifier)
        actor    (op/build db {:notifier notifier})
        ;; same store, same notifier -- only the advisor is swapped
        broken   (op/build db {:notifier notifier :advisor rogue-advisor})
        runs     (atom [])]

    ;; --- clean coordination episode on equipment-1 (JPN, upright piano restoration) ---
    (exec! runs actor "t01"
           {:op :log-repair-record :subject "equipment-1"
            :patch {:id "equipment-1" :diagnostic-notes "hammer action loose on C4"}})
    (exec! runs actor "t02"
           {:op :schedule-repair-operation :subject "equipment-1"
            :window {:proposed-start-date "2026-08-01" :proposed-end-date "2026-08-03"}
            :notes "ハンマーアクション調整、鍵盤の再整音"})
    ;; ALWAYS escalates at every phase; the notice is really "sent" via the
    ;; mock notifier on commit, after the human approves.
    (run-approve! runs actor "t03"
                  {:op :flag-safety-concern :subject "equipment-1"
                   :concern-type :equipment-hazard
                   :concern-description "鋳鉄製フレームに微細なひび割れの可能性、詳細点検が必要。"})
    (exec! runs actor "t04"
           {:op :log-repair-record :subject "equipment-1"
            :patch {:id "equipment-1" :safety-concern-unresolved? false}})
    (exec! runs actor "t05"
           {:op :schedule-repair-operation :subject "equipment-1"
            :window {:proposed-start-date "2026-08-05" :proposed-end-date "2026-08-06"}
            :notes "フレーム点検後の再整音・最終テスト"})
    (exec! runs actor "t06"
           {:op :order-supplies :subject "equipment-1"
            :items ["piano-hammer-felt-set" "tuning-pin-set"]
            :cost-usd 800 :vendor "Local Piano Parts Co."})
    ;; above `governor/supply-order-cost-threshold-usd` -> soft, cost-scoped escalation
    (run-approve! runs actor "t07"
                  {:op :order-supplies :subject "equipment-1"
                   :items ["replacement-soundboard"]
                   :cost-usd 8000 :vendor "Heirloom Piano Restoration Supply"})

    ;; --- the rollout ladder, not the governor, puts a human in the loop ---
    (run-approve! runs actor "t08"
                  {:op :log-repair-record :subject "equipment-5"
                   :patch {:id "equipment-5" :diagnostic-notes "rip fence locks out of parallel with the blade"}}
                  operator-phase-2)

    ;; --- cross-jurisdiction clean coordination (both honestly qualitative) ---
    (exec! runs actor "t09"
           {:op :schedule-repair-operation :subject "equipment-5"
            :window {:proposed-start-date "2026-09-01" :proposed-end-date "2026-09-02"}})
    (exec! runs actor "t10"
           {:op :schedule-repair-operation :subject "equipment-6"
            :window {:proposed-start-date "2026-09-10" :proposed-end-date "2026-09-11"}})

    ;; --- an uncovered jurisdiction: the notice really prints NOT COVERED,
    ;;     and equipment-2's contact roster really is empty, so nothing is sent ---
    (run-approve! runs actor "t11"
                  {:op :flag-safety-concern :subject "equipment-2"
                   :concern-type :incomplete-repair
                   :concern-description "船倉側の補修樹脂が硬化不良の可能性、再確認が必要。"})

    ;; --- all six HARD checks, none of which ever reaches a human ---
    ;; t12 carries TWO rules at once: ATL has no legal basis AND t11 just
    ;; opened a safety concern on equipment-2.
    (exec! runs actor "t12" {:op :schedule-repair-operation :subject "equipment-2" :window {}})
    (exec! runs actor "t13" {:op :schedule-repair-operation :subject "equipment-3" :window {}})
    (exec! runs actor "t14" {:op :schedule-repair-operation :subject "equipment-4" :window {}})
    (exec! runs actor "t15" {:op :direct-equipment-command :subject "equipment-1"})
    (exec! runs actor "t16" {:op :log-repair-record :subject "equipment-1"
                             :patch {:id "equipment-1" :repair-equipment-control? true}})
    (exec! runs broken "t17" {:op :log-repair-record :subject "equipment-1"
                              :patch {:id "equipment-1"}})

    {:db db :notifier notifier :runs @runs}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- n-cell [v] (str "<span class=\"num\">" (esc v) "</span>"))

(defn- dash [] "<span class=\"muted\">&mdash;</span>")

(defn- bool-cell [v]
  (if (true? v)
    "<span class=\"ok\">yes</span>"
    "<span class=\"muted\">no</span>"))

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- op-codes
  "A deterministic, sorted `<code>` list of an op set."
  [ops]
  (if (seq ops)
    (str/join " " (map #(code (kw-str %)) (sort-by kw-str ops)))
    "<span class=\"muted\">none</span>"))

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- derived: approver attribution -----------------------------

(def ^:private approver-key-names
  "Key names that would constitute retaining an approver's identity."
  #{"approved-by" "approved_by" "approver" "approved_by_id" "approver-id"})

(defn- deep-key-names
  "Every key name appearing anywhere in a nested structure, as strings.
  Used to ask the SSoT what it actually holds instead of assuming."
  [x]
  (cond
    (map? x) (into (into #{} (map kw-str) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(defn- retains-approver?
  "Does any record in this collection carry an approver key?"
  [coll]
  (boolean (some approver-key-names (deep-key-names coll))))

(defn- approver-attribution
  "DERIVED, per-register disclosure about where the human approver's id
  actually lives after a `:request-approval` handoff.

  This repo's behaviour was MEASURED, not assumed, and it is NOT the
  fleet's usual all-or-nothing shape -- it is OP-DEPENDENT:

    - `operation`'s `:request-approval` node attaches the approver at
      `[:value :approved-by]` on the commit record.
    - `store/commit-record!`'s `:log-repair-record` branch passes the
      WHOLE `value` through as the equipment patch and `merge`s it into
      the equipment/work-order entity. So for that op -- and ONLY that
      op -- the approver really is retained in the SSoT, on the
      EQUIPMENT entity (not on the repair-record artifact, which
      `registry/register-repair-record` builds from scratch with a fixed
      five-key shape).
    - the `:schedule-repair-operation`, `:flag-safety-concern` and
      `:order-supplies` branches never read `:approved-by` back out of
      `value`; `:flag-safety-concern` narrows the patch to
      `{:safety-concern-unresolved? true}`. So for those three ops the
      approver is dropped.
    - the `:commit` node appends only its `:committed` fact to the
      ledger, never the `:approval-granted` fact that carries `:by`, so
      the ledger never holds an approver for ANY op.

  Rather than assert that in prose (which would silently go stale the
  day the store is changed), this re-checks the real store at render
  time and returns the per-register result for the page to render from."
  [db runs]
  {:approvers (vec (sort (into #{} (keep #(:by (fact-of (:audit %) :approval-granted))) runs)))
   :registers [{:label "equipment / work-order entities"
                :detail "<code>store/all-equipment</code> &mdash; merged by the <code>:log-repair-record</code> branch"
                :retained? (retains-approver? (store/all-equipment db))}
               {:label "repair-record-log artifacts"
                :detail "<code>store/repair-record-log-history</code>"
                :retained? (retains-approver? (store/repair-record-log-history db))}
               {:label "schedule-proposal artifacts"
                :detail "<code>store/schedule-proposal-history</code>"
                :retained? (retains-approver? (store/schedule-proposal-history db))}
               {:label "safety-concern-flag artifacts"
                :detail "<code>store/safety-concern-flag-history</code>"
                :retained? (retains-approver? (store/safety-concern-flag-history db))}
               {:label "supply-order-proposal artifacts"
                :detail "<code>store/supply-order-proposal-history</code>"
                :retained? (retains-approver? (store/supply-order-proposal-history db))}
               {:label "audit ledger facts"
                :detail "<code>store/ledger</code> &mdash; scanned for an <code>:approval-granted</code> fact"
                :retained? (boolean (some #(= :approval-granted (:t %)) (store/ledger db)))}]})

(defn- attribution-section
  "Renders the approver-attribution disclosure from the DERIVED facts, so
  the claim tracks the code."
  [{:keys [approvers registers]}]
  (let [retained (filterv :retained? registers)
        dropped  (filterv (complement :retained?) registers)]
    (str "  <section class=\"card\">\n"
         "    <h2>Approver attribution &mdash; which registers actually kept it</h2>\n"
         "    <p class=\"muted\">Re-checked against the real store at render time: every register is "
         "scanned for an approver key (<code>approved-by</code> / <code>approver</code> / …) and the "
         "ledger for an <code>:approval-granted</code> fact. This disclosure is DERIVED, so it cannot "
         "drift away from the code &mdash; if the store is changed, this table changes with it.</p>\n"
         "    <table>\n"
         "      <thead><tr><th>Register</th><th>Read through</th><th>Retains approver?</th></tr></thead>\n"
         "      <tbody>\n"
         (rows (for [{:keys [label detail retained?]} registers]
                 (row (esc label)
                      detail
                      (if retained?
                        "<span class=\"ok\">yes</span>"
                        "<span class=\"critical\">no</span>"))))
         "\n      </tbody>\n    </table>\n"
         "    <p>"
         (cond
           (empty? approvers)
           "This run produced no human approval, so there is no approver to attribute."

           (empty? retained)
           (str "<strong>The approver is not retained anywhere in the SSoT.</strong> "
                "Every &ldquo;approved by&rdquo; string on this page is joined back from the run&rsquo;s "
                "own <code>:approval-granted</code> audit fact &mdash; <em>audit only, not retained in "
                "any record</em>. A reader can therefore still tell &ldquo;nobody approved&rdquo; from "
                "&ldquo;the store did not keep it&rdquo;.")

           (empty? dropped)
           "The approver is retained by every register scanned."

           :else
           (str "<strong>Retention here is OP-DEPENDENT, and this was measured rather than assumed.</strong> "
                "<code>operation</code>&rsquo;s <code>:request-approval</code> node attaches the approver at "
                "<code>[:value :approved-by]</code>. <code>store/commit-record!</code>&rsquo;s "
                "<code>:log-repair-record</code> branch passes the WHOLE <code>value</code> through as the "
                "equipment patch and <code>merge</code>s it into the equipment entity, so an approved "
                "<code>:log-repair-record</code> really does leave the approver in the SSoT &mdash; visible "
                "as <code>approved-by</code> in the directory above. The "
                "<code>:schedule-repair-operation</code>, <code>:flag-safety-concern</code> and "
                "<code>:order-supplies</code> branches never read <code>:approved-by</code> back out of "
                "<code>value</code> (<code>:flag-safety-concern</code> narrows the patch to "
                "<code>{:safety-concern-unresolved? true}</code>), and the coordination artifacts "
                "themselves are rebuilt from scratch by <code>other-equipment-repair.registry</code> with a "
                "fixed five-key shape, so they carry no approver at all. The <code>:commit</code> node "
                "likewise appends only its <code>:committed</code> fact to the ledger, never the "
                "<code>:approval-granted</code> fact that carries <code>:by</code>. "
                "For those registers the &ldquo;approved by&rdquo; text on this page is "
                "<em>audit only &mdash; not retained in the record</em>, joined back from the run&rsquo;s own "
                "audit trail, which really does carry it. This page states the split plainly rather than "
                "printing an approver as though every register held one."))
         "</p>\n"
         "  </section>\n")))

;; ----------------------------- derived: outcomes -----------------------------

(defn- holds
  "The HARD `:governor-hold` facts the run actually wrote to the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- outcome
  "Classify one real run from its own audit trail. Never from a literal."
  [{:keys [audit disposition]}]
  (let [hold (fact-of audit :governor-hold)]
    (cond
      hold {:kind :hard-hold :violations (:violations hold)}

      (fact-of audit :approval-granted)
      {:kind :approved
       :reason (:reason (fact-of audit :approval-requested))
       :by (:by (fact-of audit :approval-granted))}

      (fact-of audit :approval-requested)
      {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}

      (= :commit disposition) {:kind :auto-commit}
      :else {:kind :other})))

(defn- outcome-cell [o]
  (case (:kind o)
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; "
                    (esc (str/join ", " (map (comp kw-str :rule) (:violations o))))
                    "</span>")
    :approved (str "<span class=\"ok\">escalated (" (esc (kw-str (:reason o)))
                   ") &rarr; approved by " (esc (:by o)) "</span>")
    :awaiting (str "<span class=\"warn\">awaiting human approval &middot; "
                   (esc (kw-str (:reason o))) "</span>")
    :auto-commit "<span class=\"ok\">auto-commit (governor-clean)</span>"
    "<span class=\"muted\">in progress</span>"))

(defn- detail-cell [o]
  (case (:kind o)
    :hard-hold (esc (str/join " / " (map :detail (:violations o))))
    :approved "<span class=\"muted\">human in the loop before commit</span>"
    :awaiting "<span class=\"muted\">paused at :request-approval</span>"
    (dash)))

;; ----------------------------- sections (all derived) -----------------------------

(defn- equipment-rows [db]
  (for [{:keys [id name jurisdiction equipment-verified? safety-concern-unresolved?
                status diagnostic-notes safety-contacts approved-by]}
        (store/all-equipment db)]
    (row (code id)
         (esc name)
         (esc jurisdiction)
         (bool-cell equipment-verified?)
         (if (true? safety-concern-unresolved?)
           "<span class=\"critical\">open</span>"
           "<span class=\"ok\">none open</span>")
         (esc (kw-str status))
         (if (seq diagnostic-notes) (esc diagnostic-notes) (dash))
         (n-cell (count safety-contacts))
         (if approved-by (code approved-by) (dash)))))

(defn- run-rows [db runs]
  (for [{:keys [tid request phase] :as r} runs
        :let [o (outcome r)
              a (store/equipment db (:subject request))]]
    (row (code tid)
         (n-cell phase)
         (code (kw-str (:op request)))
         (code (:subject request))
         (esc (or (:jurisdiction a) "n/a"))
         (outcome-cell o)
         (detail-cell o))))

(defn- hold-rows
  "One row per VIOLATED RULE (not per hold), so a hold that carries two
  rules at once shows both. Read out of each run's own audit trail; the
  detail text is the governor's own message, verbatim."
  [runs]
  (for [{:keys [tid request] :as r} runs
        :let [o (outcome r)]
        :when (= :hard-hold (:kind o))
        v (:violations o)]
    (row (code tid)
         (code (kw-str (:op request)))
         (code (:subject request))
         (str "<span class=\"critical\">" (esc (kw-str (:rule v))) "</span>")
         (esc (:detail v)))))

(defn- gate-rows
  "The action gate, DERIVED from the live governor/phase vars -- not a
  prose description that could drift away from the code."
  []
  (let [auto3 (get-in phase/phases [3 :auto])]
    (for [o (sort-by kw-str governor/closed-op-allowlist)
          :let [first-write-phase (first (for [p (sort (keys phase/phases))
                                               :when (contains? (:writes (get phase/phases p)) o)]
                                           p))]]
      (row (code (kw-str o))
           (if first-write-phase (n-cell first-write-phase) "<span class=\"muted\">never</span>")
           (if (contains? auto3 o)
             "<span class=\"ok\">may auto-commit when governor-clean</span>"
             "<span class=\"warn\">human approval, every phase</span>")
           (if (contains? governor/high-stakes o)
             "<span class=\"warn\">always high-stakes</span>"
             "<span class=\"muted\">no</span>")))))

(defn- phase-rows []
  (for [p (sort (keys phase/phases))
        :let [{:keys [label writes auto]} (get phase/phases p)]]
    (row (n-cell p) (esc label) (op-codes writes) (op-codes auto))))

(defn- spec-basis-rows
  "The regulatory catalog, read straight out of
  `other-equipment-repair.facts/catalog`, sorted for determinism."
  []
  (for [iso3 (sort (keys facts/catalog))
        :let [{:keys [name owner-authority repair-safety-provenance threshold-model
                      notification-lead-days]}
              (facts/spec-basis iso3)]]
    (row (code iso3)
         (esc name)
         (esc owner-authority)
         (str "<a href=\"" (esc repair-safety-provenance) "\">"
              (esc repair-safety-provenance) "</a>")
         (code (kw-str threshold-model))
         (if notification-lead-days (n-cell notification-lead-days) (dash)))))

(defn- ledger-rows [db]
  (for [{:keys [t op subject disposition basis violations summary]} (store/ledger db)]
    (row (case t
           :committed "<span class=\"ok\">committed</span>"
           :governor-hold "<span class=\"critical\">governor-hold</span>"
           :approval-rejected "<span class=\"critical\">approval-rejected</span>"
           (esc (kw-str t)))
         (code (kw-str op))
         (code subject)
         (esc (kw-str disposition))
         (if (seq violations)
           (esc (str/join ", " (map (comp kw-str :rule) violations)))
           (esc (str/join " ; " (map kw-str basis))))
         (if summary (esc summary) (dash)))))

(defn- artifact-rows [history]
  (for [r history]
    (row (code (get r "record_id"))
         (esc (get r "kind"))
         (code (get r "equipment_id"))
         (esc (get r "jurisdiction"))
         (bool-cell (get r "immutable")))))

(defn- notifier-rows [notifier]
  (for [{:keys [status channel to subject message]} (notify/sent-log notifier)]
    (row (esc (kw-str channel))
         (esc to)
         (esc (or subject message))
         (if (= :sent status)
           "<span class=\"ok\">sent</span>"
           (str "<span class=\"critical\">" (esc (kw-str status)) "</span>")))))

;; ----------------------------- the document -----------------------------

(defn render
  "Renders the whole operator console from a `run-demo!` result. Takes no
  clock and no seed: identical input -> identical bytes."
  [{:keys [db notifier runs]}]
  (let [ledger    (vec (store/ledger db))
        outcomes  (mapv outcome runs)
        hs        (holds db)
        committed (filterv #(= :committed (:t %)) ledger)
        approved  (filterv #(= :approved (:kind %)) outcomes)
        auto      (filterv #(= :auto-commit (:kind %)) outcomes)
        audit-holds (filterv #(= :hard-hold (:kind %)) outcomes)
        rule-set  (into #{} (mapcat (fn [h] (map :rule (:violations h))) hs))
        cov       (facts/coverage)
        att       (approver-attribution db runs)
        notices   (store/safety-concern-flag-history db)]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-3319 &middot; repair of other equipment &mdash; operator console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>Repair of other equipment (ISIC 3319) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">coordination-only &middot; every effect is :propose</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>other-equipment-repair.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by actually running the compiled "
     "<code>other-equipment-repair.operation</code> StateGraph over a freshly seeded store. "
     "Every value below was read back out of that run &mdash; there is no mock markup on this page, "
     "and no timestamp, so successive regenerations are byte-identical.</p>\n"

     "<main>\n"

     (section "Run summary"
              "Counted from the real audit ledger and the real graph results, not asserted."
              ["Measure" "Count"]
              [(row "equipment / work orders in the SSoT" (n-cell (count (store/all-equipment db))))
               (row "graph runs in this scenario" (n-cell (count runs)))
               (row "<span class=\"ok\">auto-commits (governor-clean, phase 3)</span>" (n-cell (count auto)))
               (row "<span class=\"ok\">escalated &rarr; human-approved commits</span>" (n-cell (count approved)))
               (row "<span class=\"critical\">HARD governor holds (never reach a human)</span>" (n-cell (count hs)))
               (row "distinct HARD rules exercised" (n-cell (count rule-set)))
               (row "violated-rule instances across all holds"
                    (n-cell (reduce + 0 (map #(count (:violations %)) hs))))
               (row "holds on the ledger vs. holds in the run audits (must agree)"
                    (str (n-cell (count hs)) " / " (n-cell (count audit-holds))
                         (if (= (count hs) (count audit-holds))
                           " <span class=\"ok\">agree</span>"
                           " <span class=\"critical\">DISAGREE</span>")))
               (row "committed facts in the audit ledger" (n-cell (count committed)))
               (row "audit-ledger facts total" (n-cell (count ledger)))
               (row "confidence floor (<code>governor/confidence-floor</code>)"
                    (n-cell governor/confidence-floor))
               (row "supply-order escalation threshold (<code>governor/supply-order-cost-threshold-usd</code>)"
                    (str (n-cell governor/supply-order-cost-threshold-usd) " USD"))
               (row "jurisdictions with an official spec-basis"
                    (n-cell (str (:covered cov) " / " (:requested cov))))])

     (section "Equipment / work-order directory"
              "The SSoT after the run. <code>equipment-verified?</code> and the open-concern flag are the
               ground truth the Repair Governor re-checks independently &mdash; never the advisor's own
               confidence. The last column is not a domain field: it is the approver id that the
               <code>:log-repair-record</code> commit path really did leave behind &mdash; see
               <em>Approver attribution</em> below."
              ["Id" "Equipment / work order" "Jurisdiction" "Verified?" "Safety concern"
               "Status" "Diagnostic notes" "Safety contacts" "approved-by (leaked in)"]
              (equipment-rows db))

     (section "Operation dispositions (this run)"
              "One row per graph run. The outcome and the hold reason are classified from each run's own
               audit trail; the detail text is the governor's own message, verbatim. The phase column
               matters: <code>t08</code> is governor-clean but still escalates, because at phase 2
               <code>:log-repair-record</code> is a permitted write that is not yet auto-eligible."
              ["Thread" "Phase" "Op" "Subject" "Jurisdiction" "Outcome" "Governor detail"]
              (run-rows db runs))

     (section "HARD governor holds &mdash; one row per violated rule"
              "All six of the Repair Governor's checks are HARD: a human approver cannot override them,
               and none of these runs ever reached a human. <code>t12</code> carries TWO rules at once
               &mdash; <code>equipment-2</code> is in an uncovered jurisdiction AND had a safety concern
               opened against it by <code>t11</code> &mdash; which is why this table is keyed by rule
               rather than by hold."
              ["Thread" "Op" "Subject" "Rule" "Governor detail (verbatim)"]
              (hold-rows runs))

     (attribution-section att)

     (section "Action gate (Repair Governor)"
              "Derived from <code>governor/closed-op-allowlist</code>, <code>governor/high-stakes</code>
               and <code>phase/phases</code> &mdash; if the code changes, this table changes.
               <code>:order-supplies</code> is not a permanent member of <code>high-stakes</code>: its
               escalation is a soft, cost-scoped rule computed per proposal."
              ["Op" "Writable from phase" "At phase 3" "Permanent escalation"]
              (gate-rows))

     (section "Rollout phase ladder"
              "Read straight out of <code>other-equipment-repair.phase/phases</code>.
               <code>:flag-safety-concern</code> is deliberately absent from every phase's auto set,
               including phase 3 &mdash; a permanent structural fact, not a milestone still to come."
              ["Phase" "Label" "Writes allowed" "May auto-commit"]
              (phase-rows))

     (section "Pre-repair hazard / energy-control legal basis"
              "Read straight out of <code>other-equipment-repair.facts/catalog</code>. Every jurisdiction
               is honestly <code>:qualitative</code>: none of the researched sources sets a fixed numeric
               advance-notice lead time for the pre-repair stop-machine / lockout-tagout / qualified-
               personnel duty, and this actor does not invent one. A jurisdiction absent from this table
               has NO spec-basis, and the governor holds any schedule proposal against it &mdash; which is
               exactly what happens to <code>equipment-2</code> (ATL) above."
              ["Jurisdiction" "Name" "Owner authority" "Official source" "Threshold model" "Lead days"]
              (spec-basis-rows))

     (section "Audit ledger"
              "Append-only decision facts the run actually wrote to the store."
              ["Fact" "Op" "Subject" "Disposition" "Basis / violated rule" "Summary"]
              (ledger-rows db))

     (section "Repair-record log"
              "Jurisdiction-scoped sequence numbers built by
               <code>other-equipment-repair.registry</code>."
              ["Record id" "Kind" "Equipment" "Jurisdiction" "Immutable"]
              (artifact-rows (store/repair-record-log-history db)))

     (section "Schedule proposals"
              "A proposed diagnostic / repair / testing WINDOW &mdash; never a repair-equipment or
               diagnostic-tool control command, and never a return-to-service sign-off."
              ["Record id" "Kind" "Equipment" "Jurisdiction" "Immutable"]
              (artifact-rows (store/schedule-proposal-history db)))

     (section "Safety-concern flags"
              "Always human-approved before commit, at every phase &mdash;
               <code>:flag-safety-concern</code> is absent from every phase's auto set, permanently."
              ["Record id" "Kind" "Equipment" "Jurisdiction" "Immutable"]
              (artifact-rows (store/safety-concern-flag-history db)))

     (section "Supply-order proposals"
              "A replacement-parts procurement PROPOSAL &mdash; never a placed order. Proposals above
               <code>governor/supply-order-cost-threshold-usd</code>, or below the confidence floor,
               always escalate to a human first."
              ["Record id" "Kind" "Equipment" "Jurisdiction" "Immutable"]
              (artifact-rows (store/supply-order-proposal-history db)))

     (section "Safety-concern notice dispatch"
              "The mock notifier's own send log &mdash; the notice really went out over both channels to
               every contact on the roster, after a human approved it. <code>equipment-2</code>'s roster
               is really empty in the seed data, so its approved concern produced NO sends at all; that
               absence is real, not an omission."
              ["Channel" "To" "Subject / message" "Status"]
              (notifier-rows notifier))

     "  <section class=\"card\">\n"
     "    <h2>Safety-concern notice documents</h2>\n"
     "    <p class=\"muted\">Rendered by "
     "<code>other-equipment-repair.registry/render-safety-concern-notice</code> and stored verbatim on "
     "the flag record &mdash; it cites the jurisdiction's own pre-repair hazard/energy-control legal "
     "basis inline, so the notice is self-evidencing. The second notice is for an UNCOVERED "
     "jurisdiction and honestly prints <code>NOT COVERED</code> rather than inventing a citation.</p>\n"
     (str/join "\n" (for [n notices]
                      (str "    <pre><code>" (esc (get n "document")) "</code></pre>")))
     "\n  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>This actor NEVER operates repair equipment or diagnostic tools and NEVER signs off on a\n"
     "  return-to-service decision &mdash; that authority belongs exclusively to the licensed repair\n"
     "  technician. Every proposal carries <code>:effect :propose</code>; committing one means a\n"
     "  coordination artifact was logged, never that a tool was operated or that a piece of equipment\n"
     "  was authorized to return to service.</p>\n"
     "  <p>Regenerate: <code>clojure -M:dev:render-html</code></p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)
        commits (filterv #(= :committed (:t %)) (store/ledger db))]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    ;; ...and one that shows no commit at all is not evidence of an actor.
    (when (empty? commits)
      (throw (ex-info "no :committed fact on the ledger — refusing to write a console that shows no clean path"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count commits) " commits, "
                  (count runs) " requests, "
                  (count (store/repair-record-log-history db)) " repair records, "
                  (count (store/schedule-proposal-history db)) " schedule proposals, "
                  (count (store/safety-concern-flag-history db)) " safety-concern flags, "
                  (count (store/supply-order-proposal-history db)) " supply-order proposals)"))))
