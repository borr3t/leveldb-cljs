(ns leveldb-cljs.util
  "Small shared helpers: keyword case translation between kebab-case
   ClojureScript keywords and the camelCase option / manifest / event names
   of abstract-level, upstream LEVEL_* error-code translation, and
   ex-info error construction and detection.

   Pure: no I/O and no JavaScript interop. Converting CLJS maps to JS option
   objects (and reading `code`/`message`/`cause` off a thrown JS error) is
   interop and therefore lives in the single binding namespace
   (leveldb-cljs.binding, Phase 2); this namespace only deals in strings,
   keywords and maps. Phase 2 adds range-option normalisation and codec
   descriptors here (inventory §13)."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; kebab-case <-> camelCase (inventory 13.1: abstract-level options, the
;; db.supports manifest and `write` event operation objects are camelCase)
;; ---------------------------------------------------------------------------

(defn kebab->camel
  "\"key-encoding\" -> \"keyEncoding\". Keywords render as their name (the
   namespace is dropped); strings pass through untouched (they are assumed
   to be exact upstream names already). Single-word names are unchanged
   (:gte -> \"gte\")."
  [k]
  (cond
    (keyword? k) (let [[head & tail] (str/split (name k) #"-")]
                   (apply str head (map str/capitalize tail)))
    (string? k) k
    :else (str k)))

(defn camel->kebab
  "\"keyEncoding\" -> :key-encoding, \"highWaterMarkBytes\" ->
   :high-water-mark-bytes. Works on strings and keywords, returns a keyword.
   Namespaced keywords are returned unchanged."
  [k]
  (if (and (keyword? k) (namespace k))
    k
    (-> (name k)
        (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
        str/lower-case
        keyword)))

(defn transform-keys
  "Recursively applies `f` to every map key in `x` (maps inside vectors/lists
   included). Keys listed in `passthrough` (a set) keep their subtree
   untouched — user-owned values (the `value` of a put, a custom encoding
   object) must never be re-cased."
  ([f x] (transform-keys f #{} x))
  ([f passthrough x]
   (cond
     (map? x) (into (empty x)
                    (map (fn [[k v]]
                           (if (contains? passthrough k)
                             [(f k) v]
                             [(f k) (transform-keys f passthrough v)])))
                    x)
     (sequential? x) (into (empty x) (map #(transform-keys f passthrough %)) x)
     :else x)))

(def option-passthrough-keys
  "Kebab keys whose subtree is handed to abstract-level verbatim: user data
   and opaque upstream objects (inventory 13.1)."
  #{:value :key :gt :gte :lt :lte :snapshot :sublevel :signal})

(defn ->camel-keys
  "Kebab keyword option map -> string-keyed camelCase map, nil values
   dropped, nested option maps translated, subtrees under
   option-passthrough-keys untouched. nil in -> nil out. Pure: the result is
   still a CLJS map; the binding namespace turns it into a JS object."
  [opts]
  (when (some? opts)
    (transform-keys kebab->camel option-passthrough-keys
                    (into {} (remove (comp nil? val)) opts))))

(defn ->kebab-keys
  "Deeply translates camelCase string/keyword keys (as js->clj yields them)
   to kebab keywords; values are never touched."
  [x]
  (transform-keys camel->kebab x))

;; ---------------------------------------------------------------------------
;; Upstream error codes (inventory §9: LEVEL_* codes are the semver contract)
;; ---------------------------------------------------------------------------

(defn level-code->keyword
  "\"LEVEL_DATABASE_NOT_OPEN\" -> :database-not-open. The LEVEL_ prefix is
   dropped; other uppercase codes (\"ERR_X\") are kebab-cased as-is; keywords
   pass through; nil/blank -> nil."
  [code]
  (cond
    (keyword? code) code
    (and (string? code) (not (str/blank? code)))
    (-> code
        (str/replace #"^LEVEL_" "")
        str/lower-case
        (str/replace "_" "-")
        keyword)
    :else nil))

(def not-open-codes
  "Codes meaning a resource was used after close (or before a failed open)."
  #{:database-not-open :iterator-not-open :batch-not-open :snapshot-not-open})

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(def error-types
  "Every :type a leveldb-cljs ex-info can carry."
  #{:leveldb-cljs/db-error :leveldb-cljs/argument-error :leveldb-cljs/codec-error})

(defn db-error
  "ex-info for an error raised by abstract-level / classic-level (inventory
   §9). `code` is the upstream LEVEL_* string or its keyword form; `message`
   the upstream message. Data: {:type :leveldb-cljs/db-error :code <kebab
   keyword or nil> :message .. :cause <original error, if any> ..}."
  [code message & [data]]
  (let [code (level-code->keyword code)]
    (ex-info (str "leveldb-cljs" (when code (str " " (name code))) ": " message)
             (merge {:type :leveldb-cljs/db-error
                     :code code
                     :message message}
                    data))))

(defn argument-error
  "ex-info for an invalid argument detected before any database call."
  [message & [data]]
  (ex-info (str "leveldb-cljs: " message)
           (merge {:type :leveldb-cljs/argument-error} data)))

(defn codec-error
  "ex-info for an encode/decode failure inside a leveldb-cljs codec (the
   :edn / :transit value codecs of Phase 2). Upstream decode failures keep
   :leveldb-cljs/db-error with :code :decode-error."
  [message & [data]]
  (ex-info (str "leveldb-cljs codec: " message)
           (merge {:type :leveldb-cljs/codec-error} data)))

(defn error-data
  "Returns the structured data of a leveldb-cljs error, or nil when `e` is
   not one."
  [e]
  (when-let [d (ex-data e)]
    (when (contains? error-types (:type d))
      d)))

(defn error-type
  "One of the leveldb-cljs error :type keywords (see error-types) or nil."
  [e]
  (:type (error-data e)))

(defn error-code
  "The kebab keyword form of the upstream LEVEL_* code of a
   :leveldb-cljs/db-error, nil otherwise."
  [e]
  (when (= :leveldb-cljs/db-error (error-type e))
    (:code (error-data e))))

(defn not-open?
  "True when `e` reports use of a closed database/iterator/batch/snapshot."
  [e]
  (contains? not-open-codes (error-code e)))
