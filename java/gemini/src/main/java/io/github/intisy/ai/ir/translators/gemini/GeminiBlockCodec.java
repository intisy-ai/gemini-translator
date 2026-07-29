package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.ImageBlock;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ThinkingBlock;
import io.github.intisy.ai.ir.ToolResultBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.UnknownBlock;
import io.github.intisy.ai.ir.spi.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini {@code Part} {@code Map} tree <-> {@link Block} hierarchy. Ported against the real Gemini
 * {@code generateContent} {@code Part} shapes confirmed by antigravity-auth's
 * {@code AntigravityFormatBridge}/{@code AntigravityStreamMapper} (the battle-tested
 * vendor-format bridge this module reuses as its fidelity reference): {@code text},
 * {@code inlineData{mimeType,data}}, {@code fileData{mimeType,fileUri}}, {@code
 * functionCall{id?,name,args}}, {@code functionResponse{id?,name,response}} and the thought-part
 * shape {@code {thought:true, text, thoughtSignature}}.
 *
 * <h2>tool_use / tool_result id pairing</h2>
 * A real Gemini {@code functionCall}/{@code functionResponse} {@code id} is OPTIONAL (only
 * populated for parallel function calling); Gemini otherwise pairs a response to its call by
 * {@code name}, exactly like antigravity-auth's format-bridging logic pre-maps a tool-use id
 * -&gt; name before emitting {@code functionResponse}. When a wire
 * {@code functionCall} carries no {@code id}, {@link #decodePart} synthesizes
 * {@link ToolUseBlock#id} from its {@code name} (flagged via {@link #EXT_ID_FROM_NAME} so
 * {@link #encodePart} omits the invented {@code id} on re-encode, keeping the round trip
 * lossless); {@link GeminiRequestCodec} threads the resulting id/name/synthetic-id bookkeeping
 * into {@link #encodePart} for {@link ToolResultBlock}s via {@code toolNames}/{@code syntheticIds}.
 *
 * <h2>tool_result content</h2>
 * A real Gemini {@code functionResponse.response} is a free-form JSON object, not a content-block
 * list. The common shape this module (and antigravity-auth) produces is {@code {result: "<text>"}}
 * (optionally {@code isError}); {@link #decodePart} recognizes that shape and unwraps it into a
 * single {@link TextBlock} for IR consumers, restoring it exactly on {@link #encodePart}. Any other
 * shape (arbitrary {@code response} object) round-trips verbatim through
 * {@link #EXT_RESPONSE_RAW} (lossless for the same vendor) with a JSON-stringified
 * {@link TextBlock} standing in as the readable IR content.
 */
final class GeminiBlockCodec {
    private GeminiBlockCodec() {
    }

    static final String EXT_ID_FROM_NAME = "$idFromName";
    static final String EXT_RESPONSE_RAW = "$responseRaw";
    static final String EXT_CONTENT_IS_STRING = "$contentIsString";

    // ---- lists -------------------------------------------------------------------------------

    static List<Block> decodePartsList(JsonCodec json, Object raw) {
        List<Object> list = GeminiJsonUtil.asList(raw);
        if (list == null) return null;
        List<Block> out = new ArrayList<>();
        for (Object item : list) out.add(decodePart(json, GeminiJsonUtil.asMap(item)));
        return out;
    }

    static List<Object> encodePartsList(JsonCodec json, List<Block> blocks, Map<String, String> toolNames, Set<String> syntheticIds) {
        List<Object> out = new ArrayList<>();
        if (blocks == null) return out;
        for (Block b : blocks) out.add(encodePart(json, b, toolNames, syntheticIds));
        return out;
    }

    /** Wraps plain-string content (a {@code result} value with no block shape) as a single block. */
    static List<Block> wrapStringAsBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new TextBlock(text));
        return blocks;
    }

    /** True when {@code blocks} is exactly the shape produced by {@link #wrapStringAsBlocks}. */
    static boolean isPlainWrappedText(List<Block> blocks) {
        if (blocks == null || blocks.size() != 1) return false;
        Block only = blocks.get(0);
        return only instanceof TextBlock && only.cacheControl == null && only.extensions == null;
    }

    // ---- decode --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Block decodePart(JsonCodec json, Map<String, Object> part) {
        if (part == null) return null;

        if (part.get("functionCall") instanceof Map) {
            return decodeFunctionCall(GeminiJsonUtil.asMap(part.get("functionCall")));
        }
        if (part.get("functionResponse") instanceof Map) {
            return decodeFunctionResponse(json, GeminiJsonUtil.asMap(part.get("functionResponse")));
        }
        if (part.get("inlineData") instanceof Map) {
            Map<String, Object> inline = GeminiJsonUtil.asMap(part.get("inlineData"));
            ImageBlock img = new ImageBlock();
            img.mediaType = GeminiJsonUtil.asString(inline.get("mimeType"));
            img.data = GeminiJsonUtil.asString(inline.get("data"));
            return img;
        }
        if (part.get("fileData") instanceof Map) {
            Map<String, Object> file = GeminiJsonUtil.asMap(part.get("fileData"));
            ImageBlock img = new ImageBlock();
            img.mediaType = GeminiJsonUtil.asString(file.get("mimeType"));
            img.url = GeminiJsonUtil.asString(file.get("fileUri"));
            return img;
        }
        if (Boolean.TRUE.equals(part.get("thought"))) {
            ThinkingBlock t = new ThinkingBlock();
            t.text = GeminiJsonUtil.asString(part.get("text"));
            t.signature = GeminiJsonUtil.asString(part.get("thoughtSignature"));
            return t;
        }
        if (part.get("text") instanceof String) {
            return new TextBlock((String) part.get("text"));
        }
        // An unrecognized Gemini part shape (e.g. executableCode/codeExecutionResult) -- stash it
        // verbatim rather than throw, mirroring how other translators handle UnknownBlock: a
        // translator ahead of a real upstream must never fail a whole response over ONE part it
        // doesn't recognize.
        UnknownBlock u = new UnknownBlock();
        u.raw = new LinkedHashMap<>(part);
        return u;
    }

    private static Block decodeFunctionCall(Map<String, Object> fc) {
        ToolUseBlock t = new ToolUseBlock();
        t.name = GeminiJsonUtil.asString(fc.get("name"));
        t.input = fc.get("args");
        String id = GeminiJsonUtil.asString(fc.get("id"));
        if (id != null) {
            t.id = id;
        } else {
            t.id = t.name;
            putExtension(t, EXT_ID_FROM_NAME, Boolean.TRUE);
        }
        return t;
    }

    private static Block decodeFunctionResponse(JsonCodec json, Map<String, Object> fr) {
        ToolResultBlock r = new ToolResultBlock();
        String id = GeminiJsonUtil.asString(fr.get("id"));
        String name = GeminiJsonUtil.asString(fr.get("name"));
        r.toolUseId = id != null ? id : name;

        Map<String, Object> response = GeminiJsonUtil.asMap(fr.get("response"));
        if (response == null) {
            r.content = wrapStringAsBlocks("");
        } else if (isCleanResultShape(response)) {
            r.content = wrapStringAsBlocks((String) response.get("result"));
            Object isError = response.get("isError");
            if (isError instanceof Boolean) r.isError = (Boolean) isError;
        } else {
            r.content = wrapStringAsBlocks(json.stringify(response));
            putExtension(r, EXT_RESPONSE_RAW, response);
        }
        return r;
    }

    // {result: "<string>"} with only result/isError keys present.
    private static boolean isCleanResultShape(Map<String, Object> response) {
        if (!(response.get("result") instanceof String)) return false;
        for (String key : response.keySet()) {
            if (!"result".equals(key) && !"isError".equals(key)) return false;
        }
        return true;
    }

    // ---- encode --------------------------------------------------------------------------------

    static Map<String, Object> encodePart(JsonCodec json, Block block, Map<String, String> toolNames, Set<String> syntheticIds) {
        if (block == null) return null;
        if (block instanceof UnknownBlock) {
            // Return the stashed raw map verbatim (mirrors how other translators encode an
            // UnknownBlock) -- a block this codec never modeled (including one that arrived via a
            // DIFFERENT vendor's decode, e.g. a `document` block riding through IR into a Gemini
            // request) must not crash the encode side either.
            return new LinkedHashMap<>(((UnknownBlock) block).raw);
        }
        if (block instanceof TextBlock) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", ((TextBlock) block).text);
            return m;
        }
        if (block instanceof ImageBlock) {
            return encodeImage((ImageBlock) block);
        }
        if (block instanceof ToolUseBlock) {
            return encodeFunctionCall((ToolUseBlock) block);
        }
        if (block instanceof ToolResultBlock) {
            return encodeFunctionResponse(json, (ToolResultBlock) block, toolNames, syntheticIds);
        }
        if (block instanceof ThinkingBlock) {
            ThinkingBlock t = (ThinkingBlock) block;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("thought", Boolean.TRUE);
            m.put("text", t.text);
            if (t.signature != null) m.put("thoughtSignature", t.signature);
            return m;
        }
        throw new IllegalArgumentException("unsupported Block type: " + block.getClass());
    }

    private static Map<String, Object> encodeImage(ImageBlock img) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (img.url != null) {
            Map<String, Object> fileData = new LinkedHashMap<>();
            fileData.put("mimeType", img.mediaType);
            fileData.put("fileUri", img.url);
            m.put("fileData", fileData);
        } else {
            Map<String, Object> inlineData = new LinkedHashMap<>();
            inlineData.put("mimeType", img.mediaType);
            inlineData.put("data", img.data);
            m.put("inlineData", inlineData);
        }
        return m;
    }

    private static Map<String, Object> encodeFunctionCall(ToolUseBlock t) {
        Map<String, Object> fc = new LinkedHashMap<>();
        boolean idFromName = t.extensions != null && Boolean.TRUE.equals(t.extensions.get(EXT_ID_FROM_NAME));
        if (!idFromName && t.id != null) fc.put("id", t.id);
        fc.put("name", t.name);
        fc.put("args", t.input != null ? t.input : new LinkedHashMap<>());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("functionCall", fc);
        return m;
    }

    private static Map<String, Object> encodeFunctionResponse(JsonCodec json, ToolResultBlock r, Map<String, String> toolNames, Set<String> syntheticIds) {
        Map<String, Object> fr = new LinkedHashMap<>();
        boolean idIsSynthetic = syntheticIds != null && syntheticIds.contains(r.toolUseId);
        if (!idIsSynthetic && r.toolUseId != null) fr.put("id", r.toolUseId);
        String name = toolNames != null ? toolNames.get(r.toolUseId) : null;
        fr.put("name", name != null ? name : (r.toolUseId != null ? r.toolUseId : "tool"));

        Object rawResponse = r.extensions == null ? null : r.extensions.get(EXT_RESPONSE_RAW);
        if (rawResponse instanceof Map) {
            fr.put("response", rawResponse);
        } else {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("result", contentToText(json, r.content));
            if (r.isError != null) response.put("isError", r.isError);
            fr.put("response", response);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("functionResponse", fr);
        return m;
    }

    // Joins tool_result content into a single string, matching AntigravityFormatBridge's
    // joinToolResultTexts/JSON.stringify fallback for non-text content.
    private static String contentToText(JsonCodec json, List<Block> content) {
        if (isPlainWrappedText(content)) {
            return ((TextBlock) content.get(0)).text;
        }
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (Block b : content) {
                if (b instanceof TextBlock) {
                    sb.append(((TextBlock) b).text);
                } else {
                    sb.append(json.stringify(encodePart(json, b, null, null)));
                }
            }
        }
        return sb.toString();
    }

    private static void putExtension(Block block, String key, Object value) {
        if (block.extensions == null) block.extensions = new LinkedHashMap<>();
        block.extensions.put(key, value);
    }
}
