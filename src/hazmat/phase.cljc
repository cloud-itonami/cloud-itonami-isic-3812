(ns hazmat.phase
  "Rollout phases: a hazmat dispatch actor can operate at different
  confidence/automation levels depending on deployment phase. The phase gate
  can only ADD caution (downgrade :commit → :escalate), never REMOVE it.

  The DECISION is in `src/hazmat/phase_core.kotoba`; this namespace is the
  host side (the verdict/request maps, the keyword ↔ code translation, and
  the `{:disposition :reason}` map `hazmat.operation` destructures) and the
  oracle the decision is checked against by
  `test/hazmat/phase_kotoba_parity_test.clj`. Keywords do not cross that
  boundary and the core's disposition codes are ordered by caution
  (commit 0 < escalate 1 < hold 2), which is what lets the never-remove-caution
  property be stated as arithmetic.

  2026-08-30: phase 0 used to return `:escalate` UNCONDITIONALLY, including for
  a `:hold`. `hazmat.policy` promises a HARD violation \"forces HOLD; a human
  cannot override\", and `hazmat.operation` routes `:escalate` to
  `:request-approval` where an approver saying `:approved` commits — so the
  most cautious phase was the only one that could hand back the override the
  governor said did not exist. Phase 1 already guarded `:hold`. Fixed in both
  implementations at once; the property is asserted over the whole
  cross-product in the parity test rather than only as agreement with this
  file.")

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

  - Phase 0 (manual): every operation escalates for human review, EXCEPT a
    :hold, which stands (a hard governor violation is not reviewable)
  - Phase 1 (review): small-volume manifests auto-commit, others escalate
  - Phase 2 (supervised): governor verdict is trusted; phase adds no extra caution
  - Phase 3 (autonomous): same as phase 2 (future: optimization permitted)"
  [phase request disposition]
  (case phase
    :phase-0-manual
    ;; A hold is not something a human can usefully be asked about: the
    ;; governor already refused it on a ground it declared unoverridable.
    ;; Escalating it here is how it would reach an approver.
    (if (= :hold disposition)
      {:disposition :hold :reason nil}
      {:disposition :escalate :reason "Phase 0: all operations require human review"})

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
