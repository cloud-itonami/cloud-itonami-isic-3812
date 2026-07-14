(ns hazmat.llm-test
  (:require [clojure.test :refer [deftest is]]
            [hazmat.llm :as llm]
            [hazmat.store :as store]))

(def test-store (store/mem-store [] [] []))

(deftest mock-advisor-intake-collection-order
  (let [advisor (llm/mock-advisor)
        request {:op :intake-collection-order
                 :subject "intake-001"
                 :id "intake-001" :client-id "client-001" :facility-id "facility-001"
                 :waste-class :corrosive :hazard-flags #{:corrosive}
                 :estimated-kg 100M :scheduled-date "2026-07-14"
                 :source {:class :collector-visual-inspection :ref "US-RCRA"}}
        proposal (llm/-advise advisor test-store request)]
    (is (= :intake-upsert (:effect proposal)))
    (is (string? (:summary proposal)))
    (is (pos? (:confidence proposal)))
    (is (= "intake-001" (get-in proposal [:value :id])))))

(deftest mock-advisor-unsourced-intake
  (let [advisor (llm/mock-advisor)
        request {:op :intake-collection-order
                 :subject "intake-002"
                 :id "intake-002" :client-id "client-001" :facility-id "facility-001"
                 :waste-class :corrosive :hazard-flags #{:corrosive}
                 :estimated-kg 100M :unsourced? true}
        proposal (llm/-advise advisor test-store request)]
    ;; Unsourced proposal still returns high confidence (the governor gate
    ;; will catch it, not the LLM)
    (is (>= (:confidence proposal) 0.8))
    (is (nil? (:source proposal)))))

(deftest mock-advisor-schedule-collection-dispatch
  (let [advisor (llm/mock-advisor)
        request {:op :schedule-collection-dispatch
                 :subject "schedule-001"
                 :intake-id "intake-001" :treatment-method :incineration
                 :estimated-kg 100M :source {:class :collector-visual-inspection}}
        proposal (llm/-advise advisor test-store request)]
    (is (= :schedule-upsert (:effect proposal)))
    (is (string? (:summary proposal)))))

(deftest mock-advisor-manifest-record
  (let [advisor (llm/mock-advisor)
        request {:op :manifest-record
                 :subject "manifest-001"
                 :intake-id "intake-001" :actual-kg 95M :waste-class :corrosive
                 :source {:class :facility-intake-scan}}
        proposal (llm/-advise advisor test-store request)]
    (is (= :manifest-record (:effect proposal)))
    (is (string? (:summary proposal)))))

(deftest mock-advisor-coordinate-disposal
  (let [advisor (llm/mock-advisor)
        request {:op :coordinate-disposal-facility
                 :subject "disposal-coord-001"
                 :intake-id "intake-001" :facility-id "facility-001"
                 :treatment-method :incineration}
        proposal (llm/-advise advisor test-store request)]
    (is (= :disposal-coordination-propose (:effect proposal)))
    (is (= 0.9 (:confidence proposal)))))

(deftest mock-advisor-flag-hazard-concern
  (let [advisor (llm/mock-advisor)
        request {:op :flag-hazard-concern
                 :subject "concern-001"
                 :intake-id "intake-001" :concern-type :contamination
                 :description "Suspected cross-contamination during transport"}
        proposal (llm/-advise advisor test-store request)]
    (is (= :hazard-concern-flag (:effect proposal)))
    (is (= 1.0 (:confidence proposal)))
    (is (string? (:summary proposal)))))

(deftest mock-advisor-unknown-operation
  (let [advisor (llm/mock-advisor)
        request {:op :unknown-operation :subject "unknown-001"}
        proposal (llm/-advise advisor test-store request)]
    (is (nil? (:effect proposal)))
    (is (= 0.0 (:confidence proposal)))))

(deftest trace-audit-entry
  (let [request {:op :intake-collection-order :subject "intake-001"}
        proposal {:summary "test summary" :confidence 0.9 :cites [:field1 :field2]}
        trace (llm/trace request proposal)]
    (is (= :llm-proposal (:t trace)))
    (is (= :intake-collection-order (:op trace)))
    (is (= "intake-001" (:subject trace)))
    (is (= "test summary" (:proposal-summary trace)))
    (is (= 0.9 (:confidence trace)))))
