(ns wf.spec
  "Spec definitions of all wf entities."
  (:require [clojure.spec.alpha :as s]
            [wf.engine :as engine]
            [wf.event :as event]))

;; Core entities

(s/def :wf/engine (partial satisfies? engine/Engine))

(s/def :wf/event (partial satisfies? event/Event))
(s/def :wf.event/data any?)

(s/def :wf/callback
  #{:wf.callback/event-unmatched
    :wf.callback/matched-flows
    :wf.callback/entered-node
    :wf.callback/left-node
    :wf.callback/flow-no-longer-active
    :wf.callback/context-spawned
    :wf.callback/context-failed
    :wf.callback/context-complete})

;; Flow, node

(s/def :wf/flow
  (s/keys
    :req [:wf.flow/id :wf.flow/graph]))

(s/def :wf.flow/id any?)

(s/def :wf.flow/graph
  (s/map-of :wf.flow.node/id :wf.flow/node))

(s/def :wf.flow.node/type
  #{:wf.flow.node/Entry
    :wf.flow.node/Expect
    :wf.flow.node/Fn
    :wf.flow.node/Pause})

(s/def :wf.flow.node/id keyword?)
(s/def :wf.flow.node/next :wf.flow.node/id)
(s/def :wf.flow.node/on-arrival :wf.flow.node/id)
(s/def :wf.flow.node/on-timeout :wf.flow.node/id)
(s/def :wf.flow.node/args map?)
(s/def :wf.flow.node/condition fn?)
(s/def :wf.flow.node/timeout integer?)

(defmulti flow-node :wf.flow.node/type)

(defmethod flow-node :wf.flow.node/Entry
  [_]
  (s/keys
    :req [:wf.flow.node/type
          :wf.flow.node/id
          :wf.flow.node/next]
    :opt [:wf.flow.node/condition]))

(defmethod flow-node :wf.flow.node/Expect
  [_]
  (s/and
    (s/keys
      :req [:wf.flow.node/type
            :wf.flow.node/id
            :wf.flow.node/timeout]
      :opt [:wf.flow.node/condition
            :wf.flow.node/on-arrival
            :wf.flow.node/on-timeout])
    (s/or
      :arrival #(contains? % :wf.flow.node/on-arrival)
      :timeout #(contains? % :wf.flow.node/on-timeout))))

(s/def :wf.flow.node.dispatch/condition
  (s/or
    :comparison (s/tuple #{:wf.flow.Fn/eq :wf.flow.Fn/neq} any?)
    :default (partial = [:wf.flow.Fn/default])))

(s/def :wf.flow.node/dispatch
  (s/coll-of
    (s/tuple :wf.flow.node.dispatch/condition :wf.flow.node/id)))

(defmethod flow-node :wf.flow.node/Fn
  [_]
  (s/keys
    :req [:wf.flow.node/type
          :wf.flow.node/id
          :wf.flow.node/args]
    :opt [:wf.flow.node/dispatch]))

(defmethod flow-node :wf.flow.node/Pause
  [_]
  (s/keys
    :req [:wf.flow.node/type
          :wf.flow.node/id
          :wf.flow.node/timeout]
    :opt [:wf.flow.node/next]))

(s/def :wf.flow/node
  (s/multi-spec flow-node :wf.flow.node/type))

;; Context

(s/def :wf.context/spawning-flow-id :wf.flow/id)

(s/def :wf.context/spawning-event :wf.event/data)

(s/def :wf.context.cursor/position
  #{:wf.context.cursor/before
    :wf.context.cursor/in})

(s/def :wf.context/cursor
  (s/or
    :running (s/tuple :wf.context.cursor/position :wf.flow.node/id)
    :complete (partial = [:wf.context.cursor/complete])))

(s/def :wf.context/timestamp integer?)

(s/def :wf.context.log/change
  #{:wf.context.log/spawned
    :wf.context.log/enter-node
    :wf.context.log/leave-node
    :wf.context.log/Fn-return
    :wf.context.log/Fn-exception})

(s/def :wf.context.log/data any?)

(s/def :wf.context.log/entry
  (s/or :without-log-data (s/tuple :wf.context/timestamp
                                   :wf.context.log/change)
        :with-log-data (s/tuple :wf.context/timestamp
                                :wf.context.log/change
                                :wf.context.log/data)))

(s/def :wf.context/log
  (s/coll-of :wf.context.log/entry))

(s/def :wf.context/Fn-return any?)

(s/def :wf.context/Fn-returns
  (s/map-of
    :wf.flow.node/id
    (s/coll-of
      (s/tuple :wf.context/timestamp :wf.context/Fn-return))))

(s/def :wf.context/Expect-arrivals
  (s/map-of
    :wf.flow.node/id
    (s/coll-of
      (s/tuple :wf.context/timestamp :wf.event/data))))

(s/def :wf.context/timeout
  (s/cat :started-at :wf.context/timestamp
         :expires-at :wf.context/timestamp
         :node-id :wf.flow.node/id))

(s/def :wf/context
  (s/keys
    :req [:wf.context/id
          :wf.context/key
          :wf.context/spawning-flow-id
          :wf.context/spawning-event
          :wf.context/cursor
          :wf.context/log]
    :opt [:wf.context/timeout
          :wf.context/Fn-returns
          :wf.context/Expect-arrivals]))
