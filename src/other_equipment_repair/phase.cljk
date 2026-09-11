(ns other-equipment-repair.phase
  "Phase 0->3 staged rollout -- the repair-of-other-equipment-coordination
  analog of `installation.phase`/`demolition.phase`.

    Phase 0  read-only              -- no writes, still governor-gated.
    Phase 1  assisted-logging       -- `:log-repair-record` allowed,
                                       every write needs human approval.
    Phase 2  assisted-coordination  -- adds `:flag-safety-concern` and
                                       `:order-supplies` writes, still
                                       approval.
    Phase 3  supervised-coordination -- adds `:schedule-repair-
                                       operation`; governor-clean,
                                       high-confidence `:log-repair-
                                       record` (pure data logging, no
                                       capital/safety risk), `:schedule-
                                       repair-operation` (governor-clean,
                                       equipment-verified, legal-basis on
                                       file, no unresolved concern) AND
                                       `:order-supplies` BELOW the cost
                                       threshold may auto-commit.

  ## Actuation (there is none -- read this before changing this file)

  This actor performs NO real-world actuation. Every proposal it can ever
  produce carries `:effect :propose` (see `other-equipment-repair.
  governor` ns docstring checks 1-4) -- 'committing' a proposal here
  means only that a coordination artifact (a repair-record-log entry, a
  schedule PROPOSAL, a safety-concern flag, a supply-order PROPOSAL) is
  now logged in the SSoT + audit ledger. It never operates repair
  equipment/diagnostic tools, never signs off on return-to-service --
  that authority is the licensed repair technician's exclusively.

  `:flag-safety-concern` is DELIBERATELY ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Surfacing an equipment-hazard/
  incomplete-repair/certification-lapse concern is exactly the judgment
  this actor must never let auto-commit; it is always a human's call.
  `other-equipment-repair.governor`'s `high-stakes` set enforces the same
  invariant independently -- two layers, not one, agree on this (see
  `other-equipment-repair.governor` ns docstring).

  UNLIKE `cloud-itonami-isic-3320`'s Installation Phase (which keeps
  `:schedule-installation-operation` off every phase's `:auto` set
  because it coordinates potential heavy-lift/rigging-equipment dispatch
  and pre-energization work), `:schedule-repair-operation` IS a member of
  phase 3's `:auto` set here -- repair-of-other-equipment is the
  ISIC-residual, lower-average-physical-risk class (furniture, sporting/
  recreational goods, musical instruments, miscellaneous equipment
  n.e.c.), and `other-equipment-repair.governor`'s own equipment-
  verification / legal-basis / unresolved-concern HARD checks (checks
  4-6, see that ns docstring) already gate it independently of phase.
  This is a deliberate narrower escalation surface than 3320's -- see
  `other-equipment-repair.governor` ns docstring `high-stakes`.

  `:log-repair-record` (diagnostic-finding/repair-work-performed/parts-
  used DATA LOGGING, no direct capital or safety risk) and `:order-
  supplies` BELOW the cost threshold (see `other-equipment-repair.
  governor/supply-order-cost-threshold-usd`) are ALSO members of phase
  3's `:auto` set -- but the governor's own cost-threshold/confidence-
  floor check can still force `:order-supplies` to escalate even when
  it's `:auto`-eligible, the same 'phase says maybe, governor decides'
  layering `installation.phase`/`construction.phase` established for
  cost-scoped ops.")

(def read-ops  #{})
(def write-ops #{:log-repair-record :schedule-repair-operation
                 :flag-safety-concern :order-supplies})

;; NOTE the invariant: `:flag-safety-concern` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there -- see ns docstring 'Actuation'
;; section above before changing this.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"                 :writes #{}                                            :auto #{}}
   1 {:label "assisted-logging"          :writes #{:log-repair-record}                           :auto #{}}
   2 {:label "assisted-coordination"     :writes #{:log-repair-record :flag-safety-concern
                                                    :order-supplies}                              :auto #{}}
   3 {:label "supervised-coordination"   :writes write-ops
      :auto #{:log-repair-record :schedule-repair-operation :order-supplies}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-safety-concern` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't). `:log-repair-record`, `:schedule-repair-operation`
    and `:order-supplies` MAY auto-commit at phase 3 -- see ns
    docstring."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Repair Governor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
