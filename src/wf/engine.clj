(ns wf.engine
  "A set of protocols to be implemented by the engine record.")

(defprotocol Engine
  "(REQUIRED) Core engine protocol."

  (flows [engine]
    "Returns a seq of all active flows. This call will be overridden in case
     the engine satisfies the PartitionedFlows protocol.")

  ;; TODO Move out, not a core requirement
  (flow-by-id [engine flow-id]
    "Returns an active flow by given flow id, or nil.")

  (invoke-Fn! [engine flow context args]
    "Executes the body of an Fn node with provided args, presumably, though
     not necessarily, for side effects. The value returned by the client fn
     will be stored in the :context/Fn-returns key of the current context
     and can be addressed in downstream execution. It should be noted that
     Fn return values are best kept as terse as possible."))

(defprotocol ContextStore
  "(OPTIONAL*) A set of interactions with the Context Store. This protocol is
   required for running flows with Pause and Expect nodes, as well as for
   deployments with elevated context commitment policy.
   (See wf.settings/*commit-uninterrupted-context?*)"

  (get-interrupted-contexts [engine context-key]
    "Fetches all contexts matching given context key currently in Pause
     or Expect nodes.")

  (commit-context! [engine context]
    "Puts or updates a context in the Context Store."))

(defprotocol Scheduler
  "(OPTIONAL*) Along with ContextStore, this protocol is relied on by the
   machinery of context interruptions and therefore is a requirement for
   the environments with Pause/Expect flows."

  (schedule-context-resumption! [engine context timeout]
    "Schedules a context interrupted by Pause or Expect to be resumed at
     a point in the future. timeout is a number of milliseconds to pass
     from the moment of execution. The resumed context is expected to be
     passed to wf.core/resume-context!.")

  (unschedule-context-resumption! [engine context]
    "Revokes a scheduled context resumption."))

(defprotocol Inheritance
  "(OPTIONAL) Useful for environments wherein a context lifespan is likely
   to outlast that of a flow, i.e. a flow may have already been substituted
   by an updated version by the time its spawning context is resumed.
   (See wf.settings/*honor-flow-inheritance?*)"

  (get-flow-by-ancestor-id [engine ancestor-flow-id]
    "Returns a flow that is a direct descendant of the flow with given id."))

(defprotocol PartitionedFlows
  "(OPTIONAL) Increases overall processing rate when flow screenings are
   guaranteed to occur within a known (small) subset of flows."

  (partitioned-flows [engine]
    "Returns a partitioned map of arbitrary depth containing all active flows.
     This will be invoked instead of the flows method in the Engine protocol
     if an incoming event is supplied with a partition key."))

(defprotocol Election
  "(OPTIONAL) Provides means for custom flow election prior to context
   execution. Falls back to plain clojure.core/rand-nth."

  (elect-flow [this matches event context-id]
    "Given a seq of matching flows and an incoming event, returns either one
     such pair, or nil.
     Matching flows are represented as pairs [flow matching-node-id]"))

(defprotocol Callbacks
  "(OPTIONAL) Not obligatory, but proven useful for providing custom side
   effects throughout all stages of context lifecycle."

  (invoke-callback! [engine callback data]
    "Invokes a custom callback."))
