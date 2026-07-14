(ns hazmat.store
  "SSoT interface for the HazardousWasteDispatch actor. Implementations vary
  (MemStore for tests, DatomicStore for development, kotoba-server for
  production), but all must satisfy this protocol.

  The store holds:
    - client hazmat-generator registrations + manifest tracking
    - disposal facility capacity + licensing
    - daily collection schedules + manifest records
    - dispute requests + resolutions
    - append-only audit ledger (immutable record of all decisions).")

(defprotocol Store
  "SSoT for hazmat collection dispatch."
  (client [st client-id]
    "Fetch {:id :name :registered? :active? ...}.")
  (client-manifest [st client-id]
    "Fetch [{:id :manifest-date :waste-class :hazard-flags ...}] for
     client's prior manifests.")
  (disposal-facility [st facility-id]
    "Fetch {:id :name :licensed? :methods :daily-capacity-kg ...}.")
  (facility-intake [st facility-id treatment-method]
    "Current cumulative daily intake (kg) for this facility + method.")
  (contract [st tenant]
    "Fetch {:tenant :active? :tier :columns ...} billing contract for
    disclosure tier.")
  (commit-record! [st record]
    "Commit an SSoT mutation: {:effect :intake-upsert|:manifest-record|...
     :value {...} :path [...]}.
     Returns the committed record; throws if invalid.")
  (append-ledger! [st fact]
    "Append an audit-ledger fact (immutable): {:t :committed|:hold|:escalate
     :op :intake-...|:schedule-...|:flag-... :subject :actor ...}.
     Always succeeds; ledger is append-only."))

(defrecord MemStore [clients facilities contracts ledger records]
  Store
  (client [st client-id]
    (get (:clients st) client-id))
  (client-manifest [st client-id]
    (filter #(= client-id (:client-id %)) (:records st)))
  (disposal-facility [st facility-id]
    (get (:facilities st) facility-id))
  (facility-intake [st facility-id treatment-method]
    (or (get-in st [:records facility-id treatment-method :cumulative-intake-kg] 0M) 0M))
  (contract [st tenant]
    (get (:contracts st) tenant))
  (commit-record! [st record]
    (swap! (:records st) conj record)
    record)
  (append-ledger! [st fact]
    (swap! (:ledger st) conj fact)
    fact))

(defn mem-store
  "Create a test/dev MemStore.
  clients: [{:id :name :registered? :active? ...}]
  facilities: [{:id :name :licensed? :methods :daily-capacity-kg ...}]
  contracts: [{:tenant :active? :tier ...}]"
  [clients facilities contracts]
  (MemStore.
   (into {} (map #(vector (:id %) %)) clients)
   (into {} (map #(vector (:id %) %)) facilities)
   (into {} (map #(vector (:tenant %) %)) contracts)
   (atom [])
   (atom [])))
