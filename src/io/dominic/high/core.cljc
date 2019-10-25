(ns io.dominic.high.core
  (:refer-clojure :exclude [ref])
  (:require [io.dominic.high.impl.core :as impl]
            [clojure.walk :as walk]))

(defn- safely-derive-parts
  [components init]
  (let [g (impl/system-dependency-graph components)
        sccs (impl/sccs g init)]
    (if-let [errors (seq (impl/dependency-errors sccs g))]
      (throw
        (ex-info
          (apply
            str
            "Could not construct execution order because:\n  "
            (interpose "\n  " (map impl/human-render-dependency-error errors)))
          {:components components
           :errors errors
           :g g
           :sccs sccs}))

      [g (map #(find components (first %)) sccs)])))

(defn- ->executor
  [executor]
  #?(:cljs executor
     :default
     (if (fn? executor)
       executor
       (requiring-resolve executor))))

(defn start
  "Takes a system config to start.  Returns a running system where the keys map
  to the started component.  Runs the :pre-start, :start and :post-start keys
  in that order.
  
  :pre-start and :start may contains references, and will be executed without
  an implicit target.
  :post-start may also contain references, but will have the started component
  as an implict target.  This means that a symbol on it's own will work instead
  of requiring a code form, in addition the anaphoric variable `this` is
  available to refer to the started component."
  [system-config]
  (let [{:keys [components executor]
         :or {executor impl/exec-queue}} system-config
        executor (->executor executor)
        [g component-chain] (safely-derive-parts components [])]
    (executor
      (for [component component-chain
            f [(impl/pre-starting-f components)
               (impl/starting-f components)
               (impl/post-starting-f components)]]
        (f component)))))

(defn stop
  "Takes a system config to stop.

  Runs the :stop key, which may not contain references, and will be executed
  with the started component as an implict target.  If no :stop is provided and
  the target is AutoClosable then .close will be called on it, otherwise
  nothing."
  [system-config running-system]
  (let [{:keys [components executor]
         :or {executor impl/exec-queue}} system-config
        executor (->executor executor)
        [g component-chain] (safely-derive-parts components ())]
    (executor
      (map impl/stopping-f component-chain)
      running-system)))

(comment
  (start
    {:components '{:foo {:start (high/ref :bar)}
                   :bar {:start (high/ref :foo)}
                   :baz {:start (high/ref :baz)}
                   :foob {:start (high/ref :nowhere)}}
     :executor impl/exec-queue})

  (let [system-config {:components '{:foo {:pre-start (prn "init:foo")
                                           :start 1
                                           :post-start prn
                                           :stop prn}
                                     :bar {:start (inc (high/ref :foo))
                                           :stop prn}}
                       :executor impl/promesa-exec-queue}]
    (stop system-config (start system-config))))

(def exec-sync
  "Executor which runs sync between calls."
  impl/exec-queue)

(defn ref
  [ref-to]
  (list 'high/ref ref-to))

(defn deval-body
  "EXPERIMENTAL.  Takes a body of code and defers evaluation of lists."
  [body]
  (walk/postwalk
    (fn [x]
      (if (and (list? x)
               (not= (first x) 'high/ref))
        (cons `list x)
        x))
    body))

(defmacro deval
  "EXPERIMENTAL.  Defers evaluation of lists.  Useful for defining component
  start/stop/etc. functions."
  [body]
  (deval-body body))