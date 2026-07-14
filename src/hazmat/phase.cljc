(ns hazmat.phase
  "Rollout phases: a hazmat dispatch actor can operate at different
  confidence/automation levels depending on deployment phase. The phase gate
  can only ADD caution (downgrade :commit → :escalate), never REMOVE it.")

(def default-phase :phase-2-supervised)

(defn verdict->disposition
  "Convert governor verdict → base disposition before phase gate.
   - :hard? = :hold (never override)
   - :escalate? = :escalate (soft/human decision)
   - else = :commit (clean, safe)"
  [{:keys [hard? escalate?]}]
  (cond
    hard?     :hold
    escalate? :escalate
    :else     :commit))

(defn gate
  "Apply phase-level safety gate to a verdict-derived disposition.
  Returns {:disposition :commit|:escalate|:hold :reason nil|str}.

  - Phase 0 (manual): all operations escalate for human review
  - Phase 1 (review): small-volume manifests auto-commit, others escalate
  - Phase 2 (supervised): governor verdict is trusted; phase adds no extra caution
  - Phase 3 (autonomous): same as phase 2 (future: optimization permitted)"
  [phase request disposition]
  (case phase
    :phase-0-manual
    {:disposition :escalate :reason "Phase 0: all operations require human review"}

    :phase-1-review
    (if (= :hold disposition)
      {:disposition :hold :reason nil}
      (if (and (= :commit disposition)
               (< (or (get-in request [:value :estimated-kg] 0M) 0M) 500M))
        {:disposition :commit :reason nil}
        {:disposition :escalate :reason "Phase 1: intake >500kg or low-confidence requires review"}))

    :phase-2-supervised
    {:disposition disposition :reason nil}

    :phase-3-autonomous
    {:disposition disposition :reason nil}

    ;; fallback to phase-2
    {:disposition disposition :reason nil}))
