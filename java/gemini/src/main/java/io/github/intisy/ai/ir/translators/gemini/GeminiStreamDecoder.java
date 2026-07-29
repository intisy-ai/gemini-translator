package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.IrUsage;
import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.stream.ContentBlockKind;
import io.github.intisy.ai.ir.stream.ContentBlockStartEvent;
import io.github.intisy.ai.ir.stream.ContentBlockStopEvent;
import io.github.intisy.ai.ir.stream.IrStreamEvent;
import io.github.intisy.ai.ir.stream.MessageDeltaEvent;
import io.github.intisy.ai.ir.stream.MessageStartEvent;
import io.github.intisy.ai.ir.stream.MessageStopEvent;
import io.github.intisy.ai.ir.stream.TextDeltaEvent;
import io.github.intisy.ai.ir.stream.ThinkingDeltaEvent;
import io.github.intisy.ai.ir.stream.ThinkingSignatureEvent;
import io.github.intisy.ai.ir.stream.ToolInputDeltaEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful {@code streamGenerateContent} ({@code alt=sse}) decoder. Each Gemini SSE frame is a
 * bare {@code data: <chunk>} line (no {@code event:} discriminator, unlike some other vendors'
 * SSE) carrying a (partial) {@code GenerateContentResponse} -- structurally identical to the
 * non-streaming response body ({@code candidates[].content.parts}/{@code finishReason}/
 * {@code usageMetadata}). Line-buffers across {@link #decode} calls exactly like other stream
 * decoders in this module.
 *
 * <p>Reuses the battle-tested per-part state machine from antigravity-auth's
 * {@code AntigravityStreamMapper.handleObj} (open/close a content block on
 * {@code functionCall}/{@code thought}/{@code text} transitions; a real Gemini
 * {@code functionCall} always arrives with its full {@code args} in one chunk, so its block opens
 * and closes within the same frame -- there is no incremental tool-input streaming to buffer, only
 * one {@link ToolInputDeltaEvent} per call). Also honors the same tool-use precedence: a
 * {@code functionCall} anywhere in the stream forces the final
 * {@link IrStopReason#TOOL_USE}, regardless of the terminal {@code finishReason}.
 *
 * <h2>End-of-stream signal</h2>
 * Gemini's SSE stream itself carries no explicit "done" frame (the connection just closes); by
 * API contract only the FINAL chunk of a candidate carries a non-null {@code finishReason} (and
 * the final cumulative {@code usageMetadata}), so this decoder treats a frame with a
 * {@code finishReason} as the terminal one and emits {@code content_block_stop} (if a block is
 * still open) + {@link MessageDeltaEvent} + {@link MessageStopEvent} from within that same
 * {@link #decode} call -- no separate close/flush hook is needed on the {@link StreamDecoder} SPI.
 */
final class GeminiStreamDecoder implements StreamDecoder {
    private static final String EXT_FINISH_REASON_RAW = "$finishReasonRaw";

    private final JsonCodec json;
    private final StringBuilder lineBuffer = new StringBuilder();
    private final StringBuilder dataBuffer = new StringBuilder();
    private boolean sawDataLine = false;

    private boolean started = false;
    private boolean blockOpen = false;
    private String blockKind = null; // ContentBlockKind.TEXT | TOOL_USE | THINKING
    private int index = -1;
    private boolean sawToolUse = false;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer thoughtsTokens;
    private Integer totalTokens;

    GeminiStreamDecoder(JsonCodec json) {
        this.json = json;
    }

    @Override
    public List<IrStreamEvent> decode(String chunk) {
        List<IrStreamEvent> out = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) return out;
        lineBuffer.append(chunk);
        int newlineIndex;
        while ((newlineIndex = lineBuffer.indexOf("\n")) >= 0) {
            String line = lineBuffer.substring(0, newlineIndex);
            lineBuffer.delete(0, newlineIndex + 1);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            processLine(line, out);
        }
        return out;
    }

    private void processLine(String line, List<IrStreamEvent> out) {
        if (line.isEmpty()) {
            flushFrame(out);
            return;
        }
        if (line.startsWith(":")) return; // SSE comment/keepalive
        if (line.startsWith("data:")) {
            String data = line.substring(5);
            if (data.startsWith(" ")) data = data.substring(1);
            if (sawDataLine) dataBuffer.append('\n');
            dataBuffer.append(data);
            sawDataLine = true;
        }
    }

    private void flushFrame(List<IrStreamEvent> out) {
        if (!sawDataLine) return;
        String data = dataBuffer.toString();
        dataBuffer.setLength(0);
        sawDataLine = false;
        if (data.isEmpty() || "[DONE]".equals(data)) return;
        Map<String, Object> frame = GeminiJsonUtil.asMap(json.parse(data));
        if (frame == null) return;
        handleFrame(frame, out);
    }

    private void handleFrame(Map<String, Object> frame, List<IrStreamEvent> out) {
        if (!started) {
            started = true;
            MessageStartEvent ev = new MessageStartEvent();
            ev.id = GeminiJsonUtil.asString(frame.get("responseId"));
            ev.model = GeminiJsonUtil.asString(frame.get("modelVersion"));
            ev.role = "assistant";
            out.add(ev);
        }

        Map<String, Object> usageMetadata = GeminiJsonUtil.asMap(frame.get("usageMetadata"));
        if (usageMetadata != null) {
            if (usageMetadata.get("promptTokenCount") != null) inputTokens = GeminiJsonUtil.asInt(usageMetadata.get("promptTokenCount"));
            if (usageMetadata.get("candidatesTokenCount") != null) outputTokens = GeminiJsonUtil.asInt(usageMetadata.get("candidatesTokenCount"));
            if (usageMetadata.get("thoughtsTokenCount") != null) thoughtsTokens = GeminiJsonUtil.asInt(usageMetadata.get("thoughtsTokenCount"));
            if (usageMetadata.get("totalTokenCount") != null) totalTokens = GeminiJsonUtil.asInt(usageMetadata.get("totalTokenCount"));
        }

        List<Object> candidates = GeminiJsonUtil.asList(frame.get("candidates"));
        Map<String, Object> candidate0 = (candidates == null || candidates.isEmpty()) ? null : GeminiJsonUtil.asMap(candidates.get(0));
        if (candidate0 == null) return;

        Map<String, Object> content = GeminiJsonUtil.asMap(candidate0.get("content"));
        List<Object> parts = content == null ? null : GeminiJsonUtil.asList(content.get("parts"));
        if (parts != null) {
            for (Object partObj : parts) {
                Map<String, Object> part = GeminiJsonUtil.asMap(partObj);
                if (part == null) continue;
                handlePart(part, out);
            }
        }

        String finishReason = GeminiJsonUtil.asString(candidate0.get("finishReason"));
        if (finishReason != null) {
            closeBlock(out);
            MessageDeltaEvent mde = new MessageDeltaEvent();
            mde.stopReason = sawToolUse ? IrStopReason.TOOL_USE : GeminiFinishReason.toIr(finishReason);
            mde.usage = buildUsage();
            putExtension(mde, EXT_FINISH_REASON_RAW, finishReason);
            out.add(mde);
            out.add(new MessageStopEvent());
        }
    }

    private void handlePart(Map<String, Object> part, List<IrStreamEvent> out) {
        if (part.get("functionCall") instanceof Map) {
            closeBlock(out);
            index++;
            blockOpen = true;
            blockKind = ContentBlockKind.TOOL_USE;
            Map<String, Object> fc = GeminiJsonUtil.asMap(part.get("functionCall"));
            String toolId = GeminiJsonUtil.asString(fc.get("id"));
            String toolName = GeminiJsonUtil.asString(fc.get("name"));
            ContentBlockStartEvent start = new ContentBlockStartEvent();
            start.index = index;
            start.blockKind = ContentBlockKind.TOOL_USE;
            start.toolUseId = toolId != null ? toolId : toolName;
            start.toolName = toolName;
            out.add(start);

            Object args = fc.get("args") != null ? fc.get("args") : new LinkedHashMap<>();
            ToolInputDeltaEvent delta = new ToolInputDeltaEvent();
            delta.index = index;
            delta.partialJson = json.stringify(args);
            out.add(delta);

            sawToolUse = true;
            closeBlock(out);
            return;
        }

        if (Boolean.TRUE.equals(part.get("thought"))) {
            String text = GeminiJsonUtil.asString(part.get("text"));
            boolean hasText = text != null && !text.isEmpty();
            if (hasText && !(blockOpen && ContentBlockKind.THINKING.equals(blockKind))) {
                openBlock(out, ContentBlockKind.THINKING);
            }
            if (hasText) {
                ThinkingDeltaEvent delta = new ThinkingDeltaEvent();
                delta.index = index;
                delta.text = text;
                out.add(delta);
            }
            String signature = GeminiJsonUtil.asString(part.get("thoughtSignature"));
            if (signature != null) {
                // A signature can arrive on its own trailing chunk (no text); it still needs an
                // open thinking block to attach to, matching a thinking_delta's index.
                if (!(blockOpen && ContentBlockKind.THINKING.equals(blockKind))) {
                    openBlock(out, ContentBlockKind.THINKING);
                }
                ThinkingSignatureEvent sig = new ThinkingSignatureEvent();
                sig.index = index;
                sig.signature = signature;
                out.add(sig);
            }
            return;
        }

        if (part.get("text") instanceof String && !((String) part.get("text")).isEmpty()) {
            if (!(blockOpen && ContentBlockKind.TEXT.equals(blockKind))) {
                openBlock(out, ContentBlockKind.TEXT);
            }
            TextDeltaEvent delta = new TextDeltaEvent();
            delta.index = index;
            delta.text = (String) part.get("text");
            out.add(delta);
        }
        // inlineData/fileData/other part kinds mid-stream: no IR stream-event home, dropped (lenient,
        // mirroring how other stream decoders in this module silently drop unrecognized frame types).
    }

    private void closeBlock(List<IrStreamEvent> out) {
        if (!blockOpen) return;
        ContentBlockStopEvent ev = new ContentBlockStopEvent();
        ev.index = index;
        out.add(ev);
        blockOpen = false;
        blockKind = null;
    }

    private void openBlock(List<IrStreamEvent> out, String kind) {
        closeBlock(out);
        index++;
        blockOpen = true;
        blockKind = kind;
        ContentBlockStartEvent start = new ContentBlockStartEvent();
        start.index = index;
        start.blockKind = kind;
        out.add(start);
    }

    private IrUsage buildUsage() {
        if (inputTokens == null && outputTokens == null && thoughtsTokens == null && totalTokens == null) return null;
        IrUsage usage = new IrUsage();
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        usage.reasoningTokens = thoughtsTokens;
        usage.totalTokens = totalTokens;
        return usage;
    }

    private static void putExtension(IrStreamEvent event, String key, Object value) {
        if (event.extensions == null) event.extensions = new LinkedHashMap<>();
        event.extensions.put(key, value);
    }
}
