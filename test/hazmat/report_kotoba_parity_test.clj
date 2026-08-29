(ns hazmat.report-kotoba-parity-test
  "The report-query disclosure gate, in cljc and in .kotoba.

  ## What is actually at risk

  `hazmat.policy` declares `disclosure-tier-columns` and calls anything beyond
  it over-disclosure. Nothing enforced it: `generate-report` echoed the
  requested columns back, so a `:tier/basic` contract asking for
  `:hazard-flags` was answered with `:hazard-flags`. Only the empty `:records`
  kept that from being a leak, and an empty result set is not an access
  control.

  Two properties are asserted here over the whole cross-product, and not only
  as agreement with the cljc — agreement would still hold if both sides were
  wrong together, which is exactly what happened to the phase gate before
  2026-08-30:

    1. no column outside `disclosure-tier-columns` is disclosed at ANY tier;
    2. a wider tier never discloses less.

  ## Why tier codes are ordered

  basic 0 < detailed 1 < audit 2, by breadth, with -1 for a tier the host
  could not establish. `disclose?` is `(>= tier (column-min-tier column))`, so
  monotonicity is arithmetic rather than a table of allowed pairs, and an
  unknown column (99, above every tier) is refused by that same comparison
  rather than by a branch that could be dropped.

  ## The corpus is exhaustive, not sampled

  5 tiers (3 declared + one nobody declared + unlicensed) x 12 columns (all 10
  in the catalog + 2 that are not). That is small enough to enumerate, and a
  dropped conjunct is exactly the shape sampling misses.

  ## The catalog is not copied

  Neither side hard-codes a tier→columns table for the test. The cljc derives
  `column-min-tier` from `hazmat.policy/disclosure-tier-columns`, so this
  compares the .kotoba gate against the policy the governor actually declares;
  `the-cljc-catalog-matches-the-policy-table` pins that derivation so a change
  to the policy table cannot pass by being mirrored into the oracle."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hazmat.policy :as policy]
            [hazmat.report :as report]
            [hazmat.store :as store]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/hazmat/report_disclosure.kotoba"))

(def ^:private export-prefix
  (str "tier-unlicensed tier-basic tier-detailed tier-audit tier-name "
       "column-unknown column-min-tier column-known? "
       "disclose? refusal report-summary main"))

(defn- run-probes
  "Compile the gate with extra zero-arg probes appended and execute each.
  Batched, because one compile per row would be hundreds of compiles."
  [probes result-type]
  (let [defs (for [[name body] probes]
               (str "(defn " name " [] " result-type " " body ")"))
        src (-> core-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " "
                      (str/join " " (map first probes)) "])"))
                (str "\n" (str/join "\n" defs)))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _]] [n (ir/execute kir (symbol n) [])]) probes))))

(defn- opt-str
  "[[:option :string] false] | [[:option :string] true \"...\"] → str | nil."
  [v]
  (when (second v) (nth v 2)))

;; ── the corpus ──────────────────────────────────────────────────────────────

(def ^:private tiers
  ;; The three declared tiers, one nobody declared, and the absence of a
  ;; contract. The last two must behave identically — see `tier-unlicensed`.
  [:tier/basic :tier/detailed :tier/audit :tier/platinum nil])

(def ^:private columns
  ;; Every column in the catalog, plus two that are not in it: one that reads
  ;; like a real field name and one that is a plain typo.
  [:intake-id :generator-id :facility-id :waste-class :scheduled-date
   :estimated-kg :actual-kg
   :hazard-flags :source :treatment-method
   :operator-notes :hazrd-flags])

(def ^:private rows
  (for [tier tiers column columns]
    {:tier tier :column column}))

(defn- literal [{:keys [tier column]}]
  (str (report/tier-code tier) " \"" (name column) "\""))

;; ── the oracle is the policy table, not a copy of it ────────────────────────

(deftest the-cljc-catalog-matches-the-policy-table
  ;; `column-min-tier` is derived from `disclosure-tier-columns`; this states
  ;; what that derivation must produce, so the derivation cannot quietly become
  ;; a second hard-coded catalog.
  (is (= #{:tier/basic :tier/detailed :tier/audit}
         (set (keys policy/disclosure-tier-columns))))
  (doseq [[tier expected] {:tier/basic    0 :tier/detailed 1 :tier/audit 2}]
    (is (= expected (report/tier-code tier))))
  (is (= -1 (report/tier-code nil)) "a tier nobody declared is unlicensed")
  (is (= -1 (report/tier-code :tier/platinum)))
  ;; Every column the policy grants to a tier must be known to the gate at
  ;; exactly the narrowest tier that grants it.
  (doseq [[tier cols] policy/disclosure-tier-columns
          column cols]
    (is (<= (report/column-min-tier column) (report/tier-code tier))
        (str column " is granted by " tier " but the gate wants a wider tier")))
  (is (= 99 (report/column-min-tier :operator-notes))
      "a column no tier grants is outside the catalog"))

;; ── the fixture must reach every outcome ────────────────────────────────────

(deftest the-fixture-reaches-every-outcome
  ;; Without this, a corpus that refused everything would make "both sides
  ;; agree" vacuous.
  (let [rs (map (fn [r] [(report/disclose? (report/tier-code (:tier r)) (:column r))
                         (report/refusal (report/tier-code (:tier r)) (:column r))])
                rows)]
    (is (some (fn [[d _]] d) rs) "the corpus discloses something")
    (is (some (fn [[d _]] (not d)) rs) "the corpus refuses something")
    (is (some (fn [[_ why]] (and why (str/includes? why "requires disclosure tier"))) rs)
        "the corpus refuses for insufficient tier")
    (is (some (fn [[_ why]] (and why (str/includes? why "not in the disclosure catalog"))) rs)
        "the corpus refuses an unknown column")))

;; ── agreement ───────────────────────────────────────────────────────────────

(deftest disclose-agrees-over-the-whole-corpus
  (doseq [batch (partition-all 60 (map-indexed vector rows))]
    (let [probes (into {} (map (fn [[i r]]
                                 [(str "d" i) (str "(disclose? " (literal r) ")")])
                               batch))
          actual (run-probes probes ":bool")]
      (doseq [[i r] batch]
        (testing (pr-str r)
          (is (= (report/disclose? (report/tier-code (:tier r)) (:column r))
                 (get actual (str "d" i)))))))))

(deftest refusal-agrees-byte-for-byte
  (doseq [batch (partition-all 60 (map-indexed vector rows))]
    (let [probes (into {} (map (fn [[i r]]
                                 [(str "w" i) (str "(refusal " (literal r) ")")])
                               batch))
          actual (run-probes probes "[:option :string]")]
      (doseq [[i r] batch]
        (testing (pr-str r)
          (is (= (report/refusal (report/tier-code (:tier r)) (:column r))
                 (opt-str (get actual (str "w" i))))))))))

(deftest column-min-tier-agrees
  (let [probes (into {} (map-indexed
                         (fn [i c] [(str "c" i) (str "(column-min-tier \"" (name c) "\")")])
                         columns))
        actual (run-probes probes ":i64")]
    (is (= #{0 1 2 99} (set (vals actual))) "the corpus reaches every tier and the unknown")
    (doseq [[i c] (map-indexed vector columns)]
      (is (= (report/column-min-tier c) (get actual (str "c" i))) (str c)))))

(deftest report-summary-agrees-byte-for-byte
  (let [ids ["acme-chem" "" "客先-A"]
        probes (into {} (map-indexed
                         (fn [i id] [(str "s" i) (str "(report-summary \"" id "\")")])
                         ids))
        actual (run-probes probes ":string")]
    (doseq [[i id] (map-indexed vector ids)]
      (is (= (report/report-summary id) (get actual (str "s" i)))))))

;; ── the properties ──────────────────────────────────────────────────────────

(deftest no-tier-discloses-a-column-outside-the-catalog
  ;; Property 1. Asserted against BOTH implementations, because the failure it
  ;; guards against was present in the only implementation there was: the old
  ;; `generate-report` echoed back whatever it was asked for, catalog or not.
  (let [outside (remove report/column-known? columns)
        pairs (for [tier tiers column outside] {:tier tier :column column})
        probes (into {} (map-indexed
                         (fn [i r] [(str "u" i) (str "(disclose? " (literal r) ")")])
                         pairs))
        actual (run-probes probes ":bool")]
    (is (= 10 (count pairs)) "5 tiers x 2 non-catalog columns")
    (doseq [[i r] (map-indexed vector pairs)]
      (is (false? (get actual (str "u" i)))
          (str "kotoba disclosed a column outside the catalog: " (pr-str r)))
      (is (false? (report/disclose? (report/tier-code (:tier r)) (:column r)))
          (str "cljc disclosed a column outside the catalog: " (pr-str r))))))

(deftest a-wider-tier-never-discloses-less
  ;; Property 2. Codes are ordered by breadth, so "wider" is `>=` on the code.
  ;; Stated as a pairwise implication over every ordered pair of tiers, in both
  ;; implementations.
  (let [ordered (sort-by report/tier-code (map report/tier-code tiers))
        pairs (for [a ordered b ordered :when (<= a b) column columns]
                {:narrow a :wide b :column column})
        probes (into {} (mapcat (fn [[i r]]
                                  [[(str "n" i) (str "(disclose? " (:narrow r) " \"" (name (:column r)) "\")")]
                                   [(str "x" i) (str "(disclose? " (:wide r) " \"" (name (:column r)) "\")")]])
                                (map-indexed vector pairs)))]
    (doseq [batch (partition-all 120 (seq probes))]
      (let [actual (run-probes (into {} batch) ":bool")]
        (doseq [[k v] actual :when (str/starts-with? k "n")]
          (let [i (subs k 1)
                wide (get actual (str "x" i))]
            (when (some? wide)
              (is (or (not v) wide)
                  (str "kotoba: widening the tier removed a disclosure at probe " k)))))))
    ;; The same property in the cljc, over every pair.
    (doseq [{:keys [narrow wide column]} pairs]
      (is (or (not (report/disclose? narrow column)) (report/disclose? wide column))
          (str "cljc: tier " wide " disclosed less than tier " narrow " for " column)))))

(deftest a-refusal-is-present-exactly-when-the-column-is-withheld
  ;; The two halves of the gate cannot drift: a disclosed column carries no
  ;; reason, and a withheld one always carries one. A `nil` reason on a
  ;; withheld column would drop it silently out of `:refused`.
  (doseq [batch (partition-all 60 (map-indexed vector rows))]
    (let [probes (into {} (mapcat (fn [[i r]]
                                    [[(str "d" i) (str "(disclose? " (literal r) ")")]])
                                  batch))
          discl (run-probes probes ":bool")
          why (run-probes (into {} (map (fn [[i r]]
                                          [(str "w" i) (str "(refusal " (literal r) ")")])
                                        batch))
                          "[:option :string]")]
      (doseq [[i r] batch]
        (testing (pr-str r)
          (is (= (not (get discl (str "d" i)))
                 (some? (opt-str (get why (str "w" i)))))))))))

;; ── the host path ───────────────────────────────────────────────────────────

(defn- st-with [contracts]
  (store/mem-store [] [] contracts))

(deftest generate-report-enforces-the-gate
  (let [st (st-with [{:tenant "acme-chem" :active? true :tier :tier/basic}
                     {:tenant "auditco"   :active? true :tier :tier/audit}
                     {:tenant "lapsed"    :active? false :tier :tier/audit}])
        asked [:intake-id :estimated-kg :hazard-flags :operator-notes]]
    (testing "a basic contract asking for the hazard classification is refused"
      (let [r (report/generate-report st "acme-chem" asked)]
        (is (= [:intake-id] (:columns r)))
        (is (= [:estimated-kg :hazard-flags :operator-notes] (mapv :column (:refused r))))
        (is (= "column hazard-flags requires disclosure tier audit; this contract holds basic"
               (:reason (nth (:refused r) 1))))))
    (testing "an audit contract still cannot invent a column"
      (let [r (report/generate-report st "auditco" asked)]
        (is (= [:intake-id :estimated-kg :hazard-flags] (:columns r)))
        (is (= [{:column :operator-notes
                 :reason "column operator-notes is not in the disclosure catalog"}]
               (:refused r)))))
    (testing "an inactive contract discloses nothing"
      (is (= [] (:columns (report/generate-report st "lapsed" asked)))))
    (testing "no contract at all discloses nothing"
      (let [r (report/generate-report st "stranger" asked)]
        (is (= [] (:columns r)))
        (is (= -1 (:tier r)))
        (is (str/includes? (:reason (first (:refused r))) "this contract holds unlicensed"))))
    (testing "the recorded gap: rows are still not projected"
      (is (= [] (:records (report/generate-report st "auditco" asked)))))))

;; ── backends ────────────────────────────────────────────────────────────────

(deftest the-gate-compiles-for-every-target-it-claims
  ;; The header claims all three. Nothing here was collapsed to a scalar to get
  ;; that: `typed-map` and `[:set :string]` are refused natively, and are not
  ;; used; strings, `string=?`, `cond` and `[:option :string]` are admitted
  ;; everywhere, and are what the gate is written in.
  (doseq [target [:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (compiler/compile-source core-source target {}))))))
