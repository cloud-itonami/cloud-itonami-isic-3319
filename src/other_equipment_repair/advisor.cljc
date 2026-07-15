(ns other-equipment-repair.advisor
  "Repair Advisor client -- the *contained intelligence node* for the
  repair-of-other-equipment OPERATIONS COORDINATION actor.

  It normalizes repair-record logging (diagnostic-finding/repair-work-
  performed/parts-used data), drafts a diagnostic/repair/testing SCHEDULE
  PROPOSAL (never a repair-equipment/diagnostic-tool control command or a
  return-to-service sign-off), drafts a safety-concern FLAG (equipment-
  hazard / incomplete-repair / certification-lapse), and drafts a
  replacement-parts SUPPLY-ORDER PROPOSAL. CRITICAL: it is a smart-but-
  untrusted advisor, and it is SCOPED -- it holds NO repair-equipment/
  diagnostic-tool control authority and NO return-to-service sign-off
  authority (that is the licensed repair technician's exclusively). It
  returns a *proposal* (with a rationale + the fields it cited), NEVER a
  committed record, a real mail/phone send, a real procurement order, or
  a real return-to-service authorization. Every output carries `:effect
  :propose` and is censored downstream by `other-equipment-repair.
  governor` before anything touches the SSoT -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the legal-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- see `other-equipment-repair.governor`
     :value      map            ; the coordination-artifact payload
     :stake      kw|nil         ; the op itself (drives high-stakes gating) or nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [other-equipment-repair.facts :as facts]
            [other-equipment-repair.store :as store]
            [langchain.model :as model]))

(defn- log-repair-record
  "Repair-record-log normalization -- the LLM only normalizes/validates
  the patch (diagnostic-finding / repair-work-performed / parts-used
  data); it does not invent the equipment, its jurisdiction or its
  ground-truth fields. High confidence, low stakes -- `:stake nil`, the
  lowest-risk op in this actor's closed allowlist."
  [_db {:keys [patch]}]
  {:summary    (str "修理記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :propose
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- schedule-repair-operation
  "Draft a diagnostic/repair/testing SCHEDULE PROPOSAL -- a proposed
  operation window (never a repair-equipment/diagnostic-tool control
  command or a return-to-service sign-off; see `other-equipment-repair.
  governor` ns docstring). `:stake :schedule-repair-operation`."
  [db {:keys [subject window notes]}]
  (let [a (store/equipment db subject)
        iso3 (:jurisdiction a)
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式legal-basisが見つかりません -- スケジュール提案不可")
       :rationale  "other-equipment-repair.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :propose
       :value      {:equipment-id subject :jurisdiction iso3 :window window :notes notes :spec-basis nil}
       :stake      :schedule-repair-operation
       :confidence 0.2}
      {:summary    (str subject " 向け修理作業スケジュール提案 (" (:owner-authority sb) ")"
                        (when a (str " (equipment=" (:name a) ")")))
       :rationale  (str "修理前安全basis: " (:repair-safety-basis sb)
                       " / equipment-verified?=" (:equipment-verified? a)
                       " / safety-concern-unresolved?=" (:safety-concern-unresolved? a))
       :cites      [(:repair-safety-basis sb) (:repair-safety-provenance sb)]
       :effect     :propose
       :value      {:equipment-id subject :jurisdiction iso3 :window window :notes notes
                    :spec-basis (:repair-safety-provenance sb)}
       :stake      :schedule-repair-operation
       :confidence (if (and a (:equipment-verified? a) (not (:safety-concern-unresolved? a))) 0.9 0.3)})))

(defn- flag-safety-concern
  "Draft a SAFETY-CONCERN FLAG -- surfacing an equipment-hazard /
  incomplete-repair / certification-lapse concern for human review.
  `:stake :flag-safety-concern` -- ALWAYS escalates to a human,
  unconditionally, regardless of confidence (see README `Actuation` +
  `other-equipment-repair.governor` ns docstring `high-stakes`)."
  [db {:keys [subject concern-type concern-description]}]
  (let [a (store/equipment db subject)
        sb (facts/spec-basis (:jurisdiction a))]
    {:summary    (str subject ": 安全性懸念を検出（" (name (or concern-type :equipment-hazard)) "）"
                      (when a (str " (equipment=" (:name a) ")")))
     :rationale  (if sb
                   (str "関連basis: " (:repair-safety-basis sb))
                   "現有記録または法域spec-basisが見つかりません")
     :cites      (if sb [(:repair-safety-basis sb)] [])
     :effect     :propose
     :value      {:equipment-id subject
                  :concern-type (or concern-type :equipment-hazard)
                  :concern-description concern-description
                  :subject-line (str "[至急] " (:name a) " 安全性懸念のお知らせ")
                  :body (str (:name a) "について安全性懸念（" (name (or concern-type :equipment-hazard))
                            "）が検出されました。詳細を確認し対応を検討してください。")
                  :message (str (:name a) "、安全性懸念が検出されました。至急ご確認ください。")}
     :stake      :flag-safety-concern
     :confidence 0.9}))

(defn- order-supplies
  "Draft a replacement-parts SUPPLY-ORDER PROPOSAL. `:stake :order-
  supplies` -- escalates when `:cost-usd` exceeds `other-equipment-
  repair.governor/supply-order-cost-threshold-usd`, or when confidence is
  low (see `other-equipment-repair.governor`)."
  [db {:keys [subject items cost-usd vendor]}]
  (let [a (store/equipment db subject)]
    {:summary    (str subject " 向け交換部品発注提案 (" (pr-str items) ", "
                      cost-usd " USD, vendor=" vendor ")"
                      (when a (str " (equipment=" (:name a) ")")))
     :rationale  (if a (str "equipment-verified?=" (:equipment-verified? a)) "現有記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :propose
     :value      {:equipment-id subject :items items :cost-usd cost-usd :vendor vendor}
     :stake      :order-supplies
     :confidence (if (and a (:equipment-verified? a)) 0.85 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-repair-record         (log-repair-record db request)
    :schedule-repair-operation (schedule-repair-operation db request)
    :flag-safety-concern       (flag-safety-concern db request)
    :order-supplies            (order-supplies db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :propose :value {} :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは修理工房の運行調整（オペレーションズ・コーディネーション）"
       "エージェントの助言者です。与えられた事実のみに基づき、提案を1つだけEDNマップで"
       "返します。説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に:propose) "
       ":value(提案内容のmap) "
       ":stake(:log-repair-record|:schedule-repair-operation|:flag-safety-concern|"
       ":order-supplies のいずれか) :confidence(0..1)。\n"
       "重要: あなたは修理機器/診断ツールを直接操作するコマンドや、返品・稼働再開"
       "（return-to-service）の確定を伴う提案を絶対に作成してはいけません（免許を持つ"
       "修理技術者の専権事項）。登録されていない法域の要件を絶対に創作してはいけません。"
       "legal-basisが無い場合は:citesを空にしconfidenceを上げないこと。"))

(defn- facts-for [st {:keys [subject]}]
  {:equipment (store/equipment st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Repair Governor
  escalates/holds -- an LLM hiccup can never auto-schedule an operation,
  auto-flag (or suppress) a safety concern, or auto-order supplies."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose))
          (update :value #(or % {})))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :value {} :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
