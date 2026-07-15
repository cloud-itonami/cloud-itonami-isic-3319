(ns other-equipment-repair.governor
  "Repair Governor -- the independent compliance layer named in
  `blueprint.edn` (`:itonami.blueprint/governor :other-equipment-repair-
  governor`) that earns the Repair Advisor the right to commit. The LLM
  has no notion of repair-shop safety law, whether an equipment/work-
  order's own recorded verification actually reflects reality, or when a
  proposal has quietly drifted outside this actor's charter into
  repair-equipment/diagnostic-tool control or a return-to-service
  sign-off, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the repair-of-other-equipment analog
  of `cloud-itonami-isic-3320`'s Installation Governor.

  ## Scope -- this is a COORDINATION-ONLY actor

  This actor NEVER controls repair equipment/diagnostic tools and NEVER
  signs off on return-to-service -- that authority is the licensed
  repair technician's EXCLUSIVELY. Every proposal this actor's Repair
  Advisor can produce carries `:effect :propose` and NOTHING else --
  committing a proposal here means 'this coordination artifact is now
  logged/scheduled/flagged/ordered', never 'a repair tool was operated'
  or 'equipment is authorized to return to service'. Checks 1-4 below
  encode this scope as STRUCTURAL, permanent HARD holds -- not policy
  that could be relaxed by a future phase, unlike checks 5-6, which are
  ordinary per-jurisdiction/ground-truth safety gates.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them.

    1. Unknown op                -- the proposal's `:op` is outside the
                                     CLOSED four-op allowlist
                                     (`:log-repair-record` / `:schedule-
                                     repair-operation` / `:flag-safety-
                                     concern` / `:order-supplies`).
                                     Permanent, structural.
    2. Effect is not :propose    -- ANY proposal whose `:effect` is not
                                     literally `:propose` is rejected,
                                     unconditionally. Defense-in-depth: a
                                     compromised/malfunctioning advisor
                                     can never slip a real-world actuation
                                     effect past this governor. Permanent,
                                     structural.
    3. Forbidden action class    -- a proposal whose `:value` carries a
                                     `:repair-equipment-control?` /
                                     `:diagnostic-tool-control?` /
                                     `:direct-actuation?` / `:return-to-
                                     service-sign-off?` marker true is
                                     rejected, unconditionally -- even
                                     though this actor's own mock advisor
                                     never sets these, the governor checks
                                     independently so a compromised
                                     advisor gains nothing by trying.
                                     Permanent, structural, un-overridable
                                     by ANY human approval -- see README
                                     `Actuation`.
    4. Equipment/work-order not
       independently verified/
       registered                 -- for `:schedule-repair-operation` /
                                     `:flag-safety-concern` / `:order-
                                     supplies`, the equipment/work-order's
                                     own recorded `:equipment-verified?`
                                     ground-truth field (set only via a
                                     separately-committed `:log-repair-
                                     record`, never trusted from the
                                     CURRENT proposal's own confidence)
                                     must be true.
    5. Legal-basis missing       -- for `:schedule-repair-operation`, did
                                     the proposal cite an OFFICIAL source
                                     (`other-equipment-repair.facts`), or
                                     invent one for an uncovered
                                     jurisdiction?
    6. Unresolved safety concern -- for `:schedule-repair-operation`, the
                                     equipment/work-order's own recorded
                                     `:safety-concern-unresolved?`
                                     ground-truth field must be false.
                                     Evaluated off the STORE's ground
                                     truth, never the current proposal's
                                     own confidence -- the same 'ground
                                     truth, not self-report' discipline
                                     `installation.governor/unresolved-
                                     safety-concern-violations` established.
                                     Does NOT fire for `:flag-safety-
                                     concern` itself (that op ALWAYS
                                     escalates to a human regardless of
                                     its own content -- see check on
                                     `high-stakes` below and `other-
                                     equipment-repair.phase` ns
                                     docstring).

  UNLIKE `cloud-itonami-isic-3320`'s Installation Governor (which
  independently recomputes a numeric notification-lead-time-insufficient
  HARD check for `:schedule-installation-operation` in `:quantitative`
  jurisdictions), this actor's `other-equipment-repair.facts` catalog
  found NO `:quantitative` jurisdiction for the pre-repair hazard/energy-
  control duty (see that ns docstring) -- so there is no numeric bright
  line to independently re-check here, and no seventh HARD check exists
  for it. This is an honest absence, not an oversight.

  The confidence/high-stakes gate is SOFT: it asks a human to look, and
  the human may approve.

    - `:flag-safety-concern` is UNCONDITIONALLY a member of `high-stakes`
      -- it ALWAYS escalates to a human, at every phase, regardless of
      confidence or governor cleanliness. Surfacing an equipment-hazard/
      incomplete-repair/certification-lapse concern is exactly the kind
      of judgment this actor must never let auto-commit.
    - `:schedule-repair-operation` is DELIBERATELY NOT a permanent member
      of `high-stakes` -- UNLIKE `cloud-itonami-isic-3320`'s `:schedule-
      installation-operation` (which coordinates potential heavy-lift/
      rigging-equipment dispatch and therefore always needs a human),
      scheduling a diagnostic/repair/testing window for repair-of-other-
      equipment (the ISIC-residual, lower-average-physical-risk class --
      furniture, sporting/recreational goods, musical instruments,
      miscellaneous equipment n.e.c.) MAY auto-commit at phase 3 when the
      governor is completely clean (see `other-equipment-repair.phase` ns
      docstring 'Actuation' section). This is a deliberate, narrower
      escalation surface than 3320's, matching the smaller intrinsic
      hazard of this residual repair-services class.
    - `:order-supplies` escalates when its own proposed `:value :cost-
      usd` exceeds `supply-order-cost-threshold-usd`, OR when confidence
      is below `confidence-floor` -- a soft, cost-scoped gate, NOT a
      permanent per-op membership in `high-stakes`."
  (:require [other-equipment-repair.store :as store]))

(def confidence-floor 0.6)

(def supply-order-cost-threshold-usd
  "Above this, a `:order-supplies` proposal always escalates to a human,
  regardless of confidence -- see ns docstring."
  5000)

(def closed-op-allowlist
  #{:log-repair-record :schedule-repair-operation :flag-safety-concern :order-supplies})

(def high-stakes
  "Ops that ALWAYS escalate to a human when the governor is otherwise
  clean, at every phase, unconditionally. `:log-repair-record` is
  deliberately NOT a member -- low-risk data logging/normalization, the
  same posture `installation.governor/high-stakes` gives `:log-
  installation-record`. `:schedule-repair-operation` is ALSO deliberately
  NOT a member -- see ns docstring for why this actor's escalation
  surface is narrower than `cloud-itonami-isic-3320`'s. `:order-supplies`
  is deliberately NOT a permanent member either -- its escalation is a
  SOFT, cost-scoped rule computed in `check` below, not a blanket
  'always a human' rule."
  #{:flag-safety-concern})

;; ----------------------------- checks -----------------------------

(defn- unknown-op-violations
  "The proposal's `:op` must be a member of the CLOSED four-op allowlist.
  Permanent, structural -- see ns docstring check 1."
  [{:keys [op]}]
  (when-not (contains? closed-op-allowlist op)
    [{:rule :unknown-op
      :detail (str op " はこのアクターの許可された4オペレーション（:log-repair-record/"
                  ":schedule-repair-operation/:flag-safety-concern/:order-supplies）"
                  "のいずれにも該当しない")}]))

(defn- effect-not-propose-violations
  "Every proposal from this actor's advisor must carry `:effect
  :propose` -- and nothing else. Permanent, structural -- see ns
  docstring check 2."
  [proposal]
  (when-not (= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "提案の:effectが:propose以外（" (pr-str (:effect proposal))
                  "）-- このアクターは提案のみで、実際の作動/確定を一切行わない")}]))

(defn- forbidden-action-class-violations
  "A proposal's `:value` must never carry a repair-equipment-control /
  diagnostic-tool-control / direct-actuation / return-to-service-sign-off
  marker. Permanent, structural, un-overridable by any human approval --
  see ns docstring check 3."
  [proposal]
  (let [v (:value proposal)]
    (when (and (map? v)
               (or (true? (:repair-equipment-control? v))
                   (true? (:diagnostic-tool-control? v))
                   (true? (:direct-actuation? v))
                   (true? (:return-to-service-sign-off? v))))
      [{:rule :forbidden-action-class
        :detail "修理機器/診断ツールの直接操作コマンド、または返品・稼働再開（return-to-service）の確定を伴う提案は恒久的に禁止（免許を持つ修理技術者の専権事項）"}])))

(defn- equipment-not-verified-violations
  "For `:schedule-repair-operation` / `:flag-safety-concern` / `:order-
  supplies`, the equipment/work-order's own recorded `:equipment-
  verified?` ground-truth field (set only via a separately-committed
  `:log-repair-record`) must be true. See ns docstring check 4."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-repair-operation :flag-safety-concern :order-supplies} op)
    (let [a (store/equipment st subject)]
      (when-not (true? (:equipment-verified? a))
        [{:rule :equipment-not-verified
          :detail (str subject " は現有記録が独立して検証・登録済み（:equipment-verified?）でない状態での提案")}]))))

(defn- legal-basis-missing-violations
  "For `:schedule-repair-operation`, the proposal must cite an OFFICIAL
  source -- never invent a jurisdiction's pre-repair hazard/energy-
  control requirements. See ns docstring check 5."
  [{:keys [op]} proposal]
  (when (= op :schedule-repair-operation)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-legal-basis
          :detail "公式legal-basisの引用が無い提案は修理作業スケジュール提案として扱えない"}]))))

(defn- unresolved-safety-concern-violations
  "For `:schedule-repair-operation`, the equipment/work-order's own
  recorded `:safety-concern-unresolved?` ground-truth field must be
  false. See ns docstring check 6."
  [{:keys [op subject]} st]
  (when (= op :schedule-repair-operation)
    (let [a (store/equipment st subject)]
      (when (true? (:safety-concern-unresolved? a))
        [{:rule :unresolved-safety-concern
          :detail (str subject " は未解決の安全性懸念（機器の危険性/修理未完了/認証切れ）がある状態での修理作業スケジュール提案")}]))))

(defn check
  "Censors a Repair Advisor proposal against the governor rules. Returns
  {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes?
  bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (unknown-op-violations request)
                           (effect-not-propose-violations proposal)
                           (forbidden-action-class-violations proposal)
                           (equipment-not-verified-violations request st)
                           (legal-basis-missing-violations request proposal)
                           (unresolved-safety-concern-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        cost (get-in proposal [:value :cost-usd])
        cost-stakes? (and (= (:op request) :order-supplies)
                          (number? cost)
                          (> cost supply-order-cost-threshold-usd))
        stakes? (boolean (or (high-stakes (:stake proposal)) cost-stakes?))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
