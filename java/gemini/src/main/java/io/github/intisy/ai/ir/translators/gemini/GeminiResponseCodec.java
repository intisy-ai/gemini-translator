package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.IrUsage;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.spi.JsonCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini {@code generateContent} (non-streaming) response {@code Map} tree <-> {@link IrResponse}.
 *
 * <p>{@code finishReason}/{@code index} are stashed verbatim in {@link IrResponse#extensions}
 * alongside the best-effort {@link GeminiFinishReason} mapping, mirroring how other translators
 * handle their own vendor's stop-reason field. Tool-use precedence is honored
 * exactly as antigravity-auth's {@code AntigravityStreamMapper} established it: a real Gemini
 * response that calls a function reports {@code finishReason=STOP}, so a {@link ToolUseBlock}
 * anywhere in {@code content} forces {@link io.github.intisy.ai.ir.IrStopReason#TOOL_USE}
 * regardless of the wire {@code finishReason}.
 *
 * <p>{@code usageMetadata.thoughtsTokenCount}/{@code totalTokenCount} map directly onto
 * {@link IrUsage#reasoningTokens}/{@link IrUsage#totalTokens} via {@link GeminiUsageCodec} --
 * no {@code extensions} stashing needed.
 */
final class GeminiResponseCodec {
    private GeminiResponseCodec() {
    }

    private static final String EXT_FINISH_REASON_RAW = "$finishReasonRaw";
    private static final String EXT_CANDIDATE_INDEX_RAW = "$candidateIndexRaw";
    private static final String EXT_CANDIDATES_EXTRA = "$candidatesExtra";

    private static final Set<String> TOP_LEVEL_KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "candidates", "usageMetadata", "modelVersion", "responseId"));

    static IrResponse decodeResponse(JsonCodec json, String wireJson) {
        Map<String, Object> root = GeminiJsonUtil.asMap(json.parse(wireJson));
        IrResponse r = new IrResponse();
        if (root == null) return r;

        r.id = GeminiJsonUtil.asString(root.get("responseId"));
        r.model = GeminiJsonUtil.asString(root.get("modelVersion"));

        List<Object> candidates = GeminiJsonUtil.asList(root.get("candidates"));
        if (candidates != null && !candidates.isEmpty()) {
            Map<String, Object> candidate0 = GeminiJsonUtil.asMap(candidates.get(0));
            decodeCandidate(json, candidate0, r);
            if (candidates.size() > 1) {
                putExtension(r, EXT_CANDIDATES_EXTRA, new ArrayList<>(candidates.subList(1, candidates.size())));
            }
        }

        Map<String, Object> usageMetadata = GeminiJsonUtil.asMap(root.get("usageMetadata"));
        r.usage = GeminiUsageCodec.decode(usageMetadata);

        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!TOP_LEVEL_KNOWN_KEYS.contains(e.getKey())) {
                putExtension(r, e.getKey(), e.getValue());
            }
        }
        return r;
    }

    private static void decodeCandidate(JsonCodec json, Map<String, Object> candidate, IrResponse r) {
        if (candidate == null) return;
        Map<String, Object> content = GeminiJsonUtil.asMap(candidate.get("content"));
        r.content = content == null ? null : GeminiBlockCodec.decodePartsList(json, content.get("parts"));

        boolean hasToolUse = false;
        if (r.content != null) {
            for (Block b : r.content) {
                if (b instanceof ToolUseBlock) {
                    hasToolUse = true;
                    break;
                }
            }
        }

        String finishReasonRaw = GeminiJsonUtil.asString(candidate.get("finishReason"));
        putExtension(r, EXT_FINISH_REASON_RAW, finishReasonRaw);
        r.stopReason = hasToolUse ? IrStopReason.TOOL_USE : GeminiFinishReason.toIr(finishReasonRaw);

        // Only a non-zero candidate index needs bookkeeping (0 is the default and needs no stash).
        Integer idx = GeminiJsonUtil.asInt(candidate.get("index"));
        if (idx != null && idx != 0) {
            putExtension(r, EXT_CANDIDATE_INDEX_RAW, idx);
        }
    }

    @SuppressWarnings("unchecked")
    static String encodeResponse(JsonCodec json, IrResponse r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r.id != null) m.put("responseId", r.id);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", GeminiBlockCodec.encodePartsList(json, r.content, null, null));

        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        Object rawFinishReason = r.extensions == null ? null : r.extensions.get(EXT_FINISH_REASON_RAW);
        candidate.put("finishReason", rawFinishReason != null ? rawFinishReason : GeminiFinishReason.toGemini(r.stopReason));
        Object candidateIndex = r.extensions == null ? null : r.extensions.get(EXT_CANDIDATE_INDEX_RAW);
        candidate.put("index", candidateIndex != null ? candidateIndex : 0);

        List<Object> candidates = new ArrayList<>();
        candidates.add(candidate);
        Object extraCandidates = r.extensions == null ? null : r.extensions.get(EXT_CANDIDATES_EXTRA);
        if (extraCandidates instanceof List) candidates.addAll((List<Object>) extraCandidates);
        m.put("candidates", candidates);

        if (r.usage != null) {
            m.put("usageMetadata", GeminiUsageCodec.encode(r.usage));
        }

        if (r.model != null) m.put("modelVersion", r.model);
        encodeLeftoverExtensions(r, m);
        return json.stringify(m);
    }

    private static void putExtension(IrResponse r, String key, Object value) {
        if (r.extensions == null) r.extensions = new LinkedHashMap<>();
        r.extensions.put(key, value);
    }

    private static void encodeLeftoverExtensions(IrResponse r, Map<String, Object> m) {
        if (r.extensions == null) return;
        for (Map.Entry<String, Object> e : r.extensions.entrySet()) {
            if (!e.getKey().startsWith("$")) m.put(e.getKey(), e.getValue());
        }
    }
}
