# cloud-itonami-isic-3319

Open Business Blueprint for **ISIC Rev.5 3319**: repair of other equipment.

This repository designs a forkable OSS business for repair-shop
operations coordination: run by a qualified operator so a community
keeps its own operating records instead of renting a closed SaaS.

ISIC 3319 is the **residual** repair-services class -- repair of
miscellaneous equipment not covered by the more specific repair classes
3311 (fabricated metal products), 3312 (machinery and equipment), 3313
(electronic and optical equipment), 3314 (electrical equipment) or 3315
(transport equipment). Typical examples: furniture, sporting/recreational
goods, musical instruments, and other equipment n.e.c.

## Scope -- this is a COORDINATION-ONLY actor, not equipment control

This is a safety-relevant domain: repair work can involve stored/residual
energy, sharp tools and incomplete-repair hazards. **This actor does NOT
hold repair-equipment/diagnostic-tool control authority, and it does NOT
hold return-to-service sign-off authority.** Both are the licensed repair
technician's exclusive authority, always. The Repair Advisor (LLM) never
issues an equipment/tool-control command and never signs off on
returning repaired equipment to service; the independent **Repair
Governor** HARD-blocks any proposal that even tries (un-overridable by
any human approval -- see `other-equipment-repair.governor` ns
docstring). This actor coordinates *potential* diagnostic/repair/testing
dispatch (a proposed schedule window, a flagged concern, a supply-order
proposal) -- it never directly actuates.

Structurally, EVERY proposal this actor's advisor can produce carries
`:effect :propose`, and the Repair Governor HARD-holds any proposal that
doesn't -- this is a permanent invariant distinguishing this actor from
actors whose sibling ops DO commit real-world effects.

## Core Contract

```text
equipment/work-order record + independent verification
        |
        v
Advisor -> Repair Governor -> proceed (log/schedule/flag/order proposal), hold, or human approval
        |
        v
coordination artifacts (schedule proposal, safety-concern flag,
supply-order proposal) + audit ledger -- NEVER repair-equipment/
diagnostic-tool dispatch, NEVER a return-to-service sign-off
```

No automated advice can propose a schedule the governor refuses, suppress
a safety-concern flag, or slip an equipment-control/return-to-service
marker past the governor -- and `:flag-safety-concern` always needs a
human sign-off regardless of how clean the governor's check comes back
(see `Actuation` below).

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `3319`). Required capabilities:

- `:identity`
- `:forms`
- `:audit-ledger`

## Implemented slice (`src/other_equipment_repair`)

`blueprint.edn` names the governor `:other-equipment-repair-governor` and
is now `:implemented`. This repo implements it end-to-end -- **Repair
Advisor ⊣ Repair Governor** -- following the SAME `.cljc` actor pattern
(langgraph-clj StateGraph, mock-by-default advisor, dual MemStore/Datomic
backend, 0→3 phase rollout) every prior `cloud-itonami-isic-*` actor in
this fleet uses, structured after
[`cloud-itonami-isic-3320`](https://github.com/cloud-itonami/cloud-itonami-isic-3320)
(Installation of Industrial Machinery and Equipment -- the closest
structural analog: also a coordination-only actor), narrowed to
repair-shop diagnostic/repair/testing coordination as described above.

### Closed op-allowlist (4 ops, all `:effect :propose`)

| Op | Ask | Implementation |
|---|---|---|
| `:log-repair-record` | diagnostic-finding / repair-work-performed / parts-used data logging | Normalizes and commits a patch onto the equipment/work-order's ground-truth fields (`:equipment-verified?`, concern resolution, etc.) and appends an immutable repair-record-log entry. No direct capital/safety risk -- MAY auto-commit at phase 3. |
| `:schedule-repair-operation` | diagnostic/repair/testing scheduling proposal | Drafts a proposed schedule WINDOW (never a repair-equipment/diagnostic-tool control command or a return-to-service sign-off). MAY auto-commit at phase 3 when the governor is clean -- see `Actuation` below for why this differs from `cloud-itonami-isic-3320`. |
| `:flag-safety-concern` | surface an equipment-hazard / incomplete-repair / certification-lapse concern | Drafts a safety-concern flag; ALWAYS escalates to a human, unconditionally. Once approved, `other-equipment-repair.notify` sends the notice (mail + phone, mock only -- see `Actuation`) to the equipment/work-order's repair-technician/shop-safety-officer contact roster. |
| `:order-supplies` | replacement-parts procurement proposal | Drafts a supply-order proposal. Escalates above a cost threshold or below the confidence floor; may auto-commit at phase 3 otherwise. |

**Legal basis is data, not code** -- `src/other_equipment_repair/facts.cljc`'s
`catalog` is the per-jurisdiction EDN source-of-truth the governor checks
every `:schedule-repair-operation` proposal against (JPN/USA/DEU seeded,
the same honest-coverage convention `installation.facts`/`demolition.
facts`/`construction.facts` use; DEU stands in for the EU):

| Jurisdiction | Pre-repair hazard/energy-control legal basis |
|---|---|
| 🇯🇵 Japan | 労働安全衛生規則（昭和47年労働省令第32号）第107条（掃除等の場合の運転停止等 -- 機械の掃除・給油・検査・修理・調整の作業を行う場合に労働者への危険のおそれがあるときは運転を停止し、起動装置に錠を掛け表示板を取り付ける義務） -- [e-Gov](https://laws.e-gov.go.jp/law/347M50002000032) |
| 🇺🇸 USA | OSHA 29 CFR 1910.147 (The Control of Hazardous Energy -- Lockout/Tagout) -- [osha.gov](https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.147) |
| 🇪🇺 EU (DEU proxy) | Betriebssicherheitsverordnung (BetrSichV) §10 (Instandhaltung und Änderung von Arbeitsmitteln), grounded in Directive 2009/104/EC (minimum safety and health requirements for the use of work equipment by workers) -- [gesetze-im-internet.de](https://www.gesetze-im-internet.de/betrsichv_2015/__10.html) / [EUR-Lex](https://eur-lex.europa.eu/eli/dir/2009/104/oj/eng) |

All three seeded jurisdictions are honestly `:qualitative` here -- every
source is a PROCEDURAL requirement (stop the machine, lock/tag it out,
use qualified/designated personnel) with no fixed numeric advance-notice-
days count this actor could independently verify. `other-equipment-
repair.facts/notification-lead-insufficient?` reports `:qualitative` for
every covered jurisdiction rather than fabricating a number. See
`other-equipment-repair.facts` ns docstring for the full honesty
discipline.

**Governor -- six HARD checks, ALL un-overridable by human approval:**
unknown op (outside the closed 4-op allowlist), `:effect` not
`:propose`, forbidden action class (repair-equipment/diagnostic-tool-
control / direct-actuation / return-to-service-sign-off markers),
equipment/work-order not independently verified/registered, legal-basis
missing, unresolved safety concern on file. See `other-equipment-repair.
governor` ns docstring for the full enumeration, rationale and real-law
citations behind each.

## Actuation

This actor performs **no real-world actuation** -- every committed
record carries `:effect :propose` (see `other-equipment-repair.governor`
ns docstring). `:flag-safety-concern` NEVER auto-commits at any phase --
it always needs a human sign-off, even when the governor is completely
clean (`other-equipment-repair.phase` ns docstring 'Actuation' section,
`other-equipment-repair.governor`'s `high-stakes` set).

**UNLIKE `cloud-itonami-isic-3320`'s `:schedule-installation-operation`
(which always escalates because it coordinates potential heavy-lift/
rigging-equipment dispatch), this actor's `:schedule-repair-operation`
MAY auto-commit at phase 3** when the governor is clean (equipment
independently verified, legal-basis on file, no unresolved safety
concern) -- ISIC 3319 is the residual, lower-average-physical-risk repair
class (furniture, sporting/recreational goods, musical instruments,
miscellaneous equipment n.e.c.), and this actor's narrower escalation
surface reflects that. `:log-repair-record` (pure data logging) and
`:order-supplies` BELOW the cost threshold
(`other-equipment-repair.governor/supply-order-cost-threshold-usd`) also
MAY auto-commit at phase 3 when the governor is clean.

This build also deliberately ships **NO JVM-only interop anywhere in
`src/`** -- `other-equipment-repair.notify` ships only the deterministic
mock `Notifier` (no real Resend/Twilio transport), per this workspace's
cljs-first `.cljc` runtime-priority rule. A real transport can be added
later behind the same protocol via a portable HTTP client without
changing this actor's shape.

```bash
clojure -M:dev:run    # demo: full coordination episode + every HARD hold
clojure -M:dev:test   # test suite
clojure -M:lint       # clj-kondo, errors fail
```

## License

AGPL-3.0-or-later.
