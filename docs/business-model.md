# Business Model: Community Hazardous Waste Collection

## Classification
- Repository: `cloud-itonami-3812`
- ISIC Rev.5: `3812` — collection of hazardous waste
- Social impact: public health, environmental protection, chain-of-custody

## Customer
- industrial, medical and laboratory generators needing licensed
  hazardous-waste collection
- municipalities needing hazardous-waste collection oversight
- treatment/disposal facilities needing verifiable intake manifests
- programs that cannot accept closed, unauditable manifest platforms

## Offer
- waste classification and manifest management
- robotics-assisted collection and transport
- chain-of-custody and disposal-confirmation records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per collection route/contract
- support retainer with SLA
- collection/transport robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (transport without a verified manifest,
  treatment outside a verified permit scope) require human sign-off
- a manifest cannot be issued outside its verified waste-classification
  scope
- chain-of-custody reports require source verification evidence
- sensitive generator and manifest data stays outside Git
