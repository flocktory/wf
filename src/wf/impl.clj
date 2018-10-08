(ns wf.impl
  "Engine implementation intended for internal use.
   This ns should not be required."
  (:require [clojure.spec.alpha :as s]
            [wf.engine :as engine]
            [wf.event :as event]
            [wf.settings :as settings]
            [wf.spec])
  (:import (java.util Date UUID)))

(defn- now [] (.getTime (Date.)))

(defn- callback!
  [engine callback data]
  (when (satisfies? engine/Callbacks engine)
    (engine/invoke-callback! engine callback data)))

(defn- callback-entered-node!
  [engine flow context node-id]
  (callback! engine :wf.callback/entered-node {:wf/context context
                                               :wf/flow flow
                                               :wf.flow.node/id node-id}))

(defn- callback-left-node!
  [engine flow context node-id]
  (callback! engine :wf.callback/left-node {:wf/context context
                                            :wf/flow flow
                                            :wf.flow.node/id node-id}))

(defn- get-active-flows
  [engine event]
  (if (and (satisfies? engine/PartitionedFlows engine)
           (satisfies? event/PartitionedEvent event))
    (get-in (engine/partitioned-flows engine) (event/partition-key event))
    (engine/flows engine)))

(defn- flows-matching-by-Entry-nodes
  "Returns a seq of all pairs [flow node-id] that match the incoming event."
  [engine flows event]
  (apply concat
    (for [flow flows]
      (for [node (vals (:wf.flow/graph flow))
            :let [{:keys [wf.flow.node/type wf.flow.node/condition]} node]
            :when (= :wf.flow.node/Entry type)
            :when (or (nil? condition) (condition engine event))]
        [flow (:wf.flow.node/id node)]))))

(defn- log-context-change
  ([context change]
   (log-context-change context change nil))
  ([context change data]
   (let [timestamp (now)]
     (update context :wf.context/log
             (fnil conj []) (cond-> [timestamp change]
                              data (conj data))))))

(defn- log-enter-node
  [context node-id]
  (log-context-change context :wf.context.log/enter-node
    {:wf.flow.node/id node-id}))

(defn- log-leave-node
  [context node-id]
  (log-context-change context :wf.context.log/leave-node
    {:wf.flow.node/id node-id}))

(defn- assoc-Fn-return
  [context node-id result]
  (update-in context [:wf.context/Fn-returns node-id]
             (fnil conj []) [(now) result]))

(defn- assoc-Expect-arrival
  [context node-id event]
  (update-in context [:wf.context/Expect-arrivals node-id]
             (fnil conj []) [(now) (event/event-payload event)]))

(defn- assoc-timeout
  [context node-id timeout]
  (let [now* (now)
        expires-at (+ now* timeout)]
    (assoc context :wf.context/timeout [now* expires-at node-id])))

(defmulti next-node-id
  (fn [context node]
    (:wf.flow.node/type node)))

(defmethod next-node-id :default
  [_ node]
  (:wf.flow.node/next node))

(defmethod next-node-id :wf.flow.node/Expect
  [context node]
  (let [arrival (last (get-in context [:wf.context/Expect-arrivals
                                       (:wf.flow.node/id node)]))
        [arrival-time] arrival
        [expect-started-at expect-expires-at] (:wf.context/timeout context)]
    (cond
      (and arrival-time (<= expect-started-at arrival-time expect-expires-at))
      (:wf.flow.node/on-arrival node)
      (> (now) expect-expires-at)
      (:wf.flow.node/on-timeout node))))

(defmethod next-node-id :wf.flow.node/Fn
  [context node]
  (when-let [dispatch-conditions (:wf.flow.node/dispatch node)]
    (let [return-value (-> context
                           (get-in [:wf.context/Fn-returns (:wf.flow.node/id node)])
                           (last)
                           (second))
          holds? (fn [[f & args]]
                   (case f
                     :wf.flow.Fn/eq (= return-value (first args))
                     :wf.flow.Fn/neq (not= return-value (first args))
                     ;; TODO: add more dispatch operators here
                     :wf.flow.Fn/default true))
          [_ node-id] (first (filter (comp holds? first) dispatch-conditions))]
      node-id)))

(defn- move-context-cursor
  "Moves the context cursor to the next position based on graph connections
   and prior execution results."
  [context flow]
  (let [[position node-id] (:wf.context/cursor context)
        current-node (get-in flow [:wf.flow/graph node-id])
        new-cursor
        (case position
          :wf.context.cursor/before [:wf.context.cursor/in node-id]
          :wf.context.cursor/in
          (if-let [next-node-id (next-node-id context current-node)]
            [:wf.context.cursor/before next-node-id]
            [:wf.context.cursor/complete])
          (throw
            (ex-info "Illegal cursor move"
                     {:wf.error/type :wf.error.execution/illegal-cursor-move
                      :wf/context context})))]
    (assoc context :wf.context/cursor new-cursor)))

(defn- maybe-commit-uninterrupted-context!
  [engine context]
  (when (and settings/*commit-uninterrupted-context?*
             (satisfies? engine/ContextStore engine))
    (engine/commit-context! engine context)))

(defn- context-complete!
  [engine flow context node-id]
  (maybe-commit-uninterrupted-context! engine context)
  (callback! engine :wf.callback/context-complete {:wf/context context
                                                   :wf/flow flow
                                                   :wf.flow.node/id node-id}))

(defn- context-failed!
  [engine flow context node-id]
  (maybe-commit-uninterrupted-context! engine context)
  (callback! engine :wf.callback/context-failed {:wf/context context
                                                 :wf/flow flow
                                                 :wf.flow.node/id node-id}))

(declare continue-context!)

(defn- dispatch-node-type
  [_ flow _ node-id]
  (get-in flow [:wf.flow/graph node-id :wf.flow.node/type]))

(defmulti enter-node! dispatch-node-type)
(defmulti leave-node! dispatch-node-type)

(derive :wf.flow.node/Entry :wf.flow.node/non-interrupting-node)
(derive :wf.flow.node/Fn :wf.flow.node/non-interrupting-node)
(derive :wf.flow.node/Expect :wf.flow.node/interrupting-node)
(derive :wf.flow.node/Pause :wf.flow.node/interrupting-node)

(defmethod enter-node! :wf.flow.node/Entry
  [engine flow context node-id]
  (maybe-commit-uninterrupted-context! engine context)
  (callback-entered-node! engine flow context node-id)
  (continue-context! engine flow context))

(defmethod enter-node! :wf.flow.node/Fn
  [engine flow context node-id]
  (let [fn-args (get-in flow [:wf.flow/graph node-id :wf.flow.node/args])]
    (try
      (callback-entered-node! engine flow context node-id)
      (let [result (engine/invoke-Fn! engine flow context fn-args)
            context* (-> context
                         (log-context-change
                           :wf.context.log/Fn-return
                           {:wf.flow.node/id node-id})
                         (assoc-Fn-return node-id result))]
        (continue-context! engine flow context*))
      (catch Exception e
        (let [context* (log-context-change
                         context :wf.context.log/Fn-exception
                         {:wf.flow.node/id node-id
                          :wf.error/exception (.getMessage e)})]
          (if settings/*skip-Fn-exceptions?*
            (continue-context! engine flow context*)
            (do
              (context-failed! engine flow context* node-id)
              (throw e))))))))

(defmethod enter-node! :wf.flow.node/interrupting-node
  [engine flow context node-id]
  (let [timeout (get-in flow [:wf.flow/graph node-id :wf.flow.node/timeout])
        context* (assoc-timeout context node-id timeout)]
    (engine/schedule-context-resumption! engine context* timeout)
    (engine/commit-context! engine context*)
    (callback-entered-node! engine flow context* node-id)))

(defmethod leave-node! :wf.flow.node/non-interrupting-node
  [engine flow context node-id]
  (let [context* (-> context
                     (move-context-cursor flow)
                     (log-leave-node node-id)
                     (dissoc :wf.context/timeout))]
    (maybe-commit-uninterrupted-context! engine context*)
    (callback-left-node! engine flow context* node-id)
    (continue-context! engine flow context*)))

(defmethod leave-node! :wf.flow.node/interrupting-node
  [engine flow context node-id]
  (let [context* (-> context
                     (move-context-cursor flow)
                     (log-leave-node node-id)
                     (dissoc :wf.context/timeout))]
    (engine/commit-context! engine context*)
    (callback-left-node! engine flow context* node-id)
    (continue-context! engine flow context*)))

(defn- continue-context!
  [engine flow context]
  (let [[position node-id] (:wf.context/cursor context)]
    (case position
      :wf.context.cursor/before
      (let [context* (-> context
                         (move-context-cursor flow)
                         (log-enter-node node-id))]
        (enter-node! engine flow context* node-id))
      :wf.context.cursor/in
      (leave-node! engine flow context node-id)
      :wf.context.cursor/complete
      (context-complete! engine flow context node-id))))

(defn- spawn-context!
  [engine matching-flow init-node-id event context-id]
  (let [context (-> #:wf.context{:id context-id
                                 :key (event/context-key event)
                                 :spawned-at (now)
                                 :spawning-event (event/event-payload event)
                                 :spawning-flow-id (:wf.flow/id matching-flow)
                                 :cursor [:wf.context.cursor/in init-node-id]}
                    (log-context-change :wf.context.log/spawned))]
    (callback! engine :wf.callback/context-spawned {:wf/context context
                                                    :wf/flow matching-flow
                                                    :wf.flow.node/id init-node-id})
    (continue-context! engine matching-flow context)))

(defn- get-flow-by-id
  "Attempts to fetch a flow or, if possible, its descendant by given flow id."
  [engine spawning-flow-id]
  (or (engine/flow-by-id engine spawning-flow-id)
      (when (and settings/*honor-flow-inheritance?*
                 (satisfies? engine/Inheritance engine))
        (engine/get-flow-by-ancestor-id engine spawning-flow-id))))

(defn resume-interrupted-context!
  [engine context]
  (let [spawning-flow-id (:wf.context/spawning-flow-id context)]
    (if-let [flow (get-flow-by-id engine spawning-flow-id)]
      (continue-context! engine flow context)
      (do
        (callback! engine :wf.callback/flow-no-longer-active {:wf/context context})
        (context-complete! engine nil context nil)))))

(defn- generate-context-id
  []
  (.toString (UUID/randomUUID)))

(defn- elect-flow
  [engine matches event context-id]
  (if (satisfies? engine/Election engine)
    (engine/elect-flow engine matches event context-id)
    (rand-nth (vec matches))))

(defn- elect-flow-and-spawn-context!
  "Elects a single flow matching given event by an Entry condition.
   The election may occur several times if settings/*reelect-flow-on-exceptions?*
   is set to true."
  [engine event]
  (let [flows (get-active-flows engine event)
        matches (set (flows-matching-by-Entry-nodes engine flows event))]
    (when (seq matches)
      (callback! engine :wf.callback/matched-flows {:wf/event event
                                                    :wf/matching-flows matches}))
    (loop [matches* matches]
      (if (empty? matches*)
        (callback! engine :wf.callback/event-unmatched {:wf/event event})
        (let [context-id (generate-context-id)
              [matching-flow init-node-id :as match]
              (elect-flow engine matches* event context-id)
              result (if-not match
                       ::unmatched
                       (try
                         (spawn-context! engine matching-flow init-node-id
                                         event context-id)
                         (catch Exception e
                           (if settings/*reelect-flow-on-exceptions?*
                             ::reelect
                             (throw e)))))]
          (cond
            (= ::reelect result)
            (recur (disj matches* match))
            (= ::unmatched result)
            (callback! engine :wf.callback/event-unmatched {:wf/event event})))))))

(defn- get-interrupted-contexts-with-flows
  [engine context-key]
  (when (satisfies? engine/ContextStore engine)
    (let [contexts (engine/get-interrupted-contexts engine context-key)
          flows (into {}
                  (for [flow-id (distinct (map :wf.context/spawning-flow-id contexts))]
                    [flow-id (get-flow-by-id engine flow-id)]))]
      (for [context contexts
            :let [flow (get flows (:wf.context/spawning-flow-id context))]
            :when flow]
        (assoc context ::flow flow)))))

(defn- paused-context?
  [context-with-flow]
  (let [[position node-id] (:wf.context/cursor context-with-flow)]
    (and
      (= :wf.context.cursor/in position)
      (= :wf.flow.node/Pause
         (get-in context-with-flow
           [::flow :wf.flow/graph node-id :wf.flow.node/type])))))

(defn- matching-expecting-contexts
  [engine event contexts-with-flows]
  (for [context contexts-with-flows
        :let [[_ _ node-id] (:wf.context/timeout context)]
        :let [{:keys [wf.flow.node/type wf.flow.node/condition]}
              (get-in context [::flow :wf.flow/graph node-id])]
        :when (= :wf.flow.node/Expect type)
        :when (or (nil? condition) (condition engine event))]
    context))

(defn- resume-expecting-context
  [engine event context-with-flow]
  (let [[_ _ node-id] (:wf.context/timeout context-with-flow)
        context* (assoc-Expect-arrival context-with-flow node-id event)]
    ;; race
    (engine/unschedule-context-resumption! engine context*)
    (resume-interrupted-context! engine context*)))

(defn- resume-expecting-contexts
  [engine event contexts-with-flows]
  (when (seq contexts-with-flows)
    (if (= settings/*Expect-resumption* :wf.settings.Expect-resumption/all)
      (doseq [context contexts-with-flows]
        (resume-expecting-context engine event context))
      (let [sort-fn (case settings/*Expect-resumption*
                      :wf.settings.Expect-resumption/earliest
                      :wf.context/spawned-at
                      :wf.settings.Expect-resumption/latest
                      (comp - :wf.context/spawned-at))]
        (->> contexts-with-flows
             (sort-by sort-fn)
             (first)
             (resume-expecting-context engine event))))))

(defn process-event!
  [engine event]
  (let [context-key (event/context-key event)
        contexts-with-flows (get-interrupted-contexts-with-flows engine context-key)
        paused-contexts (filter paused-context? contexts-with-flows)
        expecting-contexts (matching-expecting-contexts
                             engine event contexts-with-flows)]
    (when (satisfies? engine/Scheduler engine)
      (resume-expecting-contexts engine event expecting-contexts))
    (when (and (> settings/*context-parallelism* (count paused-contexts))
               (or (empty? expecting-contexts)
                   settings/*elect-flow-after-Expect-resumption?*))
      (elect-flow-and-spawn-context! engine event))))

;; Spec

(s/fdef process-event!
  :args (s/cat :engine :wf/engine
               :event :wf/event))

(s/fdef enter-node!
  :args (s/cat :engine :wf/engine
               :flow :wf/flow
               :context :wf/context
               :node-id :wf.flow.node/id))

(s/fdef leave-node!
  :args (s/cat :engine :wf/engine
               :flow :wf/flow
               :context :wf/context
               :node-id :wf.flow.node/id))

(s/fdef next-node-id
  :args (s/cat :context :wf/context
               :node-id :wf.flow/node)
  :ret :wf.flow.node/id)
