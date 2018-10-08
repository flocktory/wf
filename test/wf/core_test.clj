(ns wf.core-test
  (:require [clojure.test :refer :all]
            [wf.core :as core]
            [wf.engine :as engine]
            [wf.event :as event]))

;;; Conditions

(defn- event-of-type?
  [event type]
  (= type (::type (event/event-payload event))))

(defn- event-type-A? [_ event] (event-of-type? event "A"))
(defn- event-type-B? [_ event] (event-of-type? event "B"))
(defn- event-type-C? [_ event] (event-of-type? event "C"))

;;; Actions

(defn- invoke-action!
  [engine context args]
  (let [{::keys [action]} args]
    (action engine context args)))

(defn- add!
  [engine context args]
  (let [n (get-in context [:wf.context/spawning-event ::n])
        {::keys [item]} args]
    (swap! (:state engine) conj (+ n item))))

(defn- multiply!
  [engine context args]
  (let [n (get-in context [:wf.context/spawning-event ::n])
        {::keys [multiple]} args]
    (swap! (:state engine) conj (* n multiple))))

(defn- fizzbuzz-dispatch
  [_ context _]
  (let [n (get-in context [:wf.context/spawning-event ::n])
        res (map (partial rem n) [2 3])]
    (cond
      (every? zero? res) "fizzbuzz"
      (zero? (first res)) "fizz"
      (zero? (second res)) "buzz")))

(defn- write-value!
  [engine _ args]
  (let [{::keys [value]} args]
    (swap! (:state engine) conj value)))

;;; Flows

(def ^:private FLOW1
  "entry ─→ fn"
  #:wf.flow
    {:id "1"
     :graph {"entry" #:wf.flow.node {:id "entry"
                                     :type :wf.flow.node/Entry
                                     :condition event-type-A?
                                     :next "fn"}
             "fn" #:wf.flow.node {:id "fn"
                                  :type :wf.flow.node/Fn
                                  :args #::{:action multiply! :multiple 10}}}})

(def ^:private FLOW2
  "entry1 ─┐
   entry2 ─┴─→ fn"
  #:wf.flow
    {:id "2"
     :graph {"entry1" #:wf.flow.node {:id "entry1"
                                      :type :wf.flow.node/Entry
                                      :condition event-type-B?
                                      :next "fn"}
             "entry2" #:wf.flow.node {:id "entry2"
                                      :type :wf.flow.node/Entry
                                      :condition event-type-C?
                                      :next "fn"}
             "fn" #:wf.flow.node {:id "fn"
                                  :type :wf.flow.node/Fn
                                  :args #::{:action add! :item 13}}}})

(def ^:private FLOW3
  "entry ─→ dispatch ┬──→ fizz
                     ├──→ buzz
                     ├──→ fizzbuzz
                     └──→ default"
  #:wf.flow
    {:id "3"
     :graph {"entry"
             #:wf.flow.node {:id "entry"
                             :type :wf.flow.node/Entry
                             :condition event-type-A?
                             :next "dispatch"}
             "dispatch"
             #:wf.flow.node {:id "dispatch"
                             :type :wf.flow.node/Fn
                             :args {::action fizzbuzz-dispatch}
                             :dispatch [[[:wf.flow.Fn/eq "fizz"] "write-fizz"]
                                        [[:wf.flow.Fn/eq "buzz"] "write-buzz"]
                                        [[:wf.flow.Fn/eq "fizzbuzz"] "write-fizzbuzz"]
                                        [[:wf.flow.Fn/default] "default"]]}
             "write-fizz"
             #:wf.flow.node {:id "write-fizz"
                             :type :wf.flow.node/Fn
                             :args #::{:action write-value! :value "fizz"}}
             "write-buzz"
             #:wf.flow.node {:id "write-buzz"
                             :type :wf.flow.node/Fn
                             :args #::{:action write-value! :value "buzz"}}
             "write-fizzbuzz"
             #:wf.flow.node {:id "write-fizzbuzz"
                             :type :wf.flow.node/Fn
                             :args #::{:action write-value! :value "fizzbuzz"}}
             "default"
             #:wf.flow.node {:id "default"
                             :type :wf.flow.node/Fn
                             :args #::{:action write-value! :value "."}}}})

(def ^:private FLOW4
  "entry ─→ pause ─→ fn"
  #:wf.flow
    {:id "4"
     :graph {"entry" #:wf.flow.node {:id "entry"
                                     :type :wf.flow.node/Entry
                                     :condition event-type-A?
                                     :next "pause"}
             "pause" #:wf.flow.node {:id "pause"
                                     :type :wf.flow.node/Pause
                                     :timeout 1000
                                     :next "fn"}
             "fn" #:wf.flow.node {:id "fn"
                                  :type :wf.flow.node/Fn
                                  :args #::{:action add! :item 10}}}})

(def ^:private FLOW5
  "entry ─→ expect ─→ arrival
                 └──→ timeout"
  #:wf.flow
    {:id "5"
     :graph {"entry" #:wf.flow.node {:id "entry"
                                     :type :wf.flow.node/Entry
                                     :condition event-type-A?
                                     :next "expect"}
             "expect" #:wf.flow.node {:id "expect"
                                      :type :wf.flow.node/Expect
                                      :condition event-type-B?
                                      :timeout 1000
                                      :on-arrival "arrival"
                                      :on-timeout "timeout"}
             "arrival" #:wf.flow.node {:id "arrival"
                                       :type :wf.flow.node/Fn
                                       :args #::{:action write-value!
                                                 :value "arrival"}}
             "timeout" #:wf.flow.node {:id "timeout"
                                       :type :wf.flow.node/Fn
                                       :args #::{:action write-value!
                                                 :value "timeout"}}}})

(defrecord BasicEvent [data]
  event/Event
  (context-key [_]
    (get-in data [::payload ::context-key]))
  (event-payload [_]
    (::payload data)))

(defn- process!
  [engine event-payload]
  (core/process-event! engine (BasicEvent. {::payload event-payload})))

;; Minimal engine implementations operating on events holding scalar values.
;; A matching flow performs an operation on the received value and conjs
;; the result to the engine's state.

(defrecord BasicEngine [state flows]
  engine/Engine
  (flows [_] flows)
  (invoke-Fn! [this _ context args]
    (invoke-action! this context args)))

(defrecord InterruptingEngine [state flows context-store]
  engine/Engine
  (flows [_] flows)
  (flow-by-id [_ flow-id]
    (first (filter (comp (partial = flow-id) :wf.flow/id) flows)))
  (invoke-Fn! [this _ context args]
    (invoke-action! this context args))
  engine/ContextStore
  (get-interrupted-contexts [_ context-key]
    (get @context-store context-key))
  (commit-context! [_ context]
    (swap! context-store update (:wf.context/key context) conj context))
  engine/Scheduler
  (schedule-context-resumption! [this context timeout]
    (future
      (Thread/sleep timeout)
      (core/resume-context! this context)))
  (unschedule-context-resumption! [_ context]))

(deftest uninterrupted-context-test
  (testing "linear flows"
    (let [state (atom [])
          engine (BasicEngine. state [FLOW1 FLOW2])]
      (process! engine #::{:type "A" :n 2})
      (process! engine #::{:type "B" :n 3})
      (process! engine #::{:type "A" :n 4})
      (process! engine #::{:type "C" :n 5})
      (process! engine #::{:type "D" :n 6})
      (is (= @state [20 16 40 18]))))

  (testing "Fn dispatch"
    (let [state (atom [])
          engine (BasicEngine. state [FLOW3])]
      (doseq [n (range 2 8)]
        (process! engine #::{:type "A" :n n}))
      (is (= @state ["fizz" "buzz" "fizz" "." "fizzbuzz" "."])))))

(deftest interrupting-contexts
  (testing "pause"
    (let [state (atom [])
          context-store (atom {})
          engine (InterruptingEngine. state [FLOW4] context-store)]
      (process! engine #::{:type "A" :context-key "A-1" :n 32})
      (process! engine #::{:type "B" :context-key "B-1" :n 64})
      (is (= @state []))
      (Thread/sleep 1500)
      (is (= @state [42]))))

  (testing "expect"
    (let [state (atom [])
          context-store (atom {})
          engine (InterruptingEngine. state [FLOW5] context-store)]

      (testing "timeout"
        (process! engine #::{:type "A" :context-key "1" :n 8})
        (Thread/sleep 1500)
        (is (= @state ["timeout"])))

      (testing "arrival"
        (reset! state [])
        (process! engine #::{:type "A" :context-key "1"})
        (Thread/sleep 100)
        (process! engine #::{:type "B" :context-key "1"})
        (is (= @state ["arrival" "timeout"]))))))
