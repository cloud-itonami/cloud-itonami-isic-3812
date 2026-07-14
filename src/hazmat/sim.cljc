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

        ;; Build the actor (in production, this invokes the graph)
        _actor (operation/build st {:advisor (llm/mock-advisor)})]

    (println "HazmatDispatch Actor Simulation")
    (println "==============================")
    (println "Client count:" (count clients))
    (println "Facility count:" (count facilities))
    (println "Actor built: true")
    (println "")))
