(ns hazmat.policy-contract-test
  (:require [clojure.test :refer [deftest is]]
            [hazmat.policy :as policy]
            [hazmat.store :as store]))

(def test-clients
  [{:id "client-001" :name "Generator A" :registered? true :active? true}
   {:id "client-002" :name "Generator B" :registered? true :active? false}])

(def test-facilities
  [{:id "facility-001" :name "Incinerator A" :licensed? true
    :methods #{:incineration} :daily-capacity-kg {:incineration 5000M}}
   {:id "facility-002" :name "Landfill B" :licensed? true
    :methods #{:secure-landfill} :daily-capacity-kg {:secure-landfill 3000M}}])

(def test-contracts
  [{:tenant "client-001" :active? true :tier :tier/basic}])

(def test-store (store/mem-store test-clients test-facilities test-contracts))

;; ───────────────────────── hard-gate tests ─────────────────────────

(deftest hazard-gate-rejects-non-hazardous
  "CRITICAL: 3812 ONLY accepts hazard-flagged streams. No hazard = HARD reject."
  (let [request {:op :intake-collection-order}
        context {:actor-role :collection-dispatcher}
        proposal-no-flags {:source {:class :collector-visual-inspection}
                           :value {:hazard-flags #{}}}
        proposal-nil-flags {:source {:class :collector-visual-inspection}
                            :value {:hazard-flags nil}}]
    ;; Empty flags = HARD violation
    (is (:hard? (policy/check request context proposal-no-flags test-store)))
    ;; Nil flags = HARD violation
    (is (:hard? (policy/check request context proposal-nil-flags test-store)))))

(deftest hazard-gate-accepts-hazardous
  "When hazard flags are present, hazard-gate passes."
  (let [request {:op :intake-collection-order}
        context {:actor-role :collection-dispatcher :client-id "client-001"}
        proposal {:source {:class :collector-visual-inspection}
                  :value {:hazard-flags #{:corrosive :reactive}
                          :facility-id "facility-001" :waste-class :corrosive
                          :estimated-kg 100M}}]
    (let [verdict (policy/check request context proposal test-store)]
      ;; No hazard-gate violation (but may have other issues like low confidence)
      (is (not (some #(= :hazard-gate (:rule %)) (:violations verdict)))))))

(deftest rbac-violations
  "Role-based access control."
  (let [request-intake {:op :intake-collection-order}
        request-dispatch {:op :schedule-collection-dispatch}
        context-bad-role {:actor-role :unknown-role}
        proposal {:source {:class :collector-visual-inspection}
                  :value {:hazard-flags #{:corrosive}}}]
    ;; Unknown role → rbac violation
    (is (some #(= :rbac (:rule %))
              (:violations (policy/check request-intake context-bad-role proposal test-store))))
    ;; Correct role → no rbac violation
    (is (not (some #(= :rbac (:rule %))
                   (:violations (policy/check request-intake
                                              {:actor-role :collection-dispatcher}
                                              proposal test-store)))))))

(deftest facility-capacity-violations
  "Facility treatment capacity gate."
  (let [context {:actor-role :collection-dispatcher :client-id "client-001"}
        ;; Proposal that exceeds facility capacity
        proposal-over-capacity
        {:source {:class :collector-visual-inspection}
         :value {:hazard-flags #{:corrosive} :facility-id "facility-001"
                 :waste-class :corrosive :treatment-method :incineration
                 :estimated-kg 6000M}}  ;; exceeds 5000M capacity
        request {:op :intake-collection-order}]
    (let [verdict (policy/check request context proposal-over-capacity test-store)]
      (is (some #(= :facility-treatment-capacity-gate (:rule %))
                (:violations verdict))))))

(deftest source-provenance-gate-violations
  "Source-provenance-gate rejects unknown source classes."
  (let [request {:op :intake-collection-order}
        context {:actor-role :collection-dispatcher :client-id "client-001"}
        proposal-bad-source {:source {:class :inference :ref "guess"}
                             :value {:hazard-flags #{:corrosive} :facility-id "facility-001"
                                     :waste-class :corrosive :estimated-kg 100M}}
        proposal-no-source {:source nil
                            :value {:hazard-flags #{:corrosive} :facility-id "facility-001"
                                    :waste-class :corrosive :estimated-kg 100M}}]
    ;; Bad source class → violation
    (is (some #(= :source-provenance-gate (:rule %))
              (:violations (policy/check request context proposal-bad-source test-store))))
    ;; No source → violation
    (is (some #(= :source-provenance-gate (:rule %))
              (:violations (policy/check request context proposal-no-source test-store))))))

(deftest client-manifest-register-violations
  "Client must be registered and active."
  (let [request {:op :intake-collection-order}
        proposal {:source {:class :collector-visual-inspection}
                  :value {:hazard-flags #{:corrosive} :facility-id "facility-001"
                          :waste-class :corrosive :estimated-kg 100M}}]
    ;; Unregistered client
    (is (some #(= :client-manifest-register (:rule %))
              (:violations (policy/check request {:actor-role :collection-dispatcher :client-id "unknown"}
                                         proposal test-store))))
    ;; Inactive client
    (is (some #(= :client-manifest-register (:rule %))
              (:violations (policy/check request {:actor-role :collection-dispatcher :client-id "client-002"}
                                         proposal test-store))))))

;; ───────────────────────── soft-gate tests (escalation) ─────────────────────────

(deftest low-confidence-escalation
  "Low confidence → escalate (soft gate, human decides)."
  (let [request {:op :intake-collection-order}
        context {:actor-role :collection-dispatcher :client-id "client-001"}
        proposal {:source {:class :collector-visual-inspection}
                  :value {:hazard-flags #{:corrosive} :facility-id "facility-001"
                          :waste-class :corrosive :treatment-method :incineration
                          :estimated-kg 100M}
                  :confidence 0.5}]  ;; below 0.65 floor
    (let [verdict (policy/check request context proposal test-store)]
      (is (and (not (:hard? verdict)) (:escalate? verdict))))))

(deftest bulk-volume-escalation
  "Bulk intake → escalate (soft gate, human decides)."
  (let [request {:op :intake-collection-order}
        context {:actor-role :collection-dispatcher :client-id "client-001"}
        proposal {:source {:class :collector-visual-inspection}
                  :value {:hazard-flags #{:corrosive} :facility-id "facility-001"
                          :waste-class :corrosive :treatment-method :incineration
                          :estimated-kg 3000M}  ;; exceeds 2000M bulk threshold
                  :confidence 0.9}]
    (let [verdict (policy/check request context proposal test-store)]
      (is (and (not (:hard? verdict)) (:escalate? verdict) (:bulk? verdict))))))

(deftest hazard-concern-always-escalates
  "Hazard concern flag operation always escalates (human sign-off required)."
  (let [request {:op :flag-hazard-concern}
        context {:actor-role :hazard-inspector :client-id "client-001"}
        proposal {:value {:intake-id "intake-001" :concern-type :contamination}
                  :confidence 1.0}]
    (let [verdict (policy/check request context proposal test-store)]
      (is (:hazard-concern? verdict))
      (is (:escalate? verdict)))))

;; ───────────────────────── ok? verdict tests ─────────────────────────

(deftest clean-verdict-when-no-violations
  "Verdict is :ok? only when no hard violations, not escalating."
  (let [request {:op :intake-collection-order}
        context {:actor-role :collection-dispatcher :client-id "client-001"}
        proposal {:source {:class :collector-visual-inspection}
                  :value {:hazard-flags #{:corrosive} :facility-id "facility-001"
                          :waste-class :corrosive :treatment-method :incineration
                          :estimated-kg 100M}  ;; under bulk threshold
                  :confidence 0.95}]  ;; above confidence floor
    (let [verdict (policy/check request context proposal test-store)]
      (is (:ok? verdict))
      (is (not (:hard? verdict)))
      (is (not (:escalate? verdict))))))
