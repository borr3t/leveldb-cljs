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
  interop outside the single binding namespace** (`leveldb-cljs.binding`, Phase 2).
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
| 2 | `binding` (the only interop namespace), `codec` (`:utf8 :json :edn :buffer :view :hex :base64` + custom), `core` (open!/close!, get!/put!/del!/batch!/clear!, sublevels, snapshots), `batch` (chained batches) | pending |
| 3 | `iter` (core.async entries/keys/values channels, manual iterator handles, `events` channel) | pending |
| 4 | `admin` (approximate-size!, compact-range!, property, destroy!, repair!) | pending |
| 5 | live smoke on a temporary on-disk `classic-level` database (lock, create-if-missing, error-if-exists shapes; platform/prebuild report) | pending |

`./run-tests.sh` — 6 tests / 52 assertions (Phase 0), no disk, no network.

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

## Quick start (target API, Phase 2)

```clojure
(require '[leveldb-cljs.core :as db]
         '[promesa.core :as p])

(p/let [c (db/open! {:location "./data" :value-encoding :edn})]
  (p/let [_ (db/put! c "alice" {:score 620})
          v (db/get! c "alice")            ; {:score 620}
          _ (db/batch! c [{:type :put :key "bob" :value {:score 10}}
                          {:type :del :key "carol"}])
          ks (db/keys! c {:gte "a" :lt "c" :limit 10})]
    (println v ks)
    (db/close! c)))
```

## Development

```
./run-tests.sh                                  # exec's node at the end — never chain it mid-command
npx shadow-cljs watch dev && node target/dev.js # REPL runtime
```
