# gemini-translator

Google Gemini `generateContent`/`streamGenerateContent` vendor translator for the canonical IR
(internal representation) used across the intisy AI-tooling ecosystem. Java + TeaVM single-source,
so the exact same request, response, and streaming codecs compile to a JVM jar and to a JS module:
any front-door or provider that needs to speak Gemini's wire format converts it to and from
`core-ir`'s neutral IR through one shared, tested implementation instead of a bespoke per-app
reimplementation.

## Under-the-Hood Architecture

```mermaid
flowchart LR
  WIRE[Gemini generateContent wire JSON] --> REQ[GeminiRequestCodec]
  WIRE --> RESP[GeminiResponseCodec]
  WIRE --> SSE[GeminiStreamDecoder / GeminiStreamEncoder]
  REQ --> TR[GeminiTranslator]
  RESP --> TR
  SSE --> TR
  IR[core-ir: IrRequest / IrResponse / IrStreamEvent] --> TR
  TR -->|":gemini" module| GEMINI[java/gemini]
  GEMINI -->|TeaVM generateJavaScript| GEN[java/teavm-gemini build/generated/teavm/js]
  GEN -->|teavm-build.mjs stage| STAGED[src/generated/gemini-translator.teavm.js]
  STAGED -->|tsc + esbuild| DIST[dist/index.js]
  DIST --> API["src/translators.ts: geminiTranslator"]
```

`GeminiTranslator` implements `core-ir`'s `Translator` SPI: `decodeRequest`/`encodeRequest`,
`decodeResponse`/`encodeResponse`, and stateful `newStreamDecoder()`/`newStreamEncoder()` for true
streaming (no buffer-and-reconvert). The `:gemini` module holds the codecs and is
zero-dependency, Java-8-clean; `:teavm-gemini` is the TeaVM export surface over `:gemini` and
the nested `:ir` module, transpiled to a single JS bundle. The TS surface (`geminiTranslator`)
is a thin async wrapper over that generated JS, so callers never touch the TeaVM handle directly.

## Structure

- `src/index.ts` - `loadGeminiTranslator()`, a lazily-memoized dynamic import of the TeaVM ESM
  bundle, plus the public barrel re-exporting `translators.ts` and `core-ir`'s IR types.
- `src/translators.ts` - the public, typed TS API: `geminiTranslator`, with
  `decodeRequest`/`encodeRequest`/`decodeResponse`/`encodeResponse` (thin async wrappers over the
  TeaVM exports) and `decodeStream()`/`encodeStream()`, which return a real `TransformStream`
  driven chunk-by-chunk by the stateful Java handle.
- `src/driver.ts` - a small CLI driver (`node dist/driver.js <payload.json>`) that decodes a wire
  request to IR and re-encodes it, useful for manual smoke checks.
- `src/generated/gemini-translator.teavm.d.ts` - hand-authored ambient types for the staged JS
  (the `.js` itself is gitignored build output).
- `src/__tests__/` - `smoke.test.ts` (toolchain round trip) and `translators.test.ts` (request and
  response round trips).
- `java/gemini/` - the Gemini codecs (`GeminiRequestCodec`, `GeminiResponseCodec`,
  `GeminiStreamDecoder`, `GeminiStreamEncoder`, `GeminiBlockCodec`, `GeminiUsageCodec`,
  `GeminiFinishReason`, `GeminiJsonUtil`) plus `GeminiTranslator`, the `Translator`
  implementation that ties them together. Depends on the nested `core-ir`'s `:ir` module for the
  IR types and the codec SPI.
- `java/teavm-gemini/` - the TeaVM JS export surface (`GeminiTranslatorJs`), transpiling
  `:gemini` and `:ir` to `gemini-translator.js`.
- `java/settings.gradle` / `java/build.gradle` / `java/gradlew*` - self-contained Gradle build
  (Java 8 for `:gemini`, Java 17 override for `:teavm-gemini`), re-declaring the nested `:ir`
  module's project path (Gradle settings do not nest across submodules).

## Installation

Via git submodule (the ecosystem convention for a `*-translator` repo consumed by a plugin):

```bash
git submodule add https://github.com/intisy-ai/gemini-translator.git gemini-translator
git submodule update --init --recursive
```

`gemini-translator` itself nests `core-ir` as a submodule, so a recursive submodule update is
required (`--init --recursive`, or `git submodule update --init --recursive` from the consuming
repo's root) to pull both levels before building. It is a submodule-consumed library like
`core-ir`/`core-proxy`, not an npm package, so there is no `npm install` step.

## Usage

```ts
import { geminiTranslator } from "gemini-translator";

const ir = await geminiTranslator.decodeRequest(wireJson);
const backToWire = await geminiTranslator.encodeRequest(ir);

const response = await geminiTranslator.decodeResponse(responseWireJson);
const wireResponse = await geminiTranslator.encodeResponse(response);

const decodeStream = await geminiTranslator.decodeStream();
const irEvents = upstreamSseBody.pipeThrough(decodeStream); // ReadableStream<IrStreamEvent>

const encodeStream = await geminiTranslator.encodeStream();
const wireSse = irEventStream.pipeThrough(encodeStream); // ReadableStream<string>
```

`geminiTranslator` satisfies `core-ir`'s `VendorTranslator` interface, so any front-door that
already speaks that interface for another vendor can adopt Gemini support by swapping in this
translator.

## Testing

Java: `cd java && ./gradlew test` (JUnit 5, `:gemini` module: request, response, and streaming
round-trip tests against fixture payloads, plus a cross-vendor test proving a canonical IR request
built independently of any Gemini wire input translates into a valid Gemini body).

TS: `npm run build && npx vitest run` (`build` stages the TeaVM JS, `tsc`s, then bundles with
esbuild; `test` round-trips the translator from TS). Both layers use the same round-trip fixture
approach: a captured Gemini wire payload decoded to IR and re-encoded, asserting the result matches
the original shape rather than a byte-identical string.

## License

MIT
