# Governance

`cloud-itonami-3812` is an OSS open-business blueprint for community
hazardous waste collection, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Hazardous Waste Governor remains independent of the advisor.
- hard policy violations (out-of-scope manifest, unmanifested transport, evidenceless chain-of-custody report) cannot be overridden by human approval.
- every dispatch, sign-off, manifest and chain-of-custody path is auditable.
- sensitive generator and manifest data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or manifest-scope checks
- mishandling generator or manifest data
- misrepresenting certification status
- failing to respond to safety or environmental incidents
