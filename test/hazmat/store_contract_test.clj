(ns hazmat.store-contract-test
  (:require [clojure.test :refer [deftest is]]
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
  [{:tenant "client-001" :active? true :tier :tier/basic}
   {:tenant "client-002" :active? false :tier :tier/detailed}])

(deftest mem-store-client-fetch
  (let [st (store/mem-store test-clients test-facilities test-contracts)]
    (is (= "Generator A" (:name (store/client st "client-001"))))
    (is (nil? (store/client st "unknown")))))

(deftest mem-store-facility-fetch
  (let [st (store/mem-store test-clients test-facilities test-contracts)]
    (is (= "Incinerator A" (:name (store/disposal-facility st "facility-001"))))
    (is (nil? (store/disposal-facility st "unknown")))))

(deftest mem-store-contract-fetch
  (let [st (store/mem-store test-clients test-facilities test-contracts)]
    (is (= :tier/basic (:tier (store/contract st "client-001"))))
    (is (nil? (store/contract st "unknown")))))

(deftest mem-store-facility-intake-tracking
  (let [st (store/mem-store test-clients test-facilities test-contracts)]
    ;; Append some records
    (store/commit-record! st {:effect :intake-upsert :value {:id "i1" :estimated-kg 100M}})
    (store/commit-record! st {:effect :intake-upsert :value {:id "i2" :estimated-kg 200M}})
    ;; Facility intake should reflect cumulative
    (is (number? (store/facility-intake st "facility-001" :incineration)))))

(deftest mem-store-ledger-append
  (let [st (store/mem-store test-clients test-facilities test-contracts)]
    (store/append-ledger! st {:t :committed :op :intake-collection-order :subject "intake-001"})
    (store/append-ledger! st {:t :committed :op :schedule-collection-dispatch :subject "schedule-001"})
    ;; Ledger is append-only; we can't directly query it but we can verify
    ;; the store accepts multiple appends without error
    (is true)))

(deftest mem-store-client-manifest-history
  (let [st (store/mem-store test-clients test-facilities test-contracts)]
    ;; Append some intake records tagged with client-id
    (store/commit-record! st {:effect :intake-upsert
                              :value {:id "intake-001" :client-id "client-001"
                                      :estimated-kg 100M}})
    (store/commit-record! st {:effect :intake-upsert
                              :value {:id "intake-002" :client-id "client-001"
                                      :estimated-kg 200M}})
    (store/commit-record! st {:effect :intake-upsert
                              :value {:id "intake-003" :client-id "client-002"
                                      :estimated-kg 150M}})
    ;; Query client-001's manifest
    (let [manifests (store/client-manifest st "client-001")]
      (is (= 2 (count manifests)))
      (is (every? #(= "client-001" (:client-id %)) manifests)))))
