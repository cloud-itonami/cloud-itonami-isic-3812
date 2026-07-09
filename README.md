# cloud-itonami-3812

Open Business Blueprint for **ISIC Rev.5 3812**: collection of hazardous
waste (industrial, medical and chemical waste collection under contract).

This repository designs a forkable OSS business for community hazardous
waste collection: waste classification and manifest management,
robotics-assisted collection and transport, and chain-of-custody
reporting — run by a qualified operator so a waste hauler keeps its own
manifest and chain-of-custody records instead of renting a closed
compliance platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (collection, transport,
telemetry-monitored containment) operate under an actor that proposes
actions and an independent **Hazardous Waste Governor** that gates them.
The governor never dispatches hardware itself; `:high`/`:safety-critical`
actions (any transport of waste without a verified manifest, any
treatment outside a verified permit scope) require human sign-off.

## Core Contract

```text
intake + identity + waste classification + collection/transport mission
        |
        v
Waste Advisor -> Hazardous Waste Governor -> manifest, dispatch, chain-of-custody report, or human approval
        |
        v
robot actions (gated) + manifest record + chain-of-custody report + audit ledger
```

No automated advice can dispatch a robot action the governor refuses,
issue a manifest outside its verified classification scope, or publish a
chain-of-custody report without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `3812`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
