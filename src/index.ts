let modulePromise: Promise<typeof import("./generated/gemini-translator.teavm.js")> | null = null;

/**
 * Loads the TeaVM-compiled Gemini translator module, once.
 *
 * @remarks
 * Concurrent callers share one import, so the module is instantiated exactly once per process.
 *
 * @returns the module, whose exports are the translator's own string functions
 */
export function loadGeminiTranslator(): Promise<typeof import("./generated/gemini-translator.teavm.js")> {
  if (!modulePromise) {
    modulePromise = import("./generated/gemini-translator.teavm.js");
  }
  return modulePromise;
}

export * from "./translators.js";
export * from "@intisy-ai/core-ir";
