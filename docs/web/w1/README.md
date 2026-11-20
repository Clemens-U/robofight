# W1 hello-wasm harness (spike artifacts)

Part of the W1 gate in `docs/FIRELINE-WEB.md` §9. Full record: `docs/WEB-W1-RECORD.md`.

This directory holds the harness, not the engine output. The engine output is a Gradle
build product: it changes whenever the engine changes, so it is never committed here.

## Files

| file | role |
|---|---|
| `index.html` | harness page: loads the engine module, prints the anchor table into the DOM |
| `serve_local.py` | local static server with the content types a browser accepts (see below) |
| `README.md` | this page |

## Build the artifacts (repo root)

```bash
export JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew --console=plain :fireline-engine:compileDevelopmentExecutableKotlinWasmJs \
          :fireline-engine:wasmJsNodeDevelopmentRun
```

`wasmJsNodeDevelopmentRun` executes the same wasm bundle in Node and must print
`W1 WASM RESULT: ALL ANCHORS OK`. The wasm build configuration lives in
`gradle/wasm-toolchain.gradle` and `gradle.properties` (`kotlin.js.yarn=false`).

## Run the browser check

Copy these four files from the build output into this directory:

```
fireline-engine/build/compileSync/wasmJs/main/developmentExecutable/kotlin/
  opcode-arena-fireline-engine.wasm              engine, 1,233,651 bytes unoptimized
  opcode-arena-fireline-engine.mjs               entry glue (217 bytes)
  opcode-arena-fireline-engine.uninstantiated.mjs  loader, does the instantiateStreaming call
  custom-formatters.js                            Kotlin stdlib formatter shim
```

(`opcode-arena-fireline-engine.wasm.map` is a debug source map: local only, never deployed.)

```bash
python docs/web/w1/serve_local.py 8765
# then open http://127.0.0.1:8765/index.html
```

Expected page content, in order: `[wasm Content-Type] application/wasm`, the engine banner,
eleven `OK` anchor lines, and `W1 WASM RESULT: ALL ANCHORS OK`.

## Negative control (why the server config matters)

```bash
python docs/web/w1/serve_local.py 8766 application/octet-stream
# open http://127.0.0.1:8766/index.html
```

Observed (2026-11-20, Chromium/Edge 157): status `module load failed` and

```
TypeError: Failed to execute 'compile' on 'WebAssembly': Incorrect response MIME type.
Expected 'application/wasm'.
```

This is the exact failure a static host produces when `.wasm` is not mapped, which is why
`docs/DEPLOY-IIS-WASM.md` §1 is mandatory rather than advisory.
