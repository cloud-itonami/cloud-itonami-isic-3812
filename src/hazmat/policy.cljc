(ns hazmat.policy
  "HazardousWasteGovernor — the independent compliance layer that earns the
  HazmatDispatch-LLM the right to intake a collection, record a manifest,
  or coordinate a disposal. The LLM has no notion of hazard verification
  discipline, a facility's licensed treatment capacity, or a client's
  manifesting entitlement, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD (intake/record/escalate nothing)
  — this actor's analog of `cloud-itonami-isic-6311`'s MarketDataGovernor
  and `cloud-itonami-isic-7820`'s StaffingGovernor.

  CRITICAL: This actor is HAZARDOUS-WASTE-ONLY. The inverse gate vs 3811:
  we MUST have hazard flags, and non-hazard streams are rejected (route to
  3811 non-hazard collection actor).

  Eight checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                        — does actor-role have permission for op?
    2. hazard-gate                 — does the intake carry REQUIRED hazard
                                    flags? (inverted vs 3811: MUST have
                                    hazard flags; no flags = HARD rejection,
                                    must route to non-hazard actor 3811)
    3. facility-treatment-capacity-gate — would this intake push the target
                                      facility's cumulative same-day treatment
                                      for this method past its permitted daily
                                      capacity? (HARD environmental limit)
    4. source-provenance-gate       — does the intake/manifest cite an
                                      allowed hazard-classification-basis
                                      source class?
    5. client-manifest-register     — is there a registered client manifest
                                      record, and is the client active?
    6. confidence floor             — LLM confidence below threshold →
                                      escalate.
    7. bulk-volume gate             — the intake's estimated weight exceeds
                                      the bulk threshold → always escalate,
                                      regardless of confidence.
    8. hazard-concern flags         — any :flag-hazard-concern operation
                                      ALWAYS escalates with human sign-off."
  (:require [clojure.set :as set]
            [hazmat.facts :as facts]
            [hazmat.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.65)

(def bulk-threshold-kg
  "An intake at or above this estimated weight always escalates to a human,
  even when governor-clean and high-confidence — an unusually large/bulk
  intake warrants a second look before dispatch commits capacity."
  2000M)

(def permissions
  "actor-role → set of operations it may perform."
  {:collection-dispatcher  #{:intake-collection-order}
   :collection-crew         #{:schedule-collection-dispatch :manifest-record}
   :hazard-inspector        #{:flag-hazard-concern}
   :disposal-coordinator    #{:coordinate-disposal-facility}
   :client-user             #{:report-query}})

(def disclosure-tier-columns
  "For `:report-query` — the columns each licensed client-disclosure tier
  may see. Anything beyond this is over-disclosure."
  (let [base #{:intake-id :generator-id :facility-id :waste-class :scheduled-date}
        detailed-extra #{:estimated-kg :actual-kg}
        audit-extra #{:hazard-flags :source :treatment-method}]
    {:tier/basic    base
     :tier/detailed (into base detailed-extra)
     :tier/audit    (into base (into detailed-extra audit-extra))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " does not have permission for " op)}]))

(defn- hazard-gate-violations
  "CRITICAL: This is a hazardous-waste actor. We ONLY accept streams with
  hazard flags. No flags = HARD rejection (route to non-hazard actor 3811)."
  [{:keys [op]} proposal]
  (when (#{:intake-collection-order :schedule-collection-dispatch} op)
    (let [flags (get-in proposal [:value :hazard-flags])]
      (cond
        (nil? flags)
        [{:rule :hazard-gate
          :detail "Intake missing required hazard flags (route to non-hazard collection 3811)"}]

        (empty? flags)
        [{:rule :hazard-gate
          :detail "Intake has no hazard flags (non-hazardous waste; route to collection 3811)"}]

        :else nil))))

(defn- facility-treatment-capacity-violations
  [{:keys [op]} proposal st]
  (when (#{:intake-collection-order :schedule-collection-dispatch} op)
    (let [{:keys [facility-id treatment-method estimated-kg]} (:value proposal)
          fac (store/disposal-facility st facility-id)
          cap (get-in fac [:daily-capacity-kg treatment-method])]
      (cond
        (nil? cap)
        [{:rule :facility-treatment-capacity-gate
          :detail (str "Facility " facility-id " is not licensed for treatment-method " treatment-method)}]

        (> (+ (store/facility-intake st facility-id treatment-method) (or estimated-kg 0M)) cap)
        [{:rule :facility-treatment-capacity-gate
          :detail (str "Facility " facility-id " treatment capacity exceeded for " treatment-method
                       ": current=" (store/facility-intake st facility-id treatment-method)
                       " + new=" estimated-kg " > cap=" cap)}]

        :else nil))))

(defn- source-provenance-violations
  [{:keys [op]} proposal]
  (when (contains? #{:intake-collection-order :schedule-collection-dispatch :manifest-record} op)
    (let [src (:source proposal)]
      (when (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-provenance-gate
          :detail (str "Hazard classification basis missing or not in allowed catalog: " (pr-str src))}]))))

(defn- client-manifest-register-violations
  [{:keys [op]} {:keys [client-id]} proposal st]
  (when (contains? #{:intake-collection-order :schedule-collection-dispatch :manifest-record} op)
    (let [c (when client-id (store/client st client-id))]
      (cond
        (nil? c)
        [{:rule :client-manifest-register :detail (str "Client " client-id " not registered")}]

        (not (:active? c))
        [{:rule :client-manifest-register :detail (str "Client " client-id " is not active")}]

        :else nil))))

(defn- bulk?
  [{:keys [op]} proposal]
  (and (contains? #{:intake-collection-order :schedule-collection-dispatch} op)
       (some-> (get-in proposal [:value :estimated-kg]) (>= bulk-threshold-kg))))

(defn check
  "Censors a HazmatDispatch-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :bulk? bool
    :hard? bool :hazard-concern? bool}.

   - :hard?       — at least one HARD violation (hazard-gate/facility-
                    treatment-capacity-gate/source-provenance-gate/
                    client-manifest-register). Forces HOLD; a human cannot
                    override.
   - :escalate?   — soft: low confidence, bulk-volume intake, OR a
                    hazard-concern flag. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (hazard-gate-violations request proposal)
                              (facility-treatment-capacity-violations request proposal st)
                              (source-provenance-violations request proposal)
                              (client-manifest-register-violations request context proposal st)))
        conf     (:confidence proposal 0.0)
        low?     (< conf confidence-floor)
        bulk?    (boolean (bulk? request proposal))
        hazard-concern? (= :flag-hazard-concern (:op request))
        hard?    (boolean (seq hard))]
    {:ok?              (and (not hard?) (not low?) (not bulk?) (not hazard-concern?))
     :violations       hard
     :confidence       conf
     :hard?            hard?
     :escalate?        (and (not hard?) (or low? bulk? hazard-concern?))
     :bulk?            bulk?
     :hazard-concern?  hazard-concern?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
