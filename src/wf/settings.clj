(ns wf.settings
  "A set of dynamic parameters governing the mechanics of flow execution,
   set to reasonable default values.")

;; Execution

(def ^:dynamic *elect-flow-after-Expect-resumption?*
  false)

(def ^:dynamic *Expect-resumption*
  "May be one of:
    - :wf.settings.Expect-resumption/all,
    - :wf.settings.Expect-resumption/earliest,
    - :wf.settings.Expect-resumption/latest"
  :wf.settings.Expect-resumption/all)

(def ^:dynamic *context-parallelism*
  Integer/MAX_VALUE)

(def ^:dynamic *honor-flow-inheritance?*
  "When set to true, allows the engine to resume contexts against the
   descendants of their spawning flows.
   It is also required that the engine implement wf.engine/Inheritance."
  true)

(def ^:dynamic *skip-Fn-exceptions?*
  "When set to true, instructs the engine to proceed with context execution
   even if an Fn invocation resulted in an uncaught exception."
  false)

(def ^:dynamic *reelect-flow-on-exceptions?*
  "If this is set to true and the initial (i.e. post-election) execution fails,
   the engine will return back to election phase with the same candidate flows
   minus the one that failed, spawning a brand new context."
  true)

(def ^:dynamic *commit-uninterrupted-context?*
  "When set to true, contexts will be commited upon the engine's entering
   and leaving non-interrupting nodes (i.e. Entry and Fn).
   It is also required that the engine implement wf.engine/ContextStore."
  false)

;; Validation

(def ^:dynamic *validate-Entry-presence?*
  "If set to true, flow validation will throw an ex-info with :wf.error/type
   :wf.error.validation/missing-entry if no Entry node is present in given
   flow graph."
  true)

(def ^:dynamic *validate-graph-connectivity?*
  "If set to true, flow validation will throw an ex-info with :wf.error/type
   :wf.error.validation/disconnected-graph if given flow graph is disconnected
   (i.e. there exists a pair of nodes not connected by a path)."
  true)

(def ^:dynamic *validate-graph-links?*
  "If set to true, flow validation will throw an ex-info with :wf.error/type
   :wf.error.validation/disconnected-graph if a flow graph contains links to
   inexistent nodes."
  true)

(def ^:dynamic *validate-inescapable-cycles?*
  "If set to true, flow validation will throw an ex-info with :wf.error/type
   :wf.error.validation/inescapable-cycle if a flow graph contains
   an inescapable cycle."
  true)
