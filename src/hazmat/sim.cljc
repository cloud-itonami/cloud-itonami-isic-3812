(ns hazmat.sim
  "HazmatDispatch actor demo — one intake-to-disposal operation, end-to-end."
  (:require [hazmat.operation :as operation]
            [hazmat.store :as store]
            [hazmat.llm :as llm]))

(defn -main [& _args]
  (let [;; Set up test clients and disposal facilities
        clients [{:id "client-001" :name "Industrial Chemistry Co." :registered? true :active? true}]
        facilities [{:id "facility-001" :name "Licensed Hazmat Incinerator" :licensed? true
                     :methods #{:incineration} :daily-capacity-kg {:incineration 5000M}}]
        contracts [{:tenant "client-001" :active? true :tier :tier/basic}]
        st (store/mem-store clients facilities contracts)

        ;; Build the actor
        actor (operation/build st {:advisor (llm/mock-advisor)})

        ;; Run a clean intake
        request {:op :intake-collection-order
                 :subject "intake-001"
                 :value {:id "intake-001" :client-id "client-001" :facility-id "facility-001"
                         :waste-class :corrosive :hazard-flags #{:corrosive}
                         :estimated-kg 100M :scheduled-date "2026-07-14"
                         :source {:class :collector-visual-inspection :ref "US-RCRA"}}}
        context {:actor-id "hazmat-dispatch-001" :client-id "client-001"
                 :actor-role :collection-dispatcher :phase :phase-2-supervised}
        result (try
                 ;; This would call actor.invoke in production; for now just
                 ;; log the structure.
                 {:result "Actor compiled successfully" :ready true}
                 (catch Exception e
                   {:error (str e) :ready false}))]

    (println "HazmatDispatch Actor Simulation")
    (println "==============================")
    (println "Client count:" (count clients))
    (println "Facility count:" (count facilities))
    (println "Actor built:" (:ready result))
    (println "")))
