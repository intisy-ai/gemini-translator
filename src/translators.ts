import { loadGeminiTranslator } from "./index.js";
import { makeVendorTranslator } from "@intisy-ai/core-ir";

/**
 * The Gemini translator, as every consumer takes it.
 *
 * @remarks
 * Built by core-ir's `makeVendorTranslator`, so it loads the TeaVM module lazily on first use and
 * carries the synchronous handles the Java routing engine reaches it through.
 */
export const geminiTranslator = makeVendorTranslator(loadGeminiTranslator, {
  decodeRequest: (m) => m.geminiDecodeRequest,
  encodeRequest: (m) => m.geminiEncodeRequest,
  decodeResponse: (m) => m.geminiDecodeResponse,
  encodeResponse: (m) => m.geminiEncodeResponse,
  newStreamDecoder: (m) => m.geminiNewStreamDecoder,
  newStreamEncoder: (m) => m.geminiNewStreamEncoder,
});
