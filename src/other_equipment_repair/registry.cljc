(ns other-equipment-repair.registry
  "Pure-function repair-record-log / schedule-proposal / safety-concern-
  flag / supply-order-proposal record construction -- an append-only
  repair-shop book-of-record draft, the repair-of-other-equipment-domain
  analog of `installation.registry`/`demolition.registry`.

  Like every sibling actor's registry, there is no single international
  check-digit standard for any of these reference numbers -- every
  operator/jurisdiction assigns its own reference format. This namespace
  does NOT invent one; it builds a jurisdiction-scoped sequence number and
  validates the record's required fields, the same honest, non-
  fabricating discipline `other-equipment-repair.facts` uses.

  `render-safety-concern-notice` produces the actual human-readable
  document text sent to the equipment/work-order's repair-technician/
  shop-safety-officer contact roster (`other-equipment-repair.notify`) --
  citing the jurisdiction's pre-repair hazard/energy-control legal basis
  inline so the notice is self-evidencing about which law grounds the
  concern.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any real regulatory filing system, no mail/phone send. It builds the
  RECORD/DOCUMENT this actor's `:effect :propose`-only proposals produce;
  it never builds, and this actor never proposes, a record that commands
  repair equipment/diagnostic tools or signs off on return-to-service
  (see `other-equipment-repair.governor` ns docstring)."
  (:require [clojure.string :as str]
            [other-equipment-repair.facts :as facts]))

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- assert-record-fields! [op-label equipment-id jurisdiction sequence]
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info (str op-label ": equipment_id required") {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info (str op-label ": jurisdiction required") {})))
  (when (< sequence 0)
    (throw (ex-info (str op-label ": sequence must be >= 0") {}))))

(defn register-repair-record
  "Validate + construct the REPAIR-RECORD-LOG registration DRAFT -- one
  entry in the append-only log of diagnostic-finding / repair-work-
  performed / parts-used data this actor's `:log-repair-record` op
  produces. Pure function -- does not verify or register anything itself;
  it builds the RECORD an operator would keep. `other-equipment-repair.
  governor` independently re-verifies the equipment/work-order's own
  recorded `:equipment-verified?` ground-truth field before any OTHER op
  may commit against it."
  [equipment-id jurisdiction sequence]
  (assert-record-fields! "repair-record" equipment-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-RPR-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "repair-record-log-entry"
               "equipment_id" equipment-id "jurisdiction" jurisdiction "immutable" true}
     "repair_record_number" record-id}))

(defn register-schedule-proposal
  "Validate + construct the REPAIR-OPERATION SCHEDULE-PROPOSAL DRAFT -- a
  proposed diagnostic/repair/testing schedule window, NEVER a repair-
  equipment/diagnostic-tool control command or a return-to-service
  sign-off (that authority is the licensed repair technician's
  exclusively -- see README and `other-equipment-repair.governor` ns
  docstring). Pure function -- `other-equipment-repair.governor`
  independently re-verifies the equipment/work-order is verified, its
  jurisdiction's pre-repair hazard/energy-control legal basis is on file,
  and no safety concern is unresolved on file, before this is ever
  allowed to commit."
  [equipment-id jurisdiction sequence]
  (assert-record-fields! "schedule-proposal" equipment-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-SCH-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "schedule-proposal-draft"
               "equipment_id" equipment-id "jurisdiction" jurisdiction "immutable" true}
     "schedule_number" record-id}))

(defn register-safety-concern-flag
  "Validate + construct the SAFETY-CONCERN-FLAG DRAFT -- surfacing an
  equipment-hazard / incomplete-repair / certification-lapse concern for
  human review. Pure function -- `:flag-safety-concern` ALWAYS escalates
  to a human at every phase (see `other-equipment-repair.phase`/`other-
  equipment-repair.governor` ns docstrings), so this record is only ever
  committed after a human has reviewed it."
  [equipment-id jurisdiction sequence]
  (assert-record-fields! "safety-concern-flag" equipment-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-SCF-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "safety-concern-flag-draft"
               "equipment_id" equipment-id "jurisdiction" jurisdiction "immutable" true}
     "concern_number" record-id}))

(defn register-supply-order-proposal
  "Validate + construct the SUPPLY-ORDER PROPOSAL DRAFT -- a replacement-
  parts procurement proposal. Pure function -- does not place any real
  order; it builds the RECORD an operator would keep. Proposals above a
  cost threshold, or below the confidence floor, always escalate to a
  human (see `other-equipment-repair.governor`)."
  [equipment-id jurisdiction sequence]
  (assert-record-fields! "supply-order-proposal" equipment-id jurisdiction sequence)
  (let [record-id (str (str/upper-case jurisdiction) "-SUP-" (zero-pad sequence 6))]
    {"record" {"record_id" record-id "kind" "supply-order-proposal-draft"
               "equipment_id" equipment-id "jurisdiction" jurisdiction "immutable" true}
     "order_number" record-id}))

;; ----------------------------- notice document -----------------------------

(defn render-safety-concern-notice
  "Human-readable SAFETY-CONCERN NOTICE document text, citing the
  jurisdiction's pre-repair hazard/energy-control legal basis inline --
  the document sent (mail + phone, `other-equipment-repair.notify`) to
  the equipment/work-order's repair-technician/shop-safety-officer
  contact roster once a human has approved logging the concern.
  `equipment` is the equipment/work-order record at flag time;
  `concern-number` is from `register-safety-concern-flag`."
  [{:keys [id name jurisdiction]} concern-number concern-description]
  (let [{:keys [repair-safety-basis repair-safety-provenance owner-authority]} (facts/spec-basis jurisdiction)]
    (str "# Repair-Shop Equipment Safety-Concern Notice\n\n"
         "Concern number: " concern-number "\n"
         "Equipment/work-order: " name " (" id ")\n"
         "Jurisdiction: " jurisdiction "\n"
         "Relevant authority: " (or owner-authority "n/a") "\n"
         "Related pre-repair hazard/energy-control basis: " (or repair-safety-basis "NOT COVERED -- no jurisdiction spec-basis on file") "\n"
         "Source: " (or repair-safety-provenance "n/a") "\n\n"
         "## Concern description\n" (or concern-description "(not recorded)") "\n\n"
         "## Status\nThis is a COORDINATION NOTICE only -- it proposes nothing about "
         "repair-equipment/diagnostic-tool control or a return-to-service sign-off. "
         "Resolution and any return-to-service decision remain the licensed repair "
         "technician's exclusive authority.\n")))

(defn append [history result]
  (conj (vec history) (get result "record")))
