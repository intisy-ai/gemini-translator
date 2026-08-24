let modulePromise: Promise<typeof import("./generated/gemini-translator.teavm.js")> | null = null;

export function loadGeminiTranslator(): Promise<typeof import("./generated/gemini-translator.teavm.js")> {
  if (!modulePromise) {
    modulePromise = import("./generated/gemini-translator.teavm.js");
  }
  return modulePromise;
}

export * from "./translators.js";
export * from "@intisy-ai/core-ir";
