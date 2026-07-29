package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.ImageBlock;
import io.github.intisy.ai.ir.IrMessage;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrThinking;
import io.github.intisy.ai.ir.IrToolChoice;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ThinkingBlock;
import io.github.intisy.ai.ir.ToolResultBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.json.TestJsonCodec;
import io.github.intisy.ai.ir.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-vector test for {@link GeminiTranslator#decodeRequest}/{@code encodeRequest}: a
 * real-shaped Gemini {@code generateContent} request exercising a system instruction + multi-turn
 * {@code contents} (with an inline image) + {@code functionCall}/{@code functionResponse} +
 * a {@code thought} block with a {@code thoughtSignature} + {@code generationConfig} including
 * {@code thinkingConfig}. Fidelity is asserted by comparing the JSON parsed as maps, not raw
 * strings, since key order is not semantically meaningful.
 */
class GeminiRequestRoundTripTest {

    private static final String GOLDEN_REQUEST = "{"
            + "\"contents\":["
            + "{\"role\":\"user\",\"parts\":["
            + "{\"text\":\"What is the weather in Berlin? Here is a photo:\"},"
            + "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"aGVsbG8=\"}}"
            + "]},"
            + "{\"role\":\"model\",\"parts\":["
            + "{\"thought\":true,\"text\":\"Let me check the weather.\",\"thoughtSignature\":\"sig-abc123\"},"
            + "{\"functionCall\":{\"id\":\"call_01\",\"name\":\"get_weather\",\"args\":{\"city\":\"Berlin\"}}}"
            + "]},"
            + "{\"role\":\"user\",\"parts\":["
            + "{\"functionResponse\":{\"id\":\"call_01\",\"name\":\"get_weather\",\"response\":{\"result\":\"18C, cloudy\"}}}"
            + "]},"
            + "{\"role\":\"model\",\"parts\":[{\"text\":\"It's 18C and cloudy in Berlin.\"}]}"
            + "],"
            + "\"systemInstruction\":{\"parts\":[{\"text\":\"You are a helpful assistant.\"}]},"
            + "\"tools\":[{\"functionDeclarations\":[{\"name\":\"get_weather\","
            + "\"description\":\"Get the current weather for a city\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
            + "\"required\":[\"city\"]}}]}],"
            + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"AUTO\"}},"
            + "\"generationConfig\":{\"maxOutputTokens\":4096,\"temperature\":0.7,\"topP\":0.9,\"topK\":40,"
            + "\"stopSequences\":[\"STOP\"],"
            + "\"thinkingConfig\":{\"thinkingBudget\":2048,\"includeThoughts\":true}}"
            + "}";

    @Test
    void requestRoundTripsToSemanticallyEqualJson() {
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrRequest decoded = translator.decodeRequest(GOLDEN_REQUEST);
        String reEncoded = translator.encodeRequest(decoded);

        assertEquals(json.parse(GOLDEN_REQUEST), json.parse(reEncoded),
                "decode->encode must reproduce a semantically-equal Gemini request");

        assertEquals(4, decoded.messages.size());
        assertEquals("user", decoded.messages.get(0).role);
        assertEquals("assistant", decoded.messages.get(1).role, "Gemini 'model' role maps to IR 'assistant'");
        assertEquals("user", decoded.messages.get(2).role, "a functionResponse turn stays IR 'user'");

        List<Block> firstTurn = decoded.messages.get(0).content;
        assertEquals(2, firstTurn.size());
        assertTrue(firstTurn.get(0) instanceof TextBlock);
        assertTrue(firstTurn.get(1) instanceof ImageBlock);
        ImageBlock image = (ImageBlock) firstTurn.get(1);
        assertEquals("image/png", image.mediaType);
        assertEquals("aGVsbG8=", image.data);

        List<Block> secondTurn = decoded.messages.get(1).content;
        assertTrue(secondTurn.get(0) instanceof ThinkingBlock);
        ThinkingBlock thinkingBlock = (ThinkingBlock) secondTurn.get(0);
        assertEquals("Let me check the weather.", thinkingBlock.text);
        assertEquals("sig-abc123", thinkingBlock.signature, "thoughtSignature must survive the round trip");
        assertTrue(secondTurn.get(1) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) secondTurn.get(1);
        assertEquals("call_01", toolUse.id);
        assertEquals("get_weather", toolUse.name);

        List<Block> thirdTurn = decoded.messages.get(2).content;
        assertTrue(thirdTurn.get(0) instanceof ToolResultBlock);
        ToolResultBlock toolResult = (ToolResultBlock) thirdTurn.get(0);
        assertEquals("call_01", toolResult.toolUseId);
        assertEquals("18C, cloudy", ((TextBlock) toolResult.content.get(0)).text);

        assertEquals(1, decoded.system.size());
        assertEquals("You are a helpful assistant.", ((TextBlock) decoded.system.get(0)).text);

        assertEquals(1, decoded.tools.size());
        assertEquals("get_weather", decoded.tools.get(0).name);
        assertEquals(IrToolChoice.Type.AUTO, decoded.toolChoice.type);

        assertEquals(4096, decoded.maxTokens);
        assertEquals(0.7, decoded.temperature);
        assertEquals(0.9, decoded.topP);
        assertEquals(40, decoded.topK);
        assertEquals(1, decoded.stopSequences.size());
        assertEquals("STOP", decoded.stopSequences.get(0));

        IrThinking thinking = decoded.thinking;
        assertTrue(thinking.enabled);
        assertEquals(2048, thinking.budgetTokens);
    }

    @Test
    void toolChoiceForcedToSingleFunctionRoundTrips() {
        String wire = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],"
                + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"get_weather\"]}}}";
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertEquals(IrToolChoice.Type.TOOL, decoded.toolChoice.type);
        assertEquals("get_weather", decoded.toolChoice.name);

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void toolChoiceNoneRoundTrips() {
        String wire = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],"
                + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"NONE\"}}}";
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertEquals(IrToolChoice.Type.NONE, decoded.toolChoice.type);
        assertNull(decoded.toolChoice.name);

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }

    @Test
    void toolResultFoldsIntoUserTurnAndPairsByNameEvenWithoutId() {
        // Neither the functionCall nor the functionResponse carries an "id" -- Gemini pairs by name.
        String wire = "{\"contents\":["
                + "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Berlin\"}}}]},"
                + "{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"get_weather\",\"response\":{\"result\":\"18C\"}}}]}"
                + "]}";
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertEquals("assistant", decoded.messages.get(0).role);
        assertEquals("user", decoded.messages.get(1).role);
        ToolUseBlock toolUse = (ToolUseBlock) decoded.messages.get(0).content.get(0);
        ToolResultBlock toolResult = (ToolResultBlock) decoded.messages.get(1).content.get(0);
        assertEquals(toolUse.id, toolResult.toolUseId, "the synthesized id (from name) pairs the two blocks");

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded),
                "no 'id' field must be invented on re-encode since the original wire never had one");
    }

    @Test
    void irToolRoleMessageFoldsIntoGeminiUserTurnOnEncode() {
        // A hypothetical front-door that models a distinct IR "tool" role (Gemini has none: a
        // tool result rides in a "user" turn, exactly like other vendors' tool-result conventions).
        ToolUseBlock toolUse = new ToolUseBlock();
        toolUse.id = "call_09";
        toolUse.name = "get_weather";
        toolUse.input = new java.util.LinkedHashMap<>();

        ToolResultBlock toolResult = new ToolResultBlock();
        toolResult.toolUseId = "call_09";
        toolResult.content = new ArrayList<>(java.util.Collections.singletonList(new TextBlock("18C")));

        IrRequest r = new IrRequest();
        r.messages = new ArrayList<>();
        r.messages.add(new IrMessage("assistant", new ArrayList<>(Arrays.asList((Block) toolUse))));
        r.messages.add(new IrMessage("tool", new ArrayList<>(Arrays.asList((Block) toolResult))));

        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);
        String encoded = translator.encodeRequest(r);

        Map<?, ?> parsed = (Map<?, ?>) json.parse(encoded);
        List<?> contents = (List<?>) parsed.get("contents");
        assertEquals("model", ((Map<?, ?>) contents.get(0)).get("role"));
        assertEquals("user", ((Map<?, ?>) contents.get(1)).get("role"), "IR 'tool' role folds into Gemini 'user'");

        Map<?, ?> responsePart = (Map<?, ?>) ((List<?>) ((Map<?, ?>) contents.get(1)).get("parts")).get(0);
        Map<?, ?> functionResponse = (Map<?, ?>) responsePart.get("functionResponse");
        assertEquals("get_weather", functionResponse.get("name"), "name resolved from the earlier tool_use by id");
    }

    @Test
    void unknownTopLevelAndToolsSurviveViaExtensions() {
        String wire = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],"
                + "\"tools\":[{\"googleSearch\":{}}],"
                + "\"safetySettings\":[{\"category\":\"HARM_CATEGORY_HARASSMENT\",\"threshold\":\"BLOCK_NONE\"}]}";
        JsonCodec json = new TestJsonCodec();
        GeminiTranslator translator = new GeminiTranslator(json);

        IrRequest decoded = translator.decodeRequest(wire);
        assertNull(decoded.tools, "a tools[] with no functionDeclarations produces no IrTool entries");
        assertTrue(decoded.extensions.get("safetySettings") instanceof List);

        String reEncoded = translator.encodeRequest(decoded);
        assertEquals(json.parse(wire), json.parse(reEncoded));
    }
}
