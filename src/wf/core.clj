(ns wf.core
  "Core wf client interface."
  (:require [clojure.spec.alpha :as s]
            [wf.impl :as impl]))

(defn process-event!
  "Injects an incoming event into the engine."
  [engine event]
  (impl/process-event! engine event))

(s/fdef process-event!
  :args (s/cat :engine :wf/engine
               :event :wf/event))

(defn resume-context!
  "Instructs the engine to resume a given interrupted context, i.e., attempt
   to fetch the corresponding flow, leave the Pause node and proceed with
   the execution. This is supposed to be invoked by the scheduling mechanism
   supplied along with the engine."
  [engine context]
  (impl/resume-interrupted-context! engine context))

(s/fdef resume-context!
  :args (s/cat :engine :wf/engine
               :context :wf/context))
