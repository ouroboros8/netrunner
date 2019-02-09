(ns nr.utils
  (:require [clojure.string :refer [join] :as s]))

;; Dot definitions
(def zws "\u200B")                  ; zero-width space for wrapping dots
(def influence-dot (str "●" zws))   ; normal influence dot
(def banned-dot (str "✘" zws))      ; on the banned list
(def restricted-dot (str "🦄" zws)) ; on the restricted list
(def alliance-dot (str "○" zws))    ; alliance free-inf dot
(def rotated-dot (str "↻" zws))     ; on the rotation list

(def banned-span
  [:span.invalid {:title "Removed"} " " banned-dot])

(def restricted-span
  [:span {:title "Restricted"} " " restricted-dot])

(def rotated-span
  [:span.casual {:title "Rotated"} " " rotated-dot])

(defn- make-dots
  "Returns string of specified dots and number. Uses number for n > 20"
  [dot n]
  (if (<= 20 n)
    (str n dot)
    (join (conj (repeat n dot) ""))))

(defn influence-dots
  "Returns a string with UTF-8 full circles representing influence."
  [num]
  (make-dots influence-dot num))

(defn alliance-dots
  [num]
  (make-dots alliance-dot num))

(defn- dots-html
  "Make a hiccup-ready vector for the specified dot and cost-map (influence or mwl)"
  [dot cost-map]
  (for [factionkey (sort (keys cost-map))]
    ^{:key factionkey}
    [:span.influence {:class (name factionkey)} (make-dots dot (factionkey cost-map))]))

;; Shared function options
(defn toastr-options
  "Function that generates the correct toastr options for specified settings"
  [options]
  (js-obj "closeButton" (:close-button options false)
          "debug" false
          "newestOnTop" false
          "progressBar" false
          "positionClass" "toast-card"
          ;; preventDuplicates - identical toasts don't stack when the property is set to true.
          ;; Duplicates are matched to the previous toast based on their message content.
          "preventDuplicates" (:prevent-duplicates options true)
          "onclick" nil
          "showDuration" 300
          "hideDuration" 1000
          ;; timeOut - how long the toast will display without user interaction
          "timeOut" (:time-out options 3000)
          ;; extendedTimeOut - how long the toast will display after a user hovers over it
          "extendedTimeOut" (:time-out options 1000)
          "showEasing" "swing"
          "hideEasing" "linear"
          "showMethod" "fadeIn"
          "hideMethod" "fadeOut"
          "tapToDismiss" (:tap-to-dismiss options true)))

(defn map-longest
  [f default & colls]
  (lazy-seq
    (when (some seq colls)
      (cons
        (apply f (map #(if (seq %) (first %) default) colls))
        (apply map-longest f default (map rest colls))))))

(def slug->format
  {"standard" "Standard"
   "eternal" "Eternal"
   "core-experience" "Core Experience"
   "snapshot" "Snapshot"
   "snapshot-plus" "Snapshot Plus"
   "socr8" "SOCR8"
   "casual" "Casual"})

(def format->slug
  {"Standard" "standard"
   "Eternal" "eternal"
   "Core Experience" "core-experience"
   "Snapshot" "snapshot"
   "Snapshot Plus" "snapshot-plus"
   "SOCR8" "socr8"
   "Casual" "casual"})

(defn add-symbols [card-text]
  (let [icon-map [
          ["\\[credits?\\]" "credit"]
          ["\\[\\$\\]" "credit"]
          ["\\[click\\]" "click"]
          ["\\[subroutine\\]" "subroutine"]
          ["\\[recurring[ -]credits?\\]" "recurring-credit"]
          ["1 ?\\[(memory unit|mu)\\]" "mu1"]
          ["2 ?\\[(memory unit|mu)\\]" "mu2"]
          ["3 ?\\[(memory unit|mu)\\]" "mu3"]
          ["\\[Memory Unit|mu\\]" "mu"]
          ["\\[link\\]" "link"]
          ["\\[trash\\]" "trash"]
          ["\\[adam\\]" "adam"]
          ["\\[anarch\\]" "anarch"]
          ["\\[apex\\]" "apex"]
          ["\\[criminal\\]" "criminal"]
          ["\\[haas-bioroid\\]" "haas-bioroid"]
          ["\\[hb\\]" "haas-bioroid"]
          ["\\[jinteki\\]" "jinteki"]
          ["\\[nbn\\]" "nbn"]
          ["\\[shaper\\]" "shaper"]
          ["\\[sunny\\]" "sunny"]
          ["\\[weyland-consortium\\]" "weyland-consortium"]
          ["\\[weyland\\]" "weyland-consortium"]
        ]
        icon-span (fn [icon-class]
                    (str "<span class='anr-icon " icon-class "'></span>"))
        icon-replacer (fn [text [pattern icon-class]]
                        (s/replace text (re-pattern (str "(?i)" pattern)) (icon-span icon-class)))]
    (if card-text (reduce icon-replacer card-text icon-map) "")))
