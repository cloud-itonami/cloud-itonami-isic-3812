# Contributing

`cloud-itonami-3812` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/robotics`. This repo holds the
business blueprint and operator contracts.

```bash
clojure -X:test
clojure -M:lint
```

## Rules
- Do not commit real generator, manifest or customer data.
- Keep robot dispatch, manifests and chain-of-custody reports behind the
  Hazardous Waste Governor.
- Treat collection/transport workflows as high-risk: add tests for
  robot-safety gating, classification scope, evidence, disclosure and
  audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
