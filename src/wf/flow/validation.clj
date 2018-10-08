(ns wf.flow.validation
  "Ready-made flow graph validation facilities"
  (:require [wf.settings :as settings]))

(defn- validate-Entry-presence!
  [graph]
  )

(defn- validate-graph-connectivity!
  [graph]
  )

(defn- validate-graph-links!
  [graph]
  )

(defn- validate-inescapable-cycles!
  [flow]
  )

(defn validate-graph!
  [graph]
  (when settings/*validate-Entry-presence?*
    (validate-Entry-presence! graph))
  (when settings/*validate-graph-connectivity?*
    (validate-graph-connectivity! graph))
  (when settings/*validate-graph-links?*
    (validate-graph-links! graph))
  (when settings/*validate-inescapable-cycles?*
    (validate-inescapable-cycles! graph)))
