(ns other-equipment-repair.facts
  "Per-jurisdiction pre-repair hazard/energy-control regulatory catalog --
  the spec-basis table the Repair Governor checks every `:schedule-
  repair-operation` proposal against ('did the advisor cite an OFFICIAL
  public source for this jurisdiction's stop-machine/lockout-tagout/
  qualified-personnel duty before repair work begins, or did it invent
  one?'). Same honest-coverage discipline `installation.facts`/
  `demolition.facts`/`construction.facts` established for this fleet: a
  jurisdiction not in this table has NO spec-basis, full stop -- the
  advisor must not fabricate one, and the governor holds if it tries.

  Coverage is reported HONESTLY (see `coverage`); this is a STARTING
  catalog (JPN/USA/DEU), not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to `catalog`,
  cite a real source, done -- never invent a jurisdiction's requirements
  to make coverage look bigger.

  UNLIKE `installation.facts` (which found ONE `:quantitative`
  jurisdiction -- Japan's Industrial Safety and Health Act Article 88 --
  for an installation-notification PLAN filing), this catalog's research
  found ZERO `:quantitative` jurisdictions among JPN/USA/DEU for the
  pre-repair hazard/energy-control duty itself: every seeded source is a
  PROCEDURAL requirement (stop the machine, lock/tag it out, use
  qualified/designated personnel) with no fixed numeric advance-notice-
  days count. This actor does NOT invent one:
    :qualitative -- the law imposes a documented stop-machine/lockout-
                    tagout/qualified-personnel duty before repair,
                    inspection, cleaning or adjustment work starts, with
                    NO fixed jurisdiction-wide numeric lead-time this
                    actor could independently verify at the time this
                    catalog was built (all three jurisdictions below).
                    `notification-lead-insufficient?` therefore always
                    returns `:qualitative` for a covered jurisdiction in
                    this catalog -- the Repair Governor's `legal-basis-
                    missing` HARD check (see `other-equipment-repair.
                    governor` ns docstring) is the bright line this
                    catalog actually supports; there is no numeric
                    lead-time bright line to independently re-check on
                    top of it.

  Real sources, verified before this catalog was written (no
  fabrication):
    JPN -- 労働安全衛生規則（昭和47年労働省令第32号）第107条（掃除等の場合の
           運転停止等）: the employer must stop a machine's operation
           before cleaning, oiling, inspection, repair or adjustment work
           when it could endanger a worker (unless a guard/cover is fitted
           over the danger point during work that must be done while the
           machine runs), and must lock the machine's start switch and
           attach a warning sign so nobody else can restart it during that
           work -- https://laws.e-gov.go.jp/law/347M50002000032
    USA -- OSHA 29 CFR 1910.147 (The Control of Hazardous Energy --
           Lockout/Tagout): before an employee performs servicing,
           maintenance or REPAIR work on machinery where unexpected
           energization/start-up/release of stored energy could cause
           injury, the machine must be isolated from its energy source
           and rendered safe under a documented energy-control program,
           carried out by an authorized employee --
           https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.147
    DEU -- Betriebssicherheitsverordnung (BetrSichV) §10 (Instandhaltung
           und Änderung von Arbeitsmitteln): the employer must ensure
           maintenance (Instandhaltung -- which the regulation itself
           defines to include Instandsetzung/repair) is carried out only
           by suitably qualified, authorized, instructed personnel, with
           work areas secured and hazardous energy controlled --
           https://www.gesetze-im-internet.de/betrsichv_2015/__10.html;
           grounded in Directive 2009/104/EC (minimum safety and health
           requirements for the use of work equipment by workers), which
           explicitly requires that 'in the case of repairs,
           modifications, maintenance or servicing, the workers concerned
           are specifically designated to carry out such work' --
           https://eur-lex.europa.eu/eli/dir/2009/104/oj/eng. UNLIKE
           `installation.facts`'s DEU entry (which cites the Machinery
           Directive 2006/42/EC, a manufacturer/placing-on-market
           instrument aimed at NEW equipment before commissioning), this
           actor cites 2009/104/EC, the correct EU-level instrument for
           safety during the USE (including repair) of equipment by
           workers -- not the same directive reused out of context.

  DEU is used as the EU-jurisdiction proxy, the SAME convention
  `installation.facts`/`demolition.facts`/`construction.facts`/
  `aerospace.facts` established -- there is no ISO-3166 alpha-3 code for
  the EU itself.")

(def catalog
  "iso3 -> requirement map. `:repair-safety-basis` / its `-provenance`,
  plus `:owner-authority`, are the citation the governor requires before a
  `:schedule-repair-operation` proposal can ever commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省（労働基準監督署長）"
          :repair-safety-basis "労働安全衛生規則（昭和47年労働省令第32号）第107条（掃除等の場合の運転停止等 -- 機械の掃除、給油、検査、修理又は調整の作業を行う場合において、労働者に危険を及ぼすおそれのあるときは機械の運転を停止し、起動装置に錠を掛け表示板を取り付ける等の措置を講じる義務）"
          :repair-safety-provenance "https://laws.e-gov.go.jp/law/347M50002000032"
          :threshold-model :qualitative
          :notification-lead-days nil
          :threshold-note "労働安全衛生規則第107条は修理作業前の機械停止・起動装置の施錠・表示措置を義務付けるが、固定日数の事前届出リードタイムは定めていない -- ここで数値を創作しない。"}
   "USA" {:name "United States"
          :owner-authority "Occupational Safety and Health Administration (OSHA), U.S. Department of Labor"
          :repair-safety-basis "29 CFR 1910.147 (The Control of Hazardous Energy -- Lockout/Tagout): before an authorized employee performs servicing, maintenance or REPAIR work on machinery where unexpected energization/start-up/release of stored energy could cause injury, the machine must be isolated from its energy source and rendered safe under a documented energy-control program"
          :repair-safety-provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.147"
          :threshold-model :qualitative
          :notification-lead-days nil
          :threshold-note "OSHA's lockout/tagout standard requires a documented energy-control program be in place and verified BEFORE repair/servicing work begins, but sets no fixed federal advance-notice-days count -- this actor does not invent one. This actor's `:schedule-repair-operation` still always requires an on-file legal-basis citation regardless (see `other-equipment-repair.governor` ns docstring `legal-basis-missing`)."}
   "DEU" {:name "Germany (EU jurisdiction proxy, see ns docstring)"
          :owner-authority "Bundesministerium für Arbeit und Soziales (BMAS) / Deutsche Gesetzliche Unfallversicherung (DGUV); EU level: European Parliament and Council"
          :repair-safety-basis "Verordnung über Sicherheit und Gesundheitsschutz bei der Verwendung von Arbeitsmitteln (Betriebssicherheitsverordnung, BetrSichV) §10 (Instandhaltung und Änderung von Arbeitsmitteln -- Instandhaltungsmaßnahmen, einschließlich Instandsetzung/Reparatur, dürfen nur von fachkundigen, beauftragten und unterwiesenen Beschäftigten durchgeführt werden, mit Absicherung des Arbeitsbereichs und Beherrschung gefährlicher Energien); grounded in Directive 2009/104/EC concerning the minimum safety and health requirements for the use of work equipment by workers, which requires that in the case of repairs, modifications, maintenance or servicing, the workers concerned are specifically designated to carry out such work"
          :repair-safety-provenance "https://www.gesetze-im-internet.de/betrsichv_2015/__10.html"
          :threshold-model :qualitative
          :notification-lead-days nil
          :threshold-note "EU/ドイツの作業設備使用関連法令（指令2009/104/EC、BetrSichV §10）は修理・保守作業を有資格者に限定し危険源の管理を義務付けるのみで、日本の労働安全衛生規則第107条のような固定日数の事前届出リードタイムはEU全域では法定されていない -- ここで数値を創作しない。"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any `:schedule-repair-operation` proposal
  that tries to cite one."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-3319 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `other-equipment-repair.facts/"
                 "catalog`, never fabricate a jurisdiction's requirements.")})))

(defn notification-lead-insufficient?
  "Independently recompute whether a jurisdiction has a fixed numeric
  advance-notice-days requirement this actor could re-check. Three-valued,
  deliberately (the same shape `installation.facts/notification-lead-
  insufficient?` established):
    true/false   -- never produced by this catalog (see ns docstring):
                    none of JPN/USA/DEU carries a `:quantitative`
                    threshold-model for the pre-repair hazard/energy-
                    control duty.
    :qualitative -- a jurisdiction with NO fixed numeric lead-time (every
                    covered jurisdiction in this catalog). This actor
                    cannot independently confirm 'sufficient' or
                    'insufficient' by arithmetic alone. Never fabricate a
                    lead-time here.
    nil          -- no spec-basis at all for `iso3` (a jurisdiction not in
                    `catalog`)."
  [iso3 _equipment]
  (when-let [{:keys [threshold-model]} (spec-basis iso3)]
    (case threshold-model
      :qualitative :qualitative
      nil)))
