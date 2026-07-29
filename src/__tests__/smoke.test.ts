import { describe, it, expect } from "vitest";
import { loadGeminiTranslator } from "../index.js";

describe("gemini-translator toolchain", () => {
  it("loads the TeaVM bundle and round-trips a request through the Java codec", async () => {
    const mod = await loadGeminiTranslator();
    const wireJson = JSON.stringify({
      contents: [{ role: "user", parts: [{ text: "hi" }] }],
      generationConfig: { maxOutputTokens: 16 },
    });
    const out = mod.geminiDecodeRequest(wireJson);
    expect(JSON.parse(out).maxTokens).toBe(16);
  });
});
