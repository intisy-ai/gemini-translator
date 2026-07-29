package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.IrUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gemini {@code usageMetadata} object <-> {@link IrUsage}, shared by the response and streaming
 * codecs. {@code promptTokenCount}/{@code candidatesTokenCount}/{@code cachedContentTokenCount}
 * map cleanly onto {@code inputTokens}/{@code outputTokens}/{@code cacheReadInputTokens};
 * {@code thoughtsTokenCount}/{@code totalTokenCount} map onto {@link IrUsage#reasoningTokens}/
 * {@link IrUsage#totalTokens} -- fields added to the neutral IR specifically because Gemini
 * surfaces reasoning-token accounting and a derived total explicitly, where some other vendors
 * fold reasoning into their output-token count and report no total at all.
 * {@code cacheCreationInputTokens} has no Gemini analog (Gemini's context cache is a separate
 * resource, not a per-request write count) and is always {@code null} on decode.
 */
final class GeminiUsageCodec {
    private GeminiUsageCodec() {
    }

    static IrUsage decode(Object raw) {
        Map<String, Object> m = GeminiJsonUtil.asMap(raw);
        if (m == null) return null;
        IrUsage u = new IrUsage();
        u.inputTokens = GeminiJsonUtil.asInt(m.get("promptTokenCount"));
        u.outputTokens = GeminiJsonUtil.asInt(m.get("candidatesTokenCount"));
        u.cacheReadInputTokens = GeminiJsonUtil.asInt(m.get("cachedContentTokenCount"));
        u.reasoningTokens = GeminiJsonUtil.asInt(m.get("thoughtsTokenCount"));
        u.totalTokens = GeminiJsonUtil.asInt(m.get("totalTokenCount"));
        return u;
    }

    static Map<String, Object> encode(IrUsage u) {
        if (u == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (u.inputTokens != null) m.put("promptTokenCount", u.inputTokens);
        if (u.outputTokens != null) m.put("candidatesTokenCount", u.outputTokens);
        if (u.cacheReadInputTokens != null) m.put("cachedContentTokenCount", u.cacheReadInputTokens);
        if (u.reasoningTokens != null) m.put("thoughtsTokenCount", u.reasoningTokens);
        if (u.totalTokens != null) m.put("totalTokenCount", u.totalTokens);
        return m;
    }
}
