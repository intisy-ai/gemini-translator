package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.json.TestJsonCodec;
import io.github.intisy.ai.ir.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-vector test for {@link GeminiTranslator#decodeResponse}/{@code encodeResponse}: a
 * real-shaped Gemini {@code generateContent} response with text + {@code functionCall} content
 * and full {@code usageMetadata} including {@code thoughtsTokenCount}/
 * {@code cachedContentTokenCount}.
 */
class GeminiResponseRoundTripTest {

    private static final String GOLDEN_RESPONSE = "{"
            + "\"candidates\":[{"
            + "\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"text\":\"It's 18C and cloudy in Berlin.\"},"
            + "{\"functionCall\":{\"id\":\"call_02\",\"name\":\"get_weather\",\"args\":{\"city\":\"Berlin\"}}}"
            + "]},"
            + "\"finishReason\":\"STOP\","
            + "\"index\":0"
            + "}],"
            + "\"usageMetadata\":{\"promptTokenCount\":512,\"candidatesTokenCount\":128,"
            + "\"totalTokenCount\":692,\"cachedContentTokenCount\":50,\"thoughtsTokenCount\":12},"
            + "\"modelVersion\":\"gemini-2.5-pro\","
            + "\"responseId\":\"resp_01XYZ\""
            + "}";

    @Test
    void responseRoundTripsToSemanticallyEqualJson() {
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrResponse decoded = translator.decodeResponse(GOLDEN_RESPONSE);
        String reEncoded = translator.encodeResponse(decoded);

        assertEquals(json.parse(GOLDEN_RESPONSE), json.parse(reEncoded),
                "decode->encode must reproduce a semantically-equal Gemini response");

        assertEquals("resp_01XYZ", decoded.id);
        assertEquals("gemini-2.5-pro", decoded.model);
        assertEquals(2, decoded.content.size());
        assertTrue(decoded.content.get(0) instanceof TextBlock);
        assertTrue(decoded.content.get(1) instanceof ToolUseBlock);
        assertEquals(IrStopReason.TOOL_USE, decoded.stopReason,
                "a functionCall in the content forces tool_use, even though the wire finishReason is STOP");

        assertEquals(512, decoded.usage.inputTokens);
        assertEquals(128, decoded.usage.outputTokens);
        assertEquals(50, decoded.usage.cacheReadInputTokens);
        assertEquals(12, decoded.usage.reasoningTokens,
                "thoughtsTokenCount maps onto the neutral IrUsage#reasoningTokens field");
        assertEquals(692, decoded.usage.totalTokens,
                "totalTokenCount maps onto the neutral IrUsage#totalTokens field");
    }

    @Test
    void plainTextResponseMapsStopToEndTurn() {
        String wire = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi\"}]},"
                + "\"finishReason\":\"STOP\",\"index\":0}],"
                + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":2}}";
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrResponse decoded = translator.decodeResponse(wire);
        assertEquals(IrStopReason.END_TURN, decoded.stopReason);

        String reEncoded = translator.encodeResponse(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void unmappedFinishReasonSurvivesViaExtensions() {
        String wire = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"blocked\"}]},"
                + "\"finishReason\":\"SAFETY\",\"index\":0}],"
                + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":2}}";
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrResponse decoded = translator.decodeResponse(wire);
        assertEquals(IrStopReason.ERROR, decoded.stopReason,
                "a genuinely unrecognized finishReason falls back to ERROR for typed consumers");

        String reEncoded = translator.encodeResponse(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded),
                "the exact Gemini finishReason string must survive even though IR has no matching constant");
    }
}
