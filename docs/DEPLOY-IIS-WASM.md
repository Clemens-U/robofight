# Deploy facts: serving the FIRELINE web console from IIS (Windows Server 2016)

Scope: `opcode-fireline.appsystems.at`, the planned `/play/` path (hosting option B,
`docs/FIRELINE-WEB.md` §7) and the W1 wasm artifacts. This page is the server-side
half of the W1 gate; the build side lives in `docs/WEB-W1-RECORD.md`.

Deployment model: **static file copy only**. No server-side runtime, no Node, no build
step on the server, and the landing page files are not touched.

## 1. Mandatory: register the `.wasm` MIME type

IIS refuses unknown static extensions (HTTP 404.3 "Unrecognized file extension"), so the
wasm file never loads and `WebAssembly.instantiateStreaming` never runs. Browsers also
require `Content-Type: application/wasm` for streaming instantiation.

Smallest safe change: a `web.config` inside the app directory (`/play/`), not the site root.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <system.webServer>
    <staticContent>
      <!-- remove first: a duplicate fileExtension throws 500.19 on IIS -->
      <remove fileExtension=".wasm" />
      <mimeMap fileExtension=".wasm" mimeType="application/wasm" />
    </staticContent>
  </system.webServer>
</configuration>
```

Server-wide alternative (IIS Manager → Sites → MIME Types → Add `.wasm` /
`application/wasm`), which writes the same mapping into `applicationHost.config`.
Prefer the per-directory file: it is reversible, scoped, and travels with the deploy.

Note `<remove>` before `<mimeMap>`: if a parent already maps `.wasm`, adding it again is a
configuration error, not a silent override.

Verified locally (2026-11-20, `docs/web/w1/`): the same wasm bundle loaded with
`Content-Type: application/wasm` runs the engine, and served as `application/octet-stream` the
browser refuses it with `TypeError: Failed to execute 'compile' on 'WebAssembly': Incorrect
response MIME type. Expected 'application/wasm'.` There is no browser-side workaround worth
relying on.

## 1b. Also check the `.mjs` entry glue

The Kotlin/wasm output is a small ES module (`<name>.mjs`, 217 bytes in the W1 build) that
imports the loader (`<name>.uninstantiated.mjs`) and the wasm file. Module scripts are refused
unless the server sends a JavaScript MIME type, and IIS versions differ on whether `.mjs` is in
the default MIME map. Check after the copy (`curl -sSI .../<name>.mjs`); if it 404s (404.3) or
returns something other than `text/javascript`, add it in the same `web.config`:

```xml
<staticContent>
  <remove fileExtension=".wasm" />
  <mimeMap fileExtension=".wasm" mimeType="application/wasm" />
  <remove fileExtension=".mjs" />
  <mimeMap fileExtension=".mjs" mimeType="text/javascript" />
</staticContent>
```

Files a deploy copies (W1 shape, W3 will add the console HTML/CSS): the `.wasm`, both `.mjs`
files and `custom-formatters.js`. The `.wasm.map` debug source map stays out of the deploy.

## 2. Verify after the copy (do this before opening a browser)

```bash
curl -sSI https://opcode-fireline.appsystems.at/play/<name>.wasm | grep -i -E 'HTTP/|content-type|content-encoding|cache-control'
```

Expected: `HTTP/1.1 200`, `Content-Type: application/wasm`. A `404.3` body means section 1
did not apply (wrong directory, or `web.config` ignored because the site's
`allowSubDirConfig` is off). A 200 with `Content-Type: application/octet-stream` is also a
failure for streaming instantiation.

## 3. Compression (gzip; brotli is not available)

IIS on Windows Server 2016 has no built-in brotli module; gzip is enough for a few-MB wasm
bundle. Two things to check, because IIS enables **dynamic** compression by default and
often leaves **static** compression disabled, and its default static scheme does not list
`.wasm`:

1. Static compression enabled (server level, IIS Manager → Compression → Compress static
   content, or `applicationHost.config`):

```xml
<system.webServer>
  <urlCompression doStaticCompression="true" doDynamicCompression="true" />
  <httpCompression>
    <staticTypes>
      <add mimeType="application/wasm" enabled="true" />
    </staticTypes>
  </httpCompression>
</system.webServer>
```

2. The `staticContent` scheme file (`%ProgramFiles%\IIS\Microsoft Web Output Cache\
   staticcontent.xml`) must include `<scheme fileExtension=".wasm" mimeType="application/wasm" />`.
   If you cannot edit that file, precompress at build time (ship `app.wasm.br`/`app.wasm.gz`
   is not served by stock IIS without extra mapping, so the practical fallback is: ship the
   plain wasm and accept the uncompressed transfer).

Verify: the `Content-Encoding: gzip` header from section 2, plus compare `Content-Length`
against the on-disk size.

## 4. Cache policy (makes "play again offline" work)

Static files are cached by IIS defaults only. Add explicit control headers for the app
directory so a returning player does not re-download the engine, and so a rebuild actually
replaces it:

- Name engine artifacts with a build-stamped suffix (`fireline-web.<build>.wasm`) and serve
  them with `Cache-Control: public, max-age=31536000, immutable`.
- Serve the HTML with `Cache-Control: no-cache` (or a short max-age) so a new build is picked up.

```xml
<system.webServer>
  <httpProtocol>
    <customHeaders>
      <add name="Cache-Control" value="public, max-age=31536000, immutable" />
    </customHeaders>
  </httpProtocol>
</system.webServer>
```

(That snippet applies to the directory it sits in; keep the HTML in a directory without it,
or use location-specific configuration.)

## 5. Transport security

HTTPS is already the access model and must stay: `WebAssembly` compilation and (later) any
service worker require a secure context. Confirm HSTS is sent on `/play/` as it is on the
landing page.

## 6. What must NOT change

- The landing page files and its existing `web.config` (if any) stay as they are; the app
  lives in its own directory.
- No server-side runtime, no Node install, no URL rewrite rules required for v1.
- No cookies, no auth, no server-side session for `/play/`.

## 7. W1 spike check on the real host (optional but decisive)

W1 artifacts are already produced locally (`docs/web/w1/`). Copy them to a temporary
directory on the server, verify section 2, and confirm the page prints
`W1 WASM RESULT: ALL ANCHORS OK` in a real browser on the production origin. That single
test proves MIME + compression + wasm delivery on the shipping host before any W3 UI work
is scheduled. Delete the temporary directory afterwards; it is not a product surface.

## 8. Browser floor (product decision, not a server setting)

Kotlin/Wasm needs WasmGC + JS exception handling. As of 2026 all mainstream browsers
qualify (Chrome/Edge 119+, Firefox 120+, Safari 18.2+). Older browsers must get a plain
HTML fallback message, not a broken console: detect with
`if (typeof WebAssembly !== 'undefined' && ...WasmGC feature check...)` and show the
"your browser is too old" panel otherwise. This belongs to W3/W4 UI work.
