(ns hazmat.phase-test
  (:require [clojure.test :refer [deftest is]]
            [hazmat.phase :as phase]))

(deftest verdict-to-disposition-hard
  (let [verdict {:hard? true :escalate? false}]
    (is (= :hold (phase/verdict->disposition verdict)))))

(deftest verdict-to-disposition-escalate
  (let [verdict {:hard? false :escalate? true}]
    (is (= :escalate (phase/verdict->disposition verdict)))))

(deftest verdict-to-disposition-commit
  (let [verdict {:hard? false :escalate? false}]
    (is (= :commit (phase/verdict->disposition verdict)))))

(deftest phase-0-all-escalate
  (let [request {:value {:estimated-kg 100M}}
        result (phase/gate :phase-0-manual request :commit)]
    (is (= :escalate (:disposition result)))
    (is (string? (:reason result)))))

(deftest phase-1-small-intakes-commit
  (let [request-small {:value {:estimated-kg 400M}}
        request-large {:value {:estimated-kg 600M}}]
    ;; Small intake (<500kg) with clean verdict → commit
    (let [result (phase/gate :phase-1-review request-small :commit)]
      (is (= :commit (:disposition result))))
    ;; Large intake (≥500kg) with clean verdict → escalate
    (let [result (phase/gate :phase-1-review request-large :commit)]
      (is (= :escalate (:disposition result))))))

(deftest phase-1-respects-hold
  (let [request {:value {:estimated-kg 100M}}]
    ;; :hold disposition → stays :hold in phase 1
    (let [result (phase/gate :phase-1-review request :hold)]
      (is (= :hold (:disposition result))))))

(deftest phase-2-passthrough
  (let [request {:value {:estimated-kg 5000M}}]
    ;; Phase 2 is supervised — trusts governor verdict
    (is (= :commit (:disposition (phase/gate :phase-2-supervised request :commit))))
    (is (= :escalate (:disposition (phase/gate :phase-2-supervised request :escalate))))
    (is (= :hold (:disposition (phase/gate :phase-2-supervised request :hold))))))

(deftest phase-3-autonomous
  (let [request {:value {:estimated-kg 5000M}}]
    ;; Phase 3 is autonomous — same as phase 2 (future: optimization allowed)
    (is (= :commit (:disposition (phase/gate :phase-3-autonomous request :commit))))
    (is (= :escalate (:disposition (phase/gate :phase-3-autonomous request :escalate))))
    (is (= :hold (:disposition (phase/gate :phase-3-autonomous request :hold))))))

(deftest unknown-phase-defaults-to-phase-2
  (let [request {:value {:estimated-kg 100M}}]
    ;; Unknown phase → defaults to phase-2 behavior
    (is (= :commit (:disposition (phase/gate :phase-unknown request :commit))))))
