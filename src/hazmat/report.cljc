(ns hazmat.report
  "Report generation and disclosure. Clients query intake/manifest/disposal
  records; the disclosure-tier gate ensures they only see columns their
  contract permits.

  The DISCLOSURE DECISION is in `src/hazmat/report_disclosure.kotoba`; this
  namespace is the host side (reading the client's billing contract out of the
  Store, the keyword ↔ code translation, and the map a caller consumes) and the
  oracle the decision is checked against by
  `test/hazmat/report_kotoba_parity_test.clj`. Keywords do not cross that
  boundary and tier codes are ordered by breadth (basic 0 < detailed 1 <
  audit 2), which is what lets \"a wider tier never discloses less\" be stated
  as arithmetic.

  2026-08-30: `generate-report` took a `_store` it never read and a `columns`
  list it echoed straight back, so a `:tier/basic` contract asking for
  `:hazard-flags` — the hazard classification, on a HAZARDOUS-waste actor —
  was answered with `:hazard-flags`, which `hazmat.policy` names as
  over-disclosure. Only the empty `:records` kept that from being a leak, and
  an empty result set is not an access control. The tier now comes from the
  Store contract and the gate is enforced.

  RECORDED GAP: `:records` is still `[]`. No Store implementation projects rows
  for a report query; the gate is written and tested before there are rows
  rather than in the same commit that first produces them."
  (:require [hazmat.policy :as policy]
            [hazmat.store :as store]))

(def tier-order
  "The declared disclosure tiers, ordered by breadth. The index IS the tier
  code the .kotoba gate compares; `hazmat.policy/disclosure-tier-columns`
  builds these sets by nesting, so the order here is the order there."
  [:tier/basic :tier/detailed :tier/audit])

(def tier-unlicensed
  "Below every column minimum, so it discloses nothing. A tier the host cannot
  establish — no contract row, an inactive contract, or a tier outside
  `tier-order` — arrives here. A disclosure tier that cannot be read is not
  evidence of entitlement; cf. `hazmat.policy/bulk?`."
  -1)

(def column-unknown
  "Above every tier, so a column in no tier's set is refused by the ordinary
  comparison rather than by a branch that could be dropped."
  99)

(defn tier-code
  "Disclosure tier keyword → the code the gate compares."
  [tier]
  (or (first (keep-indexed (fn [i t] (when (= t tier) i)) tier-order))
      tier-unlicensed))

(defn tier-name
  "Tier code → the word that appears in a refusal. `(name :tier/basic)`."
  [code]
  (if-let [t (get tier-order code)] (name t) "unlicensed"))

(defn column-min-tier
  "The narrowest declared tier whose column set contains `column`, as a code,
  or `column-unknown` when no tier's set contains it.

  Derived from `hazmat.policy/disclosure-tier-columns` rather than from a
  second copy of the catalog, so the parity test compares the .kotoba gate
  against the policy the governor actually declares."
  [column]
  (or (first (keep-indexed
              (fn [i t] (when (contains? (get policy/disclosure-tier-columns t #{}) column) i))
              tier-order))
      column-unknown))

(defn column-known? [column]
  (< (column-min-tier column) column-unknown))

(defn disclose?
  "May a contract at `tier-code` see `column`? Monotone in the code by
  construction: the code only ever stands on the left of a `>=`."
  [code column]
  (>= code (column-min-tier column)))

(defn refusal
  "Why `column` was withheld from a contract at `tier-code`, or nil when it was
  not. The two reasons are kept apart: asking for something this contract has
  not bought is a different event from asking for a column that does not
  exist."
  [code column]
  (cond
    (disclose? code column) nil
    (column-known? column)
    (str "column " (name column) " requires disclosure tier "
         (tier-name (column-min-tier column))
         "; this contract holds " (tier-name code))
    :else
    (str "column " (name column) " is not in the disclosure catalog")))

(defn report-summary [client-id]
  (str "report for client " client-id))

(defn contract-tier-code
  "The tier code a Store contract entitles this client to. Ambient authority —
  the Store is a MemStore, a Datomic connection or kotoba-server depending on
  deployment — so it lives here and not in the gate. Fails closed: a missing
  contract, an inactive one, or a tier outside `tier-order` is
  `tier-unlicensed`."
  [st client-id]
  (let [c (store/contract st client-id)]
    (if (and c (:active? c)) (tier-code (:tier c)) tier-unlicensed)))

(defn generate-report
  "Draft a client-facing report of intake/manifest records filtered to the
  client's disclosure tier.

  Returns {:summary str :tier code :columns [granted] :refused [{:column
  :reason}] :records []}. Columns the contract does not entitle the client to
  are absent from `:columns` and named, with a reason, in `:refused`."
  [st client-id columns]
  (let [code (contract-tier-code st client-id)]
    {:summary  (report-summary client-id)
     :tier     code
     :columns  (into [] (filter #(disclose? code %)) columns)
     :refused  (into [] (keep (fn [c] (when-let [r (refusal code c)]
                                        {:column c :reason r})))
                     columns)
     :records  []}))
