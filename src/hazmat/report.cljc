(ns hazmat.report
  "Report generation and disclosure. Clients query intake/manifest/disposal
  records; the HazardousWasteGovernor's disclosure-tier gate ensures they
  only see columns their contract permits.")

(defn generate-report
  "Draft a client-facing report of intake/manifest records filtered to the
  client's disclosure tier."
  [store client-id columns]
  {:summary (str "report for client " client-id)
   :columns columns
   :records []
   :generated-at (java.time.Instant/now)})
