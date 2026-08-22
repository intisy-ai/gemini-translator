package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.stream.ContentBlockKind;
import io.github.intisy.ai.ir.stream.ContentBlockStartEvent;
import io.github.intisy.ai.ir.stream.ContentBlockStopEvent;
import io.github.intisy.ai.ir.stream.ErrorEvent;
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
 * Stateful encoder from canonical {@link IrStreamEvent}s back to Gemini
 * {@code streamGenerateContent} SSE frames ({@code data: <chunk>\n\n}, no {@code event:} line).
 *
 * <p>Unlike some other vendors' fine-grained event protocols, Gemini's stream carries no explicit
 * start/stop markers -- {@link MessageStartEvent}, {@link ContentBlockStartEvent} and
 * {@link MessageStopEvent} have no wire analog and emit no frame (empty string); a real Gemini
 * {@code functionCall} always arrives with its whole {@code args} in one chunk, so
 * {@link ToolInputDeltaEvent} is buffered per open tool-use block and only flushed as a single
 * {@code functionCall} frame at the matching {@link ContentBlockStopEvent} (mirroring
 * {@code GeminiStreamDecoder}'s mirror-image handling and antigravity-auth's
 * {@code AntigravityStreamMapper}, which likewise emits a function call's args as one complete
 * delta). {@link MessageDeltaEvent} is the natural home for the terminal chunk carrying
 * {@code finishReason} + the cumulative {@code usageMetadata}, matching real Gemini streaming
 * behavior where only the last chunk populates those fields.
 */
final class GeminiStreamEncoder implements StreamEncoder {
    private final JsonCodec json;

    private String model;
    private String responseId;
    private String openToolUseId;
    private String openToolName;
    private final StringBuilder toolArgsBuffer = new StringBuilder();
    private boolean toolBlockOpen = false;

    GeminiStreamEncoder(JsonCodec json) {
        this.json = json;
    }

    @Override
    public String encode(IrStreamEvent event) {
        if (event instanceof MessageStartEvent) {
            MessageStartEvent ev = (MessageStartEvent) event;
            this.responseId = ev.id;
            this.model = ev.model;
            return "";
        }
        if (event instanceof ContentBlockStartEvent) {
            ContentBlockStartEvent ev = (ContentBlockStartEvent) event;
            if (ContentBlockKind.TOOL_USE.equals(ev.blockKind)) {
                toolBlockOpen = true;
                openToolUseId = ev.toolUseId;
                openToolName = ev.toolName;
                toolArgsBuffer.setLength(0);
            }
            return "";
        }
        if (event instanceof TextDeltaEvent) {
            TextDeltaEvent ev = (TextDeltaEvent) event;
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("text", ev.text);
            return frame(candidateChunk(part, null));
        }
        if (event instanceof ThinkingDeltaEvent) {
            ThinkingDeltaEvent ev = (ThinkingDeltaEvent) event;
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("thought", Boolean.TRUE);
            part.put("text", ev.text);
            return frame(candidateChunk(part, null));
        }
        if (event instanceof ThinkingSignatureEvent) {
            ThinkingSignatureEvent ev = (ThinkingSignatureEvent) event;
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("thought", Boolean.TRUE);
            part.put("text", "");
            part.put("thoughtSignature", ev.signature);
            return frame(candidateChunk(part, null));
        }
        if (event instanceof ToolInputDeltaEvent) {
            ToolInputDeltaEvent ev = (ToolInputDeltaEvent) event;
            if (ev.partialJson != null) toolArgsBuffer.append(ev.partialJson);
            return "";
        }
        if (event instanceof ContentBlockStopEvent) {
            if (!toolBlockOpen) return "";
            toolBlockOpen = false;
            Object args = toolArgsBuffer.length() > 0 ? json.parse(toolArgsBuffer.toString()) : new LinkedHashMap<>();
            toolArgsBuffer.setLength(0);
            Map<String, Object> fc = new LinkedHashMap<>();
            if (openToolUseId != null) fc.put("id", openToolUseId);
            fc.put("name", openToolName);
            fc.put("args", args);
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("functionCall", fc);
            return frame(candidateChunk(part, null));
        }
        if (event instanceof MessageDeltaEvent) {
            return frame(encodeMessageDelta((MessageDeltaEvent) event));
        }
        if (event instanceof MessageStopEvent) {
            return "";
        }
        if (event instanceof ErrorEvent) {
            return frame(encodeError((ErrorEvent) event));
        }
        throw new IllegalArgumentException("unsupported IrStreamEvent type: " + event.getClass());
    }

    private Map<String, Object> candidateChunk(Map<String, Object> part, String finishReason) {
        List<Object> parts = new ArrayList<>();
        parts.add(part);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", parts);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        if (finishReason != null) candidate.put("finishReason", finishReason);
        candidate.put("index", 0);
        Map<String, Object> data = new LinkedHashMap<>();
        if (responseId != null) data.put("responseId", responseId);
        List<Object> candidates = new ArrayList<>();
        candidates.add(candidate);
        data.put("candidates", candidates);
        if (model != null) data.put("modelVersion", model);
        return data;
    }

    private Map<String, Object> encodeMessageDelta(MessageDeltaEvent ev) {
        Object rawFinishReason = ev.extensions == null ? null : ev.extensions.get("$finishReasonRaw");
        String finishReason = rawFinishReason instanceof String ? (String) rawFinishReason : GeminiFinishReason.toGemini(ev.stopReason);

        Map<String, Object> emptyPart = new LinkedHashMap<>();
        emptyPart.put("text", "");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", new ArrayList<>());
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        candidate.put("finishReason", finishReason);
        candidate.put("index", 0);

        Map<String, Object> data = new LinkedHashMap<>();
        if (responseId != null) data.put("responseId", responseId);
        List<Object> candidates = new ArrayList<>();
        candidates.add(candidate);
        data.put("candidates", candidates);

        if (ev.usage != null) {
            data.put("usageMetadata", GeminiUsageCodec.encode(ev.usage));
        }
        if (model != null) data.put("modelVersion", model);
        return data;
    }

    private Map<String, Object> encodeError(ErrorEvent ev) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", ev.message);
        error.put("status", ev.errorType);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", error);
        return data;
    }

    private String frame(Map<String, Object> data) {
        return "data: " + json.stringify(data) + "\n\n";
    }
}
