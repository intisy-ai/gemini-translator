package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.json.TestJsonCodec;
import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-vector test for a streamed {@code streamGenerateContent} ({@code alt=sse}) response:
 * a {@code thought} block (with {@code thoughtSignature}) -> a {@code text} block -> a
 * {@code functionCall} -> the terminal {@code finishReason}/{@code usageMetadata} chunk. Unlike
 * some other vendors' event-typed SSE, Gemini's stream has no 1:1 frame<->event correspondence (a
 * whole {@code functionCall} collapses to a single buffered delta, and {@code content_block_start}/
 * {@code content_block_stop} are synthesized transitions with no source frame of their own), so
 * this test asserts the DECODED event sequence directly, then separately proves the round trip by
 * re-encoding the event list and decoding it again, checking the SECOND decode reaches the same
 * semantic content, since Gemini's chunks are not frame-symmetric the way some other vendors' are.
 */
class GeminiStreamRoundTripTest {

    private static String frame(String data) {
        return "data: " + data + "\n\n";
    }

    private static final List<String> FRAMES = java.util.Arrays.asList(
            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
                    + "{\"thought\":true,\"text\":\"Let me check the weather\",\"thoughtSignature\":\"sig-xyz789\"}"
                    + "]}}]}",
            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"It's \"}]}}]}",
            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"18C and cloudy.\"}]}}]}",
            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
                    + "{\"functionCall\":{\"id\":\"call_03\",\"name\":\"get_weather\",\"args\":{\"city\":\"Berlin\"}}}"
                    + "]}}],\"usageMetadata\":{\"promptTokenCount\":30}}",
            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},\"finishReason\":\"STOP\"}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":30,\"candidatesTokenCount\":42,"
                    + "\"totalTokenCount\":72,\"thoughtsTokenCount\":8}}");

    private static String buildSse() {
        StringBuilder sb = new StringBuilder();
        for (String data : FRAMES) sb.append(frame(data));
        return sb.toString();
    }

    @Test
    void streamedResponseDecodesToExpectedEventSequence() {
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);
        StreamDecoder decoder = translator.newStreamDecoder();

        String sse = buildSse();
        int splitPoint = sse.length() / 2; // exercise cross-chunk line buffering
        List<IrStreamEvent> events = new ArrayList<>();
        events.addAll(decoder.decode(sse.substring(0, splitPoint)));
        events.addAll(decoder.decode(sse.substring(splitPoint)));

        assertEvents(events);
    }

    private void assertEvents(List<IrStreamEvent> events) {
        assertEquals(14, events.size());

        assertTrue(events.get(0) instanceof MessageStartEvent);

        assertTrue(events.get(1) instanceof ContentBlockStartEvent);
        assertEquals(ContentBlockKind.THINKING, ((ContentBlockStartEvent) events.get(1)).blockKind);
        assertTrue(events.get(2) instanceof ThinkingDeltaEvent);
        assertEquals("Let me check the weather", ((ThinkingDeltaEvent) events.get(2)).text);
        assertTrue(events.get(3) instanceof ThinkingSignatureEvent);
        assertEquals("sig-xyz789", ((ThinkingSignatureEvent) events.get(3)).signature);
        assertTrue(events.get(4) instanceof ContentBlockStopEvent);

        assertTrue(events.get(5) instanceof ContentBlockStartEvent);
        assertEquals(ContentBlockKind.TEXT, ((ContentBlockStartEvent) events.get(5)).blockKind);
        assertTrue(events.get(6) instanceof TextDeltaEvent);
        assertEquals("It's ", ((TextDeltaEvent) events.get(6)).text);
        assertTrue(events.get(7) instanceof TextDeltaEvent);
        assertEquals("18C and cloudy.", ((TextDeltaEvent) events.get(7)).text);
        assertTrue(events.get(8) instanceof ContentBlockStopEvent);

        assertTrue(events.get(9) instanceof ContentBlockStartEvent);
        ContentBlockStartEvent toolStart = (ContentBlockStartEvent) events.get(9);
        assertEquals(ContentBlockKind.TOOL_USE, toolStart.blockKind);
        assertEquals("call_03", toolStart.toolUseId);
        assertEquals("get_weather", toolStart.toolName);
        assertTrue(events.get(10) instanceof ToolInputDeltaEvent);
        assertTrue(events.get(11) instanceof ContentBlockStopEvent);

        assertTrue(events.get(12) instanceof MessageDeltaEvent);
        MessageDeltaEvent messageDelta = (MessageDeltaEvent) events.get(12);
        assertEquals(IrStopReason.TOOL_USE, messageDelta.stopReason,
                "a functionCall earlier in the stream forces tool_use, even though the terminal finishReason is STOP");
        assertEquals(30, messageDelta.usage.inputTokens);
        assertEquals(42, messageDelta.usage.outputTokens);
        assertEquals(72, messageDelta.usage.totalTokens);
        assertEquals(8, messageDelta.usage.reasoningTokens);

        assertTrue(events.get(13) instanceof MessageStopEvent);
    }

    @Test
    void reEncodedEventsDecodeToTheSameSemanticContent() {
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        List<IrStreamEvent> events = translator.newStreamDecoder().decode(buildSse());

        StreamEncoder encoder = translator.newStreamEncoder();
        StringBuilder reEncodedSse = new StringBuilder();
        for (IrStreamEvent event : events) reEncodedSse.append(encoder.encode(event));

        List<IrStreamEvent> roundTripped = translator.newStreamDecoder().decode(reEncodedSse.toString());
        assertEvents(roundTripped);
    }
}
