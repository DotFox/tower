(ns cljs.taoensso.tower
  "EXPERIMENTAL ClojureScript support for Tower.
  PRE-alpha - almost certain to change."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [goog.string    :as gstr]
            [goog.string.format])
  (:require-macros [cljs.taoensso.tower :as tower-macros]))

;;;; Utils ; TODO Move to a utils ns?

(defn- ^:crossover fq-name
  [x] (if (string? x) x
          (let [n (name x)]
            (if-let [ns (namespace x)] (str ns "/" n) n))))

(defn- ^:crossover explode-keyword [k] (str/split (fq-name k) #"[\./]"))
(defn- ^:crossover merge-keywords  [ks & [as-ns?]]
  (let [parts (->> ks (filterv identity) (mapv explode-keyword) (reduce into []))]
    (when-not (empty? parts)
      (if as-ns? ; Don't terminate with /
        (keyword (str/join "." parts))
        (let [ppop (pop parts)]
          (keyword (when-not (empty? ppop) (str/join "." ppop))
                   (peek parts)))))))

(def ^:crossover scoped (memoize (fn [& ks] (merge-keywords ks))))

;; TODO This fn (unlike the JVM's formatter) is locale unaware. Try find an
;; alternative that _is_:
(defn- fmt-str "Removed from cljs.core 0.0-1885, Ref. http://goo.gl/su7Xkj"
  [fmt & args] (apply gstr/format fmt args))

;;;; Config

(def ^:dynamic *locale* nil)
(def ^:dynamic *tscope* nil)

(def ^:crossover locale-key
  ;; Careful - subtle diff from jvm version:
  (memoize #(keyword (str/replace (name %) #_(str (locale %)) "_" "-"))))

(def locale locale-key)

;;;; Localization ; TODO

;;;; Translations

(comment ; Dictionaries
  (def my-dict-inline   (tower-macros/dict-compile {:en {:a "**hello**"}}))
  (def my-dict-resource (tower-macros/dict-compile "slurps/i18n/utils.clj")))

(def ^:crossover loc-tree
  (memoize ; Also used runtime by `translate` fn
   (fn [loc]
     (let [loc-parts (str/split (-> loc locale-key name) #"[-_]")
           loc-tree  (mapv #(keyword (str/join "-" %))
                           (take-while identity (iterate butlast loc-parts)))]
       loc-tree))))

(defn translate [loc config scope k-or-ks & fmt-args]

  (assert (:compiled-dictionary config)
    "Missing Cljs config key: :compiled-dictionary")
  (assert (not (:dictionary config))
    "Invalid Cljs config key: :dictionary")

  (let [{:keys [compiled-dictionary fallback-locale log-missing-translation-fn
                fmt-fn #_dev-mode?]

         :or   {fallback-locale :en
                fmt-fn fmt-str}} config

        dict   compiled-dictionary
        ks     (if (vector? k-or-ks) k-or-ks [k-or-ks])

        get-tr*  (fn [k l] (get-in dict [              k  l])) ; Unscoped k
        get-tr   (fn [k l] (get-in dict [(scoped scope k) l])) ; Scoped k
        find-tr* (fn [k l] (some #(get-tr* k %) (loc-tree l))) ; Try loc & parents
        find-tr  (fn [k l] (some #(get-tr  k %) (loc-tree l))) ; ''

        tr
        (or (some #(find-tr % loc) (take-while keyword? ks)) ; Try loc & parents
            (let [last-k (peek ks)]
              (if-not (keyword? last-k)
                last-k ; Explicit final, non-keyword fallback (may be nil)

                (do (when-let [log-f log-missing-translation-fn]
                      (log-f {;; :ns (str *ns*) ; ??
                              :locale loc :scope scope :ks ks}))
                    (or
                     ;; Try fallback-locale & parents
                     (some #(find-tr % fallback-locale) ks)

                     ;; Try :missing key in loc, parents, fallback-loc, & parents
                     (when-let [pattern (or (find-tr* :missing loc)
                                            (find-tr* :missing fallback-locale))]
                       (let [str* #(if (nil? %) "nil" (str %))]
                         (fmt-fn loc pattern (str* loc) (str* scope) (str* ks)))))))))]

    (if (nil? fmt-args) tr
      (if (nil? tr) (throw (js/Error. "Can't format nil translation pattern."))
        (apply fmt-fn loc tr fmt-args)))))

(defn t [loc config k-or-ks & fmt-args]
  (apply translate loc config *tscope* k-or-ks fmt-args))