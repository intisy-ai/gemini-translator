package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrMessage;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrThinking;
import io.github.intisy.ai.ir.IrTool;
import io.github.intisy.ai.ir.IrToolChoice;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.spi.JsonCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini {@code generateContent} request {@code Map} tree <-> {@link IrRequest}. Field mapping
 * (see {@code GeminiBlockCodec}'s javadoc for the block-level shapes this builds on):
 * {@code contents[]} &lt;-&gt; {@code messages} ({@code model}/{@code user} roles; IR's
 * {@code tool} role FOLDS into a Gemini {@code user} turn on encode, matching the real API's
 * convention that a {@code functionResponse} rides in a {@code user}-role turn -- there is no
 * distinct Gemini role for it, mirroring how other vendors embed a tool result in a
 * {@code user} message); {@code systemInstruction} &lt;-&gt; {@code system}; {@code tools[].
 * functionDeclarations} &lt;-&gt; {@code tools}; {@code toolConfig.functionCallingConfig}
 * &lt;-&gt; {@code toolChoice}; {@code generationConfig.*} &lt;-&gt; {@code maxTokens}/
 * {@code temperature}/{@code topP}/{@code topK}/{@code stopSequences}/{@code thinking}.
 *
 * <h2>{@code model} has no Gemini request-body home</h2>
 * The real Gemini {@code generateContent} REST API carries the model in the URL path
 * ({@code models/{model}:generateContent}), never in the JSON body -- that URL/transport concern
 * is out of core-ir's scope (it belongs to the antigravity/Gemini PROVIDER). This codec
 * still reads/writes an optional top-level {@code model} string when present (some Gemini-family
 * client SDKs do embed it), so {@code IrRequest#model} survives when the wire happens to carry it,
 * without requiring it.
 *
 * <h2>Tool-result folding + name pairing</h2>
 * Gemini pairs a {@code functionResponse} to its {@code functionCall} by {@code name} (an {@code
 * id} is only used for parallel calls); this codec pre-scans every {@link ToolUseBlock} across
 * {@code messages} into an id-&gt;name map before encoding, exactly mirroring antigravity-auth's
 * format-bridging pre-pass (fallback chain {@code toolNames[id] || id || "tool"}) -- the
 * battle-tested reference this module reuses.
 *
 * <h2>{@code thinkingConfig}</h2>
 * IR's {@link IrThinking} has no {@code includeThoughts} field (most other vendors have no such
 * concept);
 * the Gemini value round-trips via the {@code $includeThoughts} request-level extension, defaulting
 * to {@code true} when absent (matching antigravity-auth's {@code buildGemini25ThinkingConfig}
 * default). See {@link GeminiFinishReason}/{@code GeminiResponseCodec} for the response-side
 * reasoning-token gap this same family of models exposes.
 */
final class GeminiRequestCodec {
    private GeminiRequestCodec() {
    }

    private static final String EXT_INCLUDE_THOUGHTS = "$includeThoughts";
    private static final String EXT_THINKING_CONFIG_EXTRA = "$thinkingConfigExtra";
    private static final String EXT_GENERATION_CONFIG_EXTRA = "$generationConfigExtra";
    private static final String EXT_TOOLS_EXTRA = "$toolsExtra";
    private static final String EXT_SYSTEM_INSTRUCTION_EXTRA = "$systemInstructionExtra";
    private static final String EXT_TOOL_CHOICE_MODE_RAW = "$modeRaw";

    private static final Set<String> TOP_LEVEL_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "model", "contents", "systemInstruction", "tools", "toolConfig", "generationConfig"));

    private static final Set<String> GENERATION_CONFIG_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "maxOutputTokens", "temperature", "topP", "topK", "stopSequences", "thinkingConfig"));

    private static final Set<String> THINKING_CONFIG_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "thinkingBudget", "includeThoughts"));

    private static final Set<String> TOOL_DECL_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "name", "description", "parameters"));

    // ---- decode --------------------------------------------------------------------------------

    static IrRequest decodeRequest(JsonCodec json, String wireJson) {
        Map<String, Object> root = GeminiJsonUtil.asMap(json.parse(wireJson));
        IrRequest r = new IrRequest();
        if (root == null) return r;

        r.model = GeminiJsonUtil.asString(root.get("model"));
        r.messages = decodeContents(json, root.get("contents"));
        decodeSystemInstruction(root.get("systemInstruction"), json, r);
        decodeTools(root.get("tools"), r);
        decodeToolConfig(root.get("toolConfig"), r);
        decodeGenerationConfig(root.get("generationConfig"), r);

        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!TOP_LEVEL_KNOWN_KEYS.contains(e.getKey())) {
                putExtension(r, e.getKey(), e.getValue());
            }
        }
        return r;
    }

    private static List<IrMessage> decodeContents(JsonCodec json, Object raw) {
        List<Object> list = GeminiJsonUtil.asList(raw);
        if (list == null) return null;
        List<IrMessage> out = new ArrayList<>();
        for (Object item : list) out.add(decodeContent(json, GeminiJsonUtil.asMap(item)));
        return out;
    }

    private static IrMessage decodeContent(JsonCodec json, Map<String, Object> cm) {
        if (cm == null) return null;
        IrMessage msg = new IrMessage();
        msg.role = "model".equals(cm.get("role")) ? "assistant" : "user";
        msg.content = GeminiBlockCodec.decodePartsList(json, cm.get("parts"));
        for (Map.Entry<String, Object> e : cm.entrySet()) {
            if (!"role".equals(e.getKey()) && !"parts".equals(e.getKey())) {
                putMessageExtension(msg, e.getKey(), e.getValue());
            }
        }
        return msg;
    }

    private static void decodeSystemInstruction(Object raw, JsonCodec json, IrRequest r) {
        Map<String, Object> si = GeminiJsonUtil.asMap(raw);
        if (si == null) return;
        r.system = GeminiBlockCodec.decodePartsList(json, si.get("parts"));
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : si.entrySet()) {
            if (!"parts".equals(e.getKey())) extra.put(e.getKey(), e.getValue());
        }
        if (!extra.isEmpty()) putExtension(r, EXT_SYSTEM_INSTRUCTION_EXTRA, extra);
    }

    private static void decodeTools(Object raw, IrRequest r) {
        List<Object> toolsList = GeminiJsonUtil.asList(raw);
        if (toolsList == null) return;

        List<IrTool> irTools = new ArrayList<>();
        List<Object> extra = new ArrayList<>();

        for (Object entry : toolsList) {
            Map<String, Object> te = GeminiJsonUtil.asMap(entry);
            List<Object> declarations = te == null ? null : GeminiJsonUtil.asList(te.get("functionDeclarations"));
            if (declarations == null) {
                extra.add(entry);
                continue;
            }
            for (Object declObj : declarations) {
                Map<String, Object> decl = GeminiJsonUtil.asMap(declObj);
                if (decl == null) continue;
                IrTool t = new IrTool();
                t.name = GeminiJsonUtil.asString(decl.get("name"));
                t.description = GeminiJsonUtil.asString(decl.get("description"));
                t.inputSchema = decl.get("parameters");
                for (Map.Entry<String, Object> e : decl.entrySet()) {
                    if (!TOOL_DECL_KNOWN_KEYS.contains(e.getKey())) {
                        if (t.extensions == null) t.extensions = new LinkedHashMap<>();
                        t.extensions.put(e.getKey(), e.getValue());
                    }
                }
                irTools.add(t);
            }
        }

        if (!irTools.isEmpty()) r.tools = irTools;
        if (!extra.isEmpty()) putExtension(r, EXT_TOOLS_EXTRA, extra);
    }

    private static void decodeToolConfig(Object raw, IrRequest r) {
        Map<String, Object> tc = GeminiJsonUtil.asMap(raw);
        Map<String, Object> fc = tc == null ? null : GeminiJsonUtil.asMap(tc.get("functionCallingConfig"));
        if (fc == null) return;

        String mode = GeminiJsonUtil.asString(fc.get("mode"));
        List<Object> allowed = GeminiJsonUtil.asList(fc.get("allowedFunctionNames"));

        IrToolChoice c = new IrToolChoice();
        if ("AUTO".equals(mode)) {
            c.type = IrToolChoice.Type.AUTO;
        } else if ("NONE".equals(mode)) {
            c.type = IrToolChoice.Type.NONE;
        } else if ("ANY".equals(mode)) {
            if (allowed != null && allowed.size() == 1) {
                c.type = IrToolChoice.Type.TOOL;
                c.name = String.valueOf(allowed.get(0));
            } else {
                c.type = IrToolChoice.Type.ANY;
                if (allowed != null && !allowed.isEmpty()) {
                    putChoiceExtension(c, "allowedFunctionNames", allowed);
                }
            }
        } else {
            c.type = IrToolChoice.Type.AUTO;
            putChoiceExtension(c, EXT_TOOL_CHOICE_MODE_RAW, mode);
        }
        r.toolChoice = c;
    }

    private static void decodeGenerationConfig(Object raw, IrRequest r) {
        Map<String, Object> gc = GeminiJsonUtil.asMap(raw);
        if (gc == null) return;

        r.maxTokens = GeminiJsonUtil.asInt(gc.get("maxOutputTokens"));
        r.temperature = GeminiJsonUtil.asDouble(gc.get("temperature"));
        r.topP = GeminiJsonUtil.asDouble(gc.get("topP"));
        r.topK = GeminiJsonUtil.asInt(gc.get("topK"));
        List<Object> stop = GeminiJsonUtil.asList(gc.get("stopSequences"));
        if (stop != null) {
            List<String> ss = new ArrayList<>();
            for (Object o : stop) ss.add(String.valueOf(o));
            r.stopSequences = ss;
        }

        Map<String, Object> tc = GeminiJsonUtil.asMap(gc.get("thinkingConfig"));
        if (tc != null) {
            IrThinking thinking = new IrThinking();
            thinking.enabled = true;
            thinking.budgetTokens = GeminiJsonUtil.asInt(tc.get("thinkingBudget"));
            r.thinking = thinking;
            Boolean includeThoughts = GeminiJsonUtil.asBoolean(tc.get("includeThoughts"));
            if (includeThoughts != null) putExtension(r, EXT_INCLUDE_THOUGHTS, includeThoughts);
            Map<String, Object> tcExtra = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : tc.entrySet()) {
                if (!THINKING_CONFIG_KNOWN_KEYS.contains(e.getKey())) tcExtra.put(e.getKey(), e.getValue());
            }
            if (!tcExtra.isEmpty()) putExtension(r, EXT_THINKING_CONFIG_EXTRA, tcExtra);
        }

        Map<String, Object> gcExtra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : gc.entrySet()) {
            if (!GENERATION_CONFIG_KNOWN_KEYS.contains(e.getKey())) gcExtra.put(e.getKey(), e.getValue());
        }
        if (!gcExtra.isEmpty()) putExtension(r, EXT_GENERATION_CONFIG_EXTRA, gcExtra);
    }

    // ---- encode --------------------------------------------------------------------------------

    static String encodeRequest(JsonCodec json, IrRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r.model != null) m.put("model", r.model);

        Map<String, String> toolNames = new LinkedHashMap<>();
        Set<String> syntheticIds = new LinkedHashSet<>();
        collectToolNames(r.messages, toolNames, syntheticIds);

        m.put("contents", encodeContents(json, r.messages, toolNames, syntheticIds));
        encodeSystemInstruction(json, r, m);
        encodeTools(r, m);
        encodeToolConfig(r, m);
        encodeGenerationConfig(r, m);
        encodeLeftoverExtensions(r, m);
        return json.stringify(m);
    }

    private static void collectToolNames(List<IrMessage> messages, Map<String, String> toolNames, Set<String> syntheticIds) {
        if (messages == null) return;
        for (IrMessage msg : messages) {
            if (msg == null || msg.content == null) continue;
            for (Block b : msg.content) {
                if (b instanceof ToolUseBlock) {
                    ToolUseBlock t = (ToolUseBlock) b;
                    toolNames.put(t.id, t.name);
                    if (t.extensions != null && Boolean.TRUE.equals(t.extensions.get(GeminiBlockCodec.EXT_ID_FROM_NAME))) {
                        syntheticIds.add(t.id);
                    }
                }
            }
        }
    }

    private static List<Object> encodeContents(JsonCodec json, List<IrMessage> messages, Map<String, String> toolNames, Set<String> syntheticIds) {
        List<Object> out = new ArrayList<>();
        if (messages == null) return out;
        for (IrMessage msg : messages) out.add(encodeContent(json, msg, toolNames, syntheticIds));
        return out;
    }

    private static Map<String, Object> encodeContent(JsonCodec json, IrMessage msg, Map<String, String> toolNames, Set<String> syntheticIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant".equals(msg.role) ? "model" : "user");
        m.put("parts", GeminiBlockCodec.encodePartsList(json, msg.content, toolNames, syntheticIds));
        if (msg.extensions != null) {
            for (Map.Entry<String, Object> e : msg.extensions.entrySet()) {
                if (!e.getKey().startsWith("$")) m.put(e.getKey(), e.getValue());
            }
        }
        return m;
    }

    private static void encodeSystemInstruction(JsonCodec json, IrRequest r, Map<String, Object> m) {
        if (r.system == null) return;
        Map<String, Object> si = new LinkedHashMap<>();
        si.put("parts", GeminiBlockCodec.encodePartsList(json, r.system, null, null));
        Object extraRaw = r.extensions == null ? null : r.extensions.get(EXT_SYSTEM_INSTRUCTION_EXTRA);
        if (extraRaw instanceof Map) si.putAll((Map<String, Object>) extraRaw);
        m.put("systemInstruction", si);
    }

    @SuppressWarnings("unchecked")
    private static void encodeTools(IrRequest r, Map<String, Object> m) {
        List<Object> toolsList = new ArrayList<>();
        if (r.tools != null && !r.tools.isEmpty()) {
            List<Object> decls = new ArrayList<>();
            for (IrTool t : r.tools) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", t.name);
                if (t.description != null) d.put("description", t.description);
                d.put("parameters", t.inputSchema);
                if (t.extensions != null) {
                    for (Map.Entry<String, Object> e : t.extensions.entrySet()) d.put(e.getKey(), e.getValue());
                }
                decls.add(d);
            }
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("functionDeclarations", decls);
            toolsList.add(wrap);
        }
        Object extraRaw = r.extensions == null ? null : r.extensions.get(EXT_TOOLS_EXTRA);
        if (extraRaw instanceof List) toolsList.addAll((List<Object>) extraRaw);
        if (!toolsList.isEmpty()) m.put("tools", toolsList);
    }

    private static void encodeToolConfig(IrRequest r, Map<String, Object> m) {
        if (r.toolChoice == null) return;
        IrToolChoice c = r.toolChoice;
        Map<String, Object> fc = new LinkedHashMap<>();

        Object modeRaw = c.extensions == null ? null : c.extensions.get(EXT_TOOL_CHOICE_MODE_RAW);
        if (modeRaw != null) {
            fc.put("mode", modeRaw);
        } else if (IrToolChoice.Type.AUTO.equals(c.type)) {
            fc.put("mode", "AUTO");
        } else if (IrToolChoice.Type.NONE.equals(c.type)) {
            fc.put("mode", "NONE");
        } else if (IrToolChoice.Type.ANY.equals(c.type)) {
            fc.put("mode", "ANY");
            Object allowed = c.extensions == null ? null : c.extensions.get("allowedFunctionNames");
            if (allowed != null) fc.put("allowedFunctionNames", allowed);
        } else if (IrToolChoice.Type.TOOL.equals(c.type)) {
            fc.put("mode", "ANY");
            List<Object> names = new ArrayList<>();
            names.add(c.name);
            fc.put("allowedFunctionNames", names);
        }

        Map<String, Object> toolConfig = new LinkedHashMap<>();
        toolConfig.put("functionCallingConfig", fc);
        m.put("toolConfig", toolConfig);
    }

    @SuppressWarnings("unchecked")
    private static void encodeGenerationConfig(IrRequest r, Map<String, Object> m) {
        Map<String, Object> gc = new LinkedHashMap<>();
        if (r.maxTokens != null) gc.put("maxOutputTokens", r.maxTokens);
        if (r.temperature != null) gc.put("temperature", r.temperature);
        if (r.topP != null) gc.put("topP", r.topP);
        if (r.topK != null) gc.put("topK", r.topK);
        if (r.stopSequences != null) gc.put("stopSequences", new ArrayList<Object>(r.stopSequences));
        if (r.thinking != null) {
            Map<String, Object> tc = new LinkedHashMap<>();
            Object includeThoughts = r.extensions == null ? null : r.extensions.get(EXT_INCLUDE_THOUGHTS);
            tc.put("includeThoughts", includeThoughts != null ? includeThoughts : Boolean.TRUE);
            if (r.thinking.budgetTokens != null) tc.put("thinkingBudget", r.thinking.budgetTokens);
            Object tcExtra = r.extensions == null ? null : r.extensions.get(EXT_THINKING_CONFIG_EXTRA);
            if (tcExtra instanceof Map) tc.putAll((Map<String, Object>) tcExtra);
            gc.put("thinkingConfig", tc);
        }
        Object gcExtra = r.extensions == null ? null : r.extensions.get(EXT_GENERATION_CONFIG_EXTRA);
        if (gcExtra instanceof Map) gc.putAll((Map<String, Object>) gcExtra);
        if (!gc.isEmpty()) m.put("generationConfig", gc);
    }

    private static void encodeLeftoverExtensions(IrRequest r, Map<String, Object> m) {
        if (r.extensions == null) return;
        for (Map.Entry<String, Object> e : r.extensions.entrySet()) {
            if (!e.getKey().startsWith("$")) m.put(e.getKey(), e.getValue());
        }
    }

    // ---- extension helpers -----------------------------------------------------------------------

    private static void putExtension(IrRequest r, String key, Object value) {
        if (r.extensions == null) r.extensions = new LinkedHashMap<>();
        r.extensions.put(key, value);
    }

    private static void putMessageExtension(IrMessage msg, String key, Object value) {
        if (msg.extensions == null) msg.extensions = new LinkedHashMap<>();
        msg.extensions.put(key, value);
    }

    private static void putChoiceExtension(IrToolChoice c, String key, Object value) {
        if (c.extensions == null) c.extensions = new LinkedHashMap<>();
        c.extensions.put(key, value);
    }
}
