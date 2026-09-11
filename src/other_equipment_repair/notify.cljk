(ns other-equipment-repair.notify
  "Mail + phone transport SEAM for the SAFETY-CONCERN NOTICE this actor
  sends once a human has approved committing a `:flag-safety-concern`
  proposal -- the concrete 呼びかけ／共有 (outreach/sharing) deliverable,
  the repair-of-other-equipment-domain analog of `installation.notify`/
  `demolition.notify`.

  `Notifier` is the injection seam: `mock-notifier` (deterministic, no
  network -- the default, and the ONLY implementation shipped by this
  repo) for dev/tests/demo. `dispatch-safety-concern-notice!` fans the
  SAME notice out to every contact in an equipment/work-order's
  `:safety-contacts` roster (the repair technician / shop safety officer,
  NOT the customer -- this actor coordinates internal repair-shop safety
  response, it does not manage customer communications) over BOTH
  channels, isolating one contact's send failure from every other
  contact's.

  UNLIKE this repo's `installation`/`demolition`/`construction` siblings
  (some of which also ship real `java.net.http`-backed Resend/Twilio
  transports guarded by `#?(:clj ...)`), this repo deliberately ships NO
  JVM-only interop anywhere in `src/` -- see this workspace's cljs-first
  `.cljc` runtime-priority rule (CLAUDE.md `.cljc`/`.kotoba` runtime
  priority section: `kotoba wasm` > `clojurewasm` > `ClojureScript` >
  `nbb`, JVM/`bb` downgraded to a last resort) and this build's explicit
  no-JVM-interop mandate. A real transport (Resend mail / Twilio voice,
  or an equivalent) can be added later behind the SAME `Notifier`
  protocol via a portable HTTP client (e.g. a `cljs`/`nbb` `fetch`-based
  implementation) without changing this namespace's shape or any caller
  -- until then, this actor's safety-concern notice is 'sent' only via
  the deterministic mock, and callers that need a real send must supply
  their own `Notifier` implementation.

  `:flag-safety-concern` is NEVER fired by an auto-commit -- it always
  escalates to a human first (see `other-equipment-repair.phase`/`other-
  equipment-repair.governor` ns docstrings), so by the time `other-
  equipment-repair.operation`'s `:commit` node calls this, a human has
  already reviewed the concern. This namespace builds/sends the message;
  it does NOT decide whether to send one."
  )

(defprotocol Notifier
  (-send-mail! [n msg] "msg: {:to :subject :body} -> {:status :channel :to ..}")
  (-send-phone-call! [n msg] "msg: {:to :message} -> {:status :channel :to ..}"))

;; ----------------------------- mock (default, and only shipped implementation) -----------------------------

(defrecord MockNotifier [log]
  Notifier
  (-send-mail! [_ {:keys [to subject body]}]
    (let [result {:status :sent :channel :mail :to to :subject subject :body body}]
      (swap! log conj result)
      result))
  (-send-phone-call! [_ {:keys [to message]}]
    (let [result {:status :sent :channel :phone :to to :message message}]
      (swap! log conj result)
      result)))

(defn mock-notifier
  "A deterministic notifier -- no network, records every send to an
  internal log atom (`sent-log`). Default everywhere (dev/tests/demo),
  and the only `Notifier` this repo ships -- see ns docstring."
  ([] (mock-notifier (atom [])))
  ([log] (->MockNotifier log)))

(defn sent-log [^MockNotifier n] @(:log n))

;; ----------------------------- composition -----------------------------

(defrecord DualNotifier [mail-notifier phone-notifier]
  Notifier
  (-send-mail! [_ msg] (-send-mail! mail-notifier msg))
  (-send-phone-call! [_ msg] (-send-phone-call! phone-notifier msg)))

(defn dual-notifier
  "Compose a mail-only Notifier and a phone-only Notifier into one --
  the shape a safety-concern notice needs (mail と 電話 の両方). Useful
  for a caller that wires its own real mail/phone `Notifier`
  implementations behind this protocol without this repo shipping any
  JVM-only transport itself."
  [mail-notifier phone-notifier]
  (->DualNotifier mail-notifier phone-notifier))

(defn dispatch-safety-concern-notice!
  "Send the safety-concern-notice message to every contact in
  `safety-contacts` ({:name :email :phone}) via BOTH mail and phone,
  using `notifier`. Returns a vector of per-contact per-channel send
  results. A failed send for one contact/channel is recorded as
  `{:status :failed ...}`, never thrown past this call -- one bad phone
  number or a transient network error must never suppress notifying
  every other responsible party of a flagged equipment-hazard/
  incomplete-repair/certification-lapse concern."
  [notifier safety-contacts {:keys [subject-line body message]}]
  (vec
   (for [{:keys [name email phone]} safety-contacts]
     {:contact name
      :mail (try (-send-mail! notifier {:to email :subject subject-line :body body})
                 (catch #?(:clj Exception :cljs :default) e
                   {:status :failed :channel :mail :to email :error (ex-message e)}))
      :phone (try (-send-phone-call! notifier {:to phone :message message})
                  (catch #?(:clj Exception :cljs :default) e
                    {:status :failed :channel :phone :to phone :error (ex-message e)}))})))
