(ns wf.event
  "Protocols of wf event records.")

(defprotocol Event
  "(REQUIRED) Core event protocol."

  (context-key [this]
    "Returns the partition key of the event.")

  (event-payload [this]
    "Returns the payload of the event."))

(defprotocol PartitionedEvent
  "(OPTIONAL) May be used along with the engine's PartitiondedFlows protocol
   to limit the initial set of flow candidates based on custom partitioning
   logic."

  (partition-key [this]
    "Returns the partition key of the event."))
