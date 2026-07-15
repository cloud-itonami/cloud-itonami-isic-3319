# Contributing

`cloud-itonami-isic-3319` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

The capability layer lives in `kotoba-lang/*` libraries. This repo holds
the business blueprint, the Repair Advisor/Governor actor and operator
contracts.

```bash
clojure -M:dev:test   # run the full test suite
clojure -M:lint       # clj-kondo, errors fail
```

## Rules

- Do not commit real equipment/work-order, customer, personal or
  credential data.
- Keep every proposal behind the Repair Governor, and keep every
  proposal's `:effect` `:propose` -- never introduce a real-world
  actuation effect into this actor.
- Never extend this actor's op-allowlist, advisor or governor to permit
  repair-equipment/diagnostic-tool control or return-to-service sign-off
  authority. That is a hard scope boundary, not a rollout milestone --
  proposals to relax it should be rejected, not merged.
- Treat workflows as high-risk: add tests for governor hard-checks
  (unknown op, non-`:propose` effect, forbidden action class, equipment
  verification, legal basis, unresolved safety concerns), phase gating
  and audit-ledger integrity.
- Extending `other-equipment-repair.facts` coverage to a new jurisdiction
  requires a real, citable official source -- never fabricate a
  jurisdiction's requirements.
- Do not add JVM-only interop to `src/` (no `java.net.http`, no other
  `java.*`/`System/*` calls) -- this build's cljs-first `.cljc`
  runtime-priority mandate. A real notification transport, if ever
  added, must go behind `other-equipment-repair.notify/Notifier` via a
  portable (cljs/nbb) HTTP client.
- Document any new business-model or operator assumption in `docs/` or
  this README.

## Pull Requests

PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
