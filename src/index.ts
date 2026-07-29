let modulePromise: Promise<typeof import("./generated/gemini-translator.teavm.js")> | null = null;

export function loadGeminiTranslator(): Promise<typeof import("./generated/gemini-translator.teavm.js")> {
  if (!modulePromise) {
    modulePromise = import("./generated/gemini-translator.teavm.js");
  }
  return modulePromise;
}

export * from "./translators.js";
// core-ir's own index.js also exports a runtime geminiTranslator/translators, so re-export
// only the IR type surface here to avoid a duplicate value export.
export * from "../core-ir/dist/types.js";
export type { VendorTranslator, StreamTranslator } from "../core-ir/dist/translators.js";
