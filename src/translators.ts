// TS-facing translator API: a thin wrapper over the TeaVM-generated JS (see src/index.ts's
// loadGeminiTranslator()). Non-streaming calls are one round trip through the Java translator
// per call; streaming calls hand back a real TransformStream driven chunk-by-chunk by a stateful
// Java-side handle (newStreamDecoder/newStreamEncoder): all SSE line-buffering and event-building
// decisions run in Java, this shell only owns bytes-in/JSON-out.

import { loadGeminiTranslator } from "./index.js";
import type { IrRequest, IrResponse, IrStreamEvent, VendorTranslator } from "../core-ir/dist/index.js";

function makeDecodeStream(handle: { decode(chunk: string): string }): TransformStream<Uint8Array | string, IrStreamEvent> {
  const textDecoder = new TextDecoder();
  return new TransformStream({
    transform(chunk, controller) {
      const text = typeof chunk === "string" ? chunk : textDecoder.decode(chunk, { stream: true });
      const events: IrStreamEvent[] = JSON.parse(handle.decode(text));
      for (const event of events) controller.enqueue(event);
    },
  });
}

function makeEncodeStream(handle: { encode(irEventJson: string): string }): TransformStream<IrStreamEvent, string> {
  return new TransformStream({
    transform(event, controller) {
      const wire = handle.encode(JSON.stringify(event));
      if (wire) controller.enqueue(wire);
    },
  });
}

export const geminiTranslator: VendorTranslator = {
  async decodeRequest(wireJson) {
    const mod = await loadGeminiTranslator();
    return JSON.parse(mod.geminiDecodeRequest(wireJson));
  },
  async encodeRequest(request: IrRequest) {
    const mod = await loadGeminiTranslator();
    return mod.geminiEncodeRequest(JSON.stringify(request));
  },
  async decodeResponse(wireJson) {
    const mod = await loadGeminiTranslator();
    return JSON.parse(mod.geminiDecodeResponse(wireJson));
  },
  async encodeResponse(response: IrResponse) {
    const mod = await loadGeminiTranslator();
    return mod.geminiEncodeResponse(JSON.stringify(response));
  },
  async decodeStream() {
    const mod = await loadGeminiTranslator();
    return makeDecodeStream(mod.geminiNewStreamDecoder());
  },
  async encodeStream() {
    const mod = await loadGeminiTranslator();
    return makeEncodeStream(mod.geminiNewStreamEncoder());
  },
};
