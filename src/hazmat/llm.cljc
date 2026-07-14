(ns hazmat.llm
  "HazmatDispatch-LLM client — the *contained intelligence node*.

  It verifies intake collection orders, drafts manifest records from
  collection-crew field input, proposes disposal facility coordination,
  and escalates hazard concerns. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields/source
  it cited), never a committed or disclosed record. Every output is
  censored downstream by `hazmat.policy` (the HazardousWasteGovernor)
  before anything touches the SSoT or coordinates with disposal operators.

  Like `cloud-itonami-isic-6311`'s MarketData-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the source-provenance gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str}|nil ; SCANNED by source-provenance
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the intake/manifest/coordination patch
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [hazmat.store :as store]))

(defn- propose-intake-collection-order
  "Intake collection order normalization — the LLM only verifies/normalizes
  the intake request (adds no new hazard-classification facts).
  `:unsourced?` injects the failure mode we must defend against: an intake
  arriving with no classification-basis citation — the HazardousWasteGovernor's
  source-provenance-gate must reject this outright."
  [_db {:keys [id client-id facility-id waste-class hazard-flags estimated-kg
              scheduled-date source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "hazmat intake: " client-id " → " facility-id " (" waste-class ")")
     :rationale "Hazard-classified intake order normalization. No new facts generated."
     :cites     [:client-id :facility-id :waste-class :hazard-flags :estimated-kg]
     :source    src
     :effect    :intake-upsert
     :value     {:id id :client-id client-id :facility-id facility-id
                 :waste-class waste-class :hazard-flags (or hazard-flags #{})
                 :estimated-kg estimated-kg :scheduled-date scheduled-date
                 :source src}
     ;; deliberately HIGH confidence even when unsourced? — proves the hard
     ;; hazard-gate and source-provenance gate do not care about confidence.
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-schedule-collection-dispatch
  "Schedule dispatch normalization from intake order."
  [_db {:keys [intake-id treatment-method estimated-kg source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "dispatch: intake " intake-id " → treatment=" treatment-method)
     :rationale "Collection dispatch scheduling for verified hazmat intake."
     :cites     [:intake-id :treatment-method :estimated-kg]
     :source    src
     :effect    :schedule-upsert
     :value     {:intake-id intake-id :treatment-method treatment-method
                 :estimated-kg estimated-kg :source src}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-manifest-record
  "Post-collection manifest draft from collection-crew field input."
  [_db {:keys [intake-id actual-kg waste-class source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "manifest: intake " intake-id " = " actual-kg "kg")
     :rationale "Post-collection actual weight record from field measurement."
     :cites     [:intake-id :actual-kg :waste-class]
     :source    src
     :effect    :manifest-record
     :value     {:intake-id intake-id :actual-kg actual-kg :waste-class waste-class :source src}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-dispose-coordinate
  "Propose coordination with a licensed disposal facility."
  [_db {:keys [intake-id facility-id treatment-method]}]
  {:summary   (str "dispose: coordinate " intake-id " → " facility-id)
    :rationale "Propose coordination with licensed disposal facility (actor does not issue disposal permits)."
    :cites     [:intake-id :facility-id :treatment-method]
    :source    nil
    :effect    :disposal-coordination-propose
    :value     {:intake-id intake-id :facility-id facility-id :treatment-method treatment-method}
    :confidence 0.9})

(defn- propose-flag-hazard-concern
  "Escalate a hazard handling/contamination concern."
  [_db {:keys [intake-id concern-type description]}]
  {:summary   (str "hazard concern: " concern-type " on intake " intake-id)
    :rationale "Human-escalated hazard handling/contamination concern — ALWAYS escalates."
    :cites     [:intake-id :concern-type]
    :source    nil
    :effect    :hazard-concern-flag
    :value     {:intake-id intake-id :concern-type concern-type :description description}
    :confidence 1.0})

(defprotocol Advisor
  "The LLM advisor interface."
  (-advise [advisor store request]
    "Return a proposal given a request and store."))

(defn mock-advisor
  "Deterministic mock advisor for testing/demo."
  []
  (reify Advisor
    (-advise [_ db {:keys [op] :as request}]
      (case op
        :intake-collection-order
        (propose-intake-collection-order db request)

        :schedule-collection-dispatch
        (propose-schedule-collection-dispatch db request)

        :manifest-record
        (propose-manifest-record db request)

        :coordinate-disposal-facility
        (propose-dispose-coordinate db request)

        :flag-hazard-concern
        (propose-flag-hazard-concern db request)

        ;; fallback
        {:summary "unknown operation"
         :rationale "operation not recognized"
         :cites []
         :source nil
         :effect nil
         :value nil
         :confidence 0.0}))))

(defn trace
  "Audit trail entry for an LLM inference."
  [request proposal]
  {:t :llm-proposal
   :op (:op request)
   :subject (:subject request)
   :proposal-summary (:summary proposal)
   :confidence (:confidence proposal)
   :cites (:cites proposal)})
