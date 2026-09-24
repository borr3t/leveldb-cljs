(ns leveldb-cljs.test-util
  "Shared fixtures for the leveldb-cljs tests. Not a test namespace itself
   (the name does not end in -test). Phase 2 adds an in-process memory-level
   database factory here (inventory §1.4) so tests never touch the disk or
   the network."
  (:require [promesa.core :as p]))

(defn rejection
  "Promise of the rejection reason of `p` (fails the assertion if it resolves)."
  ;; NB: promesa treats a js/Error RETURNED from a catch handler as a
  ;; rejection (IPromiseFactory on js/Error), so the error must be wrapped in
  ;; an already-resolved promise to travel as a value.
  [promise]
  (-> promise
      (p/then (fn [v] (throw (ex-info "expected rejection" {:resolved v}))))
      (p/catch (fn [e] (p/resolved e)))))

(defn thrown
  "The value thrown by (f), nil when it returns normally."
  [f]
  (try (f) nil (catch :default e e)))
