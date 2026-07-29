import { describe, expect, it } from "vitest";
import { geminiTranslator } from "../index.js";

describe("gemini translator", () => {
  it("round-trips a request through decode->encode", async () => {
    const wire = JSON.stringify({
      contents: [{ role: "user", parts: [{ text: "What is the weather in Berlin?" }] }],
      generationConfig: { maxOutputTokens: 256, temperature: 0.5 },
    });

    const ir = await geminiTranslator.decodeRequest(wire);
    expect(ir.maxTokens).toBe(256);
    expect(ir.temperature).toBe(0.5);
    expect(ir.messages).toHaveLength(1);

    const reEncoded = await geminiTranslator.encodeRequest(ir);
    expect(JSON.parse(reEncoded)).toEqual(JSON.parse(wire));
  });

  it("round-trips a response with reasoning/total token usage", async () => {
    const wire = JSON.stringify({
      candidates: [{ content: { role: "model", parts: [{ text: "18C, cloudy" }] }, finishReason: "STOP", index: 0 }],
      usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 4, totalTokenCount: 14, thoughtsTokenCount: 3 },
    });

    const ir = await geminiTranslator.decodeResponse(wire);
    expect(ir.usage?.reasoningTokens).toBe(3);
    expect(ir.usage?.totalTokens).toBe(14);

    const reEncoded = await geminiTranslator.encodeResponse(ir);
    expect(JSON.parse(reEncoded)).toEqual(JSON.parse(wire));
  });
});
