import { loadGeminiTranslator } from "./index.js";
import { makeVendorTranslator } from "@intisy-ai/core-ir";

export const geminiTranslator = makeVendorTranslator(loadGeminiTranslator, {
  decodeRequest: (m) => m.geminiDecodeRequest,
  encodeRequest: (m) => m.geminiEncodeRequest,
  decodeResponse: (m) => m.geminiDecodeResponse,
  encodeResponse: (m) => m.geminiEncodeResponse,
  newStreamDecoder: (m) => m.geminiNewStreamDecoder,
  newStreamEncoder: (m) => m.geminiNewStreamEncoder,
});
