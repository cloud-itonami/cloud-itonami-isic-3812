(ns hazmat.phase-kotoba-parity-test
  "The rollout-phase gate, in cljc and in .kotoba.

  ## What is actually at risk

  `hazmat.policy` promises that a HARD violation \"forces HOLD; a human cannot
  override\", and `hazmat.operation` routes `:escalate` to `:request-approval`,
  where an approver saying `:approved` commits. So the gate handing back an
  `:escalate` in place of a `:hold` IS that override, however it is spelled.

  That is asserted here as a PROPERTY over the whole cross-product — the gate
  never returns a disposition less cautious than the one it was given — and not
  only as agreement with the cljc. Agreement would still hold if both sides
  were wrong together, which is exactly what happened before 2026-08-30: phase
  0 escalated unconditionally in the only implementation there was, and the
  suite passed.

  ## Why disposition codes are ordered

  commit 0 < escalate 1 < hold 2, by caution. `gate-disposition` returning a
  code `>=` the one it was handed is the whole invariant, stated as arithmetic
  rather than as a table of allowed pairs.

  ## The corpus is exhaustive, not sampled

  4 declared phases + 1 unknown, x 3 base dispositions, x weight-known/absent,
  x the weights around the phase-1 threshold. That is small enough to enumerate,
  and a conjunction with a dropped term is exactly the shape sampling misses.

  ## BigDecimal

  `phase.cljc` compares BigDecimal against `500M`; Kotoba has i64 / f32 / f64
  and no BigDecimal, so the core takes whole kilograms, floored, against an i64
  threshold. `floor(x) < n` iff `x < n` for integer n — the fractional weights
  in `weights` are here to MEASURE that rather than let the header assert it.
  `kg->i64` is where the conversion lives; when `phase.cljc` delegates to the
  shipped core instead of mirroring it, this is the function that moves into
  the bridge."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hazmat.phase :as phase]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/hazmat/phase_core.kotoba"))

(def ^:private export-prefix
  (str "disposition-commit disposition-escalate disposition-hold "
       "phase-0-manual phase-1-review phase-2-supervised phase-3-autonomous "
       "default-phase bulk-review-threshold-kg "
       "verdict-disposition effective-kg phase-1-auto-commit? "
       "gate-disposition gate-reason main"))

(def ^:private verdict-ty
  "[:record :phase/verdict [[:hard :bool] [:escalate :bool]]]")

(def ^:private gate-ty
  (str "[:record :phase/gate [[:phase :i64] [:disposition :i64] "
       "[:weight-known :bool] [:estimated-kg :i64]]]"))

(defn- run-probes
  "Compile the core with extra zero-arg probes appended and execute each.
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

;; ── translation: what the host establishes before the core is asked ─────────

(def ^:private disposition->code {:commit 0 :escalate 1 :hold 2})
(def ^:private phase->code
  {:phase-0-manual 0 :phase-1-review 1
   :phase-2-supervised 2 :phase-3-autonomous 3})

(defn- kg->i64
  "Whole kilograms, floored. Exact for a strict `<` against an integer
  threshold; see the core's header for the removal condition."
  [kg]
  (.longValueExact (.setScale (bigdec kg) 0 java.math.RoundingMode/FLOOR)))

(defn- gate-literal [{:keys [phase disposition kg]}]
  (str "(record-new " gate-ty " "
       (get phase->code phase 7) " "          ; 7 = a phase nobody declared
       (disposition->code disposition) " "
       (some? kg) " "
       (if (some? kg) (kg->i64 kg) 0) ")"))

(defn- request-of [{:keys [kg]}]
  (if (some? kg) {:value {:estimated-kg kg}} {:value {}}))

;; ── the corpus ──────────────────────────────────────────────────────────────

(def ^:private weights
  ;; nil = the request declares no weight at all. The rest bracket the 500 kg
  ;; phase-1 threshold, fractionally on both sides, so the floor conversion is
  ;; measured at the only place it could be wrong.
  [nil 0M 0.4M 1M 499M 499.6M 499.9M 500M 500.1M 501M 2000M 5000M])

(def ^:private phases
  [:phase-0-manual :phase-1-review :phase-2-supervised :phase-3-autonomous
   :phase-unknown])

(def ^:private rows
  (for [phase phases
        disposition [:commit :escalate :hold]
        kg weights]
    {:phase phase :disposition disposition :kg kg}))

;; ── verdict → base disposition ──────────────────────────────────────────────

(def ^:private verdict-rows
  (for [hard [true false] escalate [true false]]
    {:hard? hard :escalate? escalate}))

(deftest verdict-to-disposition-agrees
  (let [probes (into {} (map-indexed
                         (fn [i {:keys [hard? escalate?]}]
                           [(str "v" i)
                            (str "(verdict-disposition (record-new " verdict-ty " "
                                 hard? " " escalate? "))")])
                         verdict-rows))
        actual (run-probes probes ":i64")]
    (is (= 4 (count verdict-rows)))
    (is (= #{0 1 2} (set (vals actual))) "the corpus reaches all three dispositions")
    (doseq [[i v] (map-indexed vector verdict-rows)]
      (testing (pr-str v)
        (is (= (disposition->code (phase/verdict->disposition v))
               (get actual (str "v" i))))))
    ;; The property, not just the agreement: a hard violation outranks the soft
    ;; escalate flag. Reversing the two branches routes an unoverridable
    ;; rejection to an approver.
    (is (= 2 (get actual "v0")) "hard? beats escalate?")))

;; ── the gate ────────────────────────────────────────────────────────────────

(deftest the-fixture-reaches-every-outcome
  ;; Without this, a corpus that produced one disposition everywhere would make
  ;; "both sides agree" vacuous.
  (let [outs (set (map (fn [r] (:disposition (phase/gate (:phase r)
                                                         (request-of r)
                                                         (:disposition r))))
                       rows))]
    (is (= #{:commit :escalate :hold} outs)
        "the corpus must reach commit, escalate and hold")))

(deftest gate-disposition-agrees-over-the-whole-corpus
  (doseq [batch (partition-all 60 (map-indexed vector rows))]
    (let [probes (into {} (map (fn [[i r]]
                                 [(str "g" i) (str "(gate-disposition " (gate-literal r) ")")])
                               batch))
          actual (run-probes probes ":i64")]
      (doseq [[i r] batch]
        (testing (pr-str r)
          (is (= (disposition->code
                  (:disposition (phase/gate (:phase r) (request-of r) (:disposition r))))
                 (get actual (str "g" i)))))))))

(deftest the-gate-never-removes-caution
  ;; The property this file exists for. Codes are ordered by caution, so
  ;; "adds caution" is `>=`. Asserted against BOTH implementations, because the
  ;; failure it guards against was present in the only implementation there was.
  (doseq [batch (partition-all 60 (map-indexed vector rows))]
    (let [probes (into {} (map (fn [[i r]]
                                 [(str "m" i) (str "(gate-disposition " (gate-literal r) ")")])
                               batch))
          actual (run-probes probes ":i64")]
      (doseq [[i r] batch]
        (let [before (disposition->code (:disposition r))]
          (is (>= (get actual (str "m" i)) before)
              (str "kotoba gate removed caution: " (pr-str r)))
          (is (>= (disposition->code
                   (:disposition (phase/gate (:phase r) (request-of r) (:disposition r))))
                  before)
              (str "cljc gate removed caution: " (pr-str r))))))))

(deftest a-hold-survives-every-phase
  ;; The specific override `hazmat.policy` says does not exist. Stated
  ;; separately from the monotonicity property above so a regression names
  ;; itself.
  (let [held (filter #(= :hold (:disposition %)) rows)
        probes (into {} (map-indexed
                         (fn [i r] [(str "h" i) (str "(gate-disposition " (gate-literal r) ")")])
                         held))
        actual (run-probes probes ":i64")]
    (is (= 60 (count held)))
    (doseq [[i r] (map-indexed vector held)]
      (is (= 2 (get actual (str "h" i)))
          (str "a hard governor violation must not become reviewable: " (pr-str r)))
      (is (= :hold (:disposition (phase/gate (:phase r) (request-of r) :hold)))
          (str "cljc: " (pr-str r))))))

(deftest gate-reason-agrees-byte-for-byte
  (doseq [batch (partition-all 60 (map-indexed vector rows))]
    (let [probes (into {} (map (fn [[i r]]
                                 [(str "r" i) (str "(gate-reason " (gate-literal r) ")")])
                               batch))
          actual (run-probes probes "[:option :string]")]
      (doseq [[i r] batch]
        (testing (pr-str r)
          (let [expected (:reason (phase/gate (:phase r) (request-of r) (:disposition r)))
                got (get actual (str "r" i))
                ;; [[:option :string] false] | [[:option :string] true "..."]
                got-str (when (second got) (nth got 2))]
            (is (= expected got-str))))))))

(deftest the-floor-conversion-is-exact-at-the-threshold
  ;; Not agreement — the arithmetic claim the core's header makes. For an
  ;; integer threshold and a strict `<`, flooring the weight cannot change the
  ;; answer. Driven through the core's own predicate.
  (let [near (for [kg [499M 499.5M 499.999M 500M 500.001M 500.5M 501M]]
               {:phase :phase-1-review :disposition :commit :kg kg})
        probes (into {} (map-indexed
                         (fn [i r] [(str "f" i) (str "(phase-1-auto-commit? " (gate-literal r) ")")])
                         near))
        actual (run-probes probes ":bool")]
    (doseq [[i r] (map-indexed vector near)]
      (is (= (< (:kg r) 500M) (get actual (str "f" i)))
          (str "floor(" (:kg r) ") < 500 must equal " (:kg r) " < 500")))))

(deftest an-undeclared-weight-is-scored-as-zero
  ;; A recorded gap, pinned so it cannot change silently. It is the opposite
  ;; direction from `hazmat.policy/bulk?`, which treats an unverifiable weight
  ;; as bulk. Unreachable for the two ops `bulk?` covers; reachable for
  ;; :manifest-record.
  (let [r {:phase :phase-1-review :disposition :commit :kg nil}
        actual (run-probes {"z" (str "(effective-kg " (gate-literal r) ")")} ":i64")]
    (is (= 0 (get actual "z")))
    (is (= :commit (:disposition (phase/gate :phase-1-review {:value {}} :commit)))
        "cljc scores an absent weight as 0M and auto-commits in phase 1")))

(deftest the-core-compiles-for-every-target-it-claims
  (doseq [target [:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (compiler/compile-source core-source target {}))))))
