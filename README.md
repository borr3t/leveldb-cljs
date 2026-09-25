# leveldb-cljs

ClojureScript (Node.js) client for LevelDB through the `level` npm package
(`level@10` re-exports `classic-level@3`, the Node-API LevelDB binding on
`abstract-level@3`). Lexicographically sorted key-value store with atomic batches,
snapshot-isolated iterators, sublevels and pluggable encodings, exposed as plain
ClojureScript: kebab-case keyword maps in and out, promesa promises for every
operation, core.async channels for iterators and events.

The authoritative spec is `docs/leveldb-api-inventory.org` (research against the
upstream READMEs, upgrade guides and live probes of the installed packages,
2026-09-25). Zero runtime npm dependencies beyond `level` itself; `memory-level`
is a devDependency so the tests never touch the disk or the network.

## Hard style rules (non-negotiable)

- ClojureScript only at every call site: **no `^js` type hints, no JavaScript
  interop outside the single binding namespace** (`leveldb-cljs.core`, which is
  both the binding and the client; `codec` only calls `JSON.stringify`/`parse`,
  `iter` is core.async over `core` handles).
- Kebab-case keyword maps in and out (`:key-encoding`, `:create-if-missing`,
  `:implicit-snapshots`); upstream camelCase never leaks.
- Async operations return promesa promises; iterators and events are core.async
  channels.
- Zero runtime npm dependencies beyond the wrapped package (`level`).
- Errors are `ex-info` values with `:type` in `#{:leveldb-cljs/db-error
  :leveldb-cljs/argument-error :leveldb-cljs/codec-error}`; upstream `LEVEL_*`
  codes become `:code` keywords (`:database-not-open`, `:locked`, `:decode-error`).

## Status

| Phase | Content | State |
|-------|---------|-------|
| 0 | scaffold: shadow-cljs `:node-test` / `:dev` builds, `run-tests.sh`, `util` (case + error-code translation, error constructors) | done |
| 1 | API inventory (`docs/leveldb-api-inventory.org`): package choice, API census, error taxonomy, encodings, iterator/batch/snapshot subtleties, pitfalls, uncertainties, Phase 2 outline | done |
| 2 | `core` (the only interop namespace + client: open!/close!/status/supports, get!/get-sync/get-many!/has!/has-many!, put!/del!/batch!/clear!, chained batches, sublevels with reopen re-derivation + root-identity checks, snapshots, iterator handles, bytes helpers), `codec` (`:utf8 :json :edn :hex :base64 :buffer :view :raw` + custom maps; codec discipline) | done |
| 3 | `iter` (core.async `entries!`/`keys!`/`values!` channels with backpressure and close-to-cancel, `collect!`/`reduce!`/`entries-seq!`) | done |
| 4 | `events!` channel in `core` (`:opening :open :closing :closed :put :del :batch :clear` derived from the upstream `write` / `clear` events, decoded with the client codecs, close-to-unsubscribe) + the live smoke `dev/leveldb_cljs/smoke.cljs` (`:smoke` build: a real on-disk `classic-level` database under the OS temp dir next to a memory-level one; platform/prebuild report, lock, round-trips, batches, iterators, sublevels, persistence, events, codec edges — 8/8 sections) | done |
| 5 | user manual `docs/leveldb-cljs.org` (13 chapters + API index) and this README | done |

`./run-tests.sh` — 67 tests / 838 assertions: memory-level in-process plus a temporary
on-disk `level` database per test (created under the OS temp dir and removed afterwards),
no network, about 3 s after the compile.

classic-level's admin extras (`approximateSize`, `compactRange`, `getProperty`, `destroy`,
`repair`; inventory 2.11 / 11.6) are deliberately not exposed in this release (manual,
"Design decisions").

## Documentation

- [`docs/leveldb-cljs.org`](docs/leveldb-cljs.org) — the user manual: install and quick
  start, clients, codecs, basic operations, batches, sublevels, iterators, the events
  channel, `clear!` and persistence, error handling, design decisions, testing and the
  live smoke, and a per-namespace API index.
- [`docs/leveldb-api-inventory.org`](docs/leveldb-api-inventory.org) — the research the
  library is built on: the upstream API census, error taxonomy, encodings, iterator /
  batch / snapshot subtleties, events, pitfalls and the uncertainties with the house
  position on each (section numbers quoted in docstrings and in the manual point here).

## Upstream facts that shape the design (see the inventory)

- `level` is 10.x: promises only, `get` of a missing key resolves `undefined`
  (mapped to `nil`), no `NotFoundError`, no `put`/`del`/`batch` events (only `write`
  and `clear`), explicit snapshots, `has`/`hasMany`, `getSync`.
- Chained batches require an open database; `open!` therefore returns an already
  opened client.
- Errors arrive both as synchronous throws and as rejections upstream; the binding
  normalizes everything to rejected promesa promises carrying `:code`.
- Test backend: `memory-level@3.1.0` (`memdown` is deprecated, `memorydown` does not
  exist).

## Quick start

```clojure
(require '[leveldb-cljs.core :as db]
         '[leveldb-cljs.iter :as iter]
         '[cljs.core.async :as async :refer [<! go-loop]]
         '[promesa.core :as p])

(p/let [c (db/open! {:path "./data" :value-encoding :edn})   ; awaits open
        _ (db/put! c "alice" {:score 620})
        v (db/get! c "alice")                                ; {:score 620}; nil when absent
        _ (db/batch! c [{:type :put :key "bob" :value {:score 10}}
                        {:type :del :key "carol"}])
        ks (db/keys! c {:gte "a" :lt "c" :limit 10})         ; vector
        people (db/sublevel c "people")                      ; namespaced keys, same codecs
        _ (db/put! people "dana" {:score 1})]
  (println v ks)
  (let [ch (iter/entries! c {:gte "a"})]                     ; lazy, backpressured channel
    (go-loop []
      (when-some [{:keys [key value] :as item} (<! ch)]
        (if (iter/error? item) (println "failed:" (ex-message item)) (println key value))
        (recur))))
  (db/close! c))
```

Errors: every rejection is an `ex-info` — check `(db/error-code e)` (`:database-not-open`,
`:locked`, `:iterator-busy` …), `(db/error-type e)` or `(db/not-open? e)`. `get-sync`
blocks the event loop (documented); `clear!` is not atomic (upstream). Closing a
channel from `iter` cancels and releases the upstream iterator; `db/open-resources`
reports still-open iterators/snapshots/batches/subscriptions.

Events: `(db/events! c {:only #{:put :del :batch}})` is a core.async channel of
`{:event :put :key k :value v}` / `{:event :del :key k}` / `{:event :batch :ops [...]}` /
`{:event :clear ...}` plus the lifecycle events, in emission order; it closes after
`{:event :closed}` and closing it yourself unsubscribes.

## Development

```
./run-tests.sh                                        # exec's node at the end — never chain it mid-command
npx shadow-cljs compile smoke && node target/smoke.js # LIVE smoke: real on-disk database in a temp dir, no network; exit 0 = all sections passed
npx shadow-cljs watch dev && node target/dev.js       # REPL runtime
```
