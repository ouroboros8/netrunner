(ns game.macros
  (:require [clojure.tools.analyzer.jvm :as a.j]
            [clojure.tools.analyzer.ast :as ast]))

(def forms
  (->>
    '[
      runner (:runner @state)
      corp (:corp @state)
      run (:run @state)
      run-server (get-in @state (concat [:corp :servers] (:server (:run @state))))
      run-ices (get-in @state (concat [:corp :servers] (:server (:run @state)) [:ices]))
      run-position (get-in @state [:run :position])
      current-ice (get-current-ice state)
      corp-reg (get-in @state [:corp :register])
      corp-reg-last (get-in @state [:corp :register-last-turn])
      runner-reg (get-in @state [:runner :register])
      runner-reg-last (get-in @state [:runner :register-last-turn])
      target (let [t (first targets)]
               (if (and (map? t)
                        (contains? t :uuid)
                        (contains? t :value))
                 (:value t)
                 t))
      context (let [t (first targets)]
                (if (and (map? t)
                         (contains? t :uuid)
                         (contains? t :value))
                  (:value t)
                  t))
      installed (#{:rig :servers} (first (get-zone card)))
      remotes (get-remote-names state)
      servers (zones->sorted-names (get-zones state))
      unprotected (let [server (second (get-zone card))]
                    (empty? (get-in @state [:corp :servers server :ices])))
      runnable-servers (zones->sorted-names (get-runnable-zones state side eid card nil))
      hq-runnable (not (:hq (get-in (:runner @state) [:register :cannot-run-on-server])))
      rd-runnable (not (:rd (get-in (:runner @state) [:register :cannot-run-on-server])))
      archives-runnable (not (:archives (get-in (:runner @state) [:register :cannot-run-on-server])))
      tagged (is-tagged? state)
      ;; only intended for use in event listeners on (pre-/post-, un-)successful-run or run-ends
      ;; true if the run was initiated by this card
      this-card-run (and (get-in card [:special :run-id])
                         (= (get-in card [:special :run-id])
                            (:run-id (first targets))))
      this-server (let [s (get-zone card)
                        r (:server (:run @state))]
                    (= (second s) (first r)))
      ]
    (partition 2)
    (map (juxt first identity))
    (into {})))

;; Taken from https://github.com/Bronsa/tools.analyzer.jvm.deps/commit/8c7c3936e6f73e85f9e7cc122a2142c43d459c12
;; TODO: Switch from this inlined function to requiring the right package when the new version drops.
(defn- find-undefined-locals
  "Takes a form and returns a set of all the free locals in it"
  [expr]
  (->> (binding [a.j/run-passes identity]
         (a.j/analyze expr (a.j/empty-env)))
       ast/nodes
       (filter (fn [{:keys [op]}] (= op :maybe-class)))
       (map :class)
       (remove (fn [x] (-> x str (.contains "."))))
       (into #{})))

(defn- emit-only
  [needed-locals]
  (mapcat identity
          (for [x needed-locals
                :when (contains? forms x)]
            (get forms x))))

(defn- effect-state-handler
  [expr]
  (for [body expr]
    (if (#{:runner :corp} (second body))
      (concat [(first body) 'state (second body)] (drop 2 body))
      (concat [(first body) 'state 'side] (rest body)))))

(defmacro req [& expr]
  (let [needed-locals (find-undefined-locals expr)
        nls (emit-only needed-locals)]
    `(fn ~['state 'side 'eid 'card 'targets]
       (let [~@nls]
         ~@expr))))

(defmacro effect [& expr]
  `(req ~@(effect-state-handler expr)))

(defmacro msg [& expr]
  `(req (str ~@expr)))


(defmacro wait-for
  ([action & expr]
   (let [awaited-fn `(fn [eid#]
                       (let [~'async-result (:result eid#)]
                         ~@expr))
         ;; this creates a five-argument function to be resolved later,
         ;; without overriding any local variables name state, card, etc.
         totake (if (#{'apply 'handler 'payable?} (first action)) 4 3)
         th (nth action totake)]
     `(let [~'use-eid# (and (map? ~th) (:eid ~th))
            ~'new-eid# (if ~'use-eid# ~th (game.core.eid/make-eid ~'state))]
        (~'game.core.eid/register-effect-completed ~'state ~'new-eid# ~awaited-fn)
        (if ~'use-eid#
          ~(concat (take totake action) (list 'new-eid#) (drop (inc totake) action))
          ~(concat (take totake action) (list 'new-eid#) (drop totake action)))))))

(defmacro continue-ability
  [state side ability card targets]
  `(game.core.engine/resolve-ability ~state ~side (if (:eid ~ability) ~ability (assoc ~ability :eid ~'eid)) ~card ~targets))

(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))
