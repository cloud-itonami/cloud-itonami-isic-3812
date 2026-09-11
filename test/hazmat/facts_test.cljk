(ns hazmat.facts-test
  (:require [clojure.test :refer [deftest is]]
            [hazmat.facts :as facts]))

(deftest hazard-classification-catalog-exists
  (is (pos? (count facts/hazard-classification-catalog)))
  (is (every? #(contains? % :id) facts/hazard-classification-catalog)))

(deftest disposal-treatment-catalog-exists
  (is (pos? (count facts/disposal-treatment-catalog)))
  (is (every? #(contains? % :id) facts/disposal-treatment-catalog)))

(deftest allowed-classes-are-complete
  (let [classes facts/allowed-hazard-classification-classes
        catalog-classes (into #{} (map :class facts/hazard-classification-catalog))]
    (is (= classes catalog-classes))))

(deftest class-allowed-check
  (is (facts/class-allowed? :collector-visual-inspection))
  (is (facts/class-allowed? :facility-intake-scan))
  (is (facts/class-allowed? :generator-self-declaration))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? :unverified-guess))))

(deftest coverage-report
  (let [cov (facts/coverage)]
    (is (pos? (:hazard-classification-framework-count cov)))
    (is (pos? (:disposal-treatment-method-count cov)))
    (is (pos? (count (:jurisdictions cov))))))
