# Web runtime development

Production behavior is documented in [web-runtime.md](../../docs/web-runtime.md).
The domain-driven implementation sequence and ownership decisions are in
[helper-compatibility.md](../../docs/reference/helper-compatibility.md).

## Contract inventory

```powershell
npm ci
node audit-contract.mjs <helper-checkout>
node audit-contract.mjs <helper-checkout> --check
```

`compatibility-domains.json` assigns every declaration file to an owner and delivery
batch. `compatibility-catalog.json` records declarations, first-level exported object
members, facade names, actual registration aliases, source hashes, direct source
imports, and the current Player binding shape. The generator reads local source; it
does not execute the helper or author scripts or fetch dependencies.

Binding presence is **not** API conformance. Rejecting stubs and absent bindings are
distinct. Declaration, facade and runtime registration counts overlap and include
constants and fields; never add them together or report a compatibility percentage.
Domain status is reviewed manually. New unmapped declaration files cause an error.

The inventory is a maintenance artifact, not a runtime version gate. On upstream
changes, regenerate and review the difference. No local checkout paths are stored.

## Verification

```powershell
npm test
npm run build
npm run test:browser
```

`test/browser/session-contract.test.mjs` is an architecture proof of a same-origin
author session coordinator separated from the trusted shell. Production now uses the coordinator in `src/session.mjs`; this isolated proof
does not establish full helper event semantics. The other
browser tests exercise the production JS bundle; native calls are simulated.

The optional C-05 test locates its original JSON by hash in the local `source/`
directory and explicitly skips if unavailable. Neither original content nor local
filenames belong in committed test reports. Android WebView and complete generation
flows require separate app/device verification.

## Performance research

After `npm run build`, run `node performance-probe.mjs` from this directory.
It measures both production author-session snapshot and delta paths and instruments an in-memory
build of the production shell to count Markdown parsing during draft and tail-message
updates. All messages are synthetic and all bridge/resource responses are local.
It uses installed Edge by default; `PROBE_BROWSER_CHANNEL` can select another installed
Playwright Chromium channel. Output goes to `build/performance/probe.json`.

These desktop measurements exclude Android, disk persistence, providers and real author
programs. Shell queue completion is not frame presentation or coordinator completion.
Results have no CI timing threshold. It asserts zero historical Markdown parses for draft
updates and one parse per changed tail message, and checks that the author coordinator
eventually receives the final delta. The original baseline is recorded in
[the performance study](../../docs/performance-study-20260914.md); implemented changes are in
[the improvement record](../../docs/performance-improvements-20260915.md).
