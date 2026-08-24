package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.spi.Translator;

/**
 * Google Gemini {@code generateContent}/{@code streamGenerateContent} {@link Translator}: maps
 * the Gemini wire format (request, response, and SSE stream) to/from the canonical IR. Scope is
 * the {@code generateContent} FORMAT only ({@code contents}/{@code systemInstruction}/
 * {@code tools}/{@code toolConfig}/{@code generationConfig}; {@code candidates}/
 * {@code usageMetadata}/{@code finishReason}; {@code streamGenerateContent} SSE) -- the
 * cloudcode-pa transport envelope, URL, {@code v1internal:} path, project id and auth headers
 * belong to the antigravity PROVIDER, not this library.
 *
 * <p>No gson, no reflection: JSON (de)serialization goes through the injected {@link JsonCodec},
 * matching this module's SPI-injection pattern for every translator.
 *
 * <p>The IR&lt;-&gt;Gemini mapping is ported faithfully from antigravity-auth's already
 * battle-tested vendor-format bridge against real cloudcode-pa
 * ({@code AntigravityFormatBridge}, {@code AntigravityStreamMapper}, {@code GeminiTransforms}) --
 * see {@code GeminiRequestCodec}/{@code GeminiResponseCodec}/{@code GeminiBlockCodec}'s javadoc
 * for the field-by-field mapping decisions and what deliberately stays out of scope (antigravity's
 * own provider-specific workarounds -- schema placeholder-filling, {@code VALIDATED} tool-choice
 * mode, conversation re-alternation/merging, thinking-signature caching -- are provider
 * concerns, not part of the neutral Gemini wire format this translator implements).
 */
public final class GeminiTranslator implements Translator {
    private final JsonCodec json;

    public GeminiTranslator(JsonCodec json) {
        this.json = json;
    }

    @Override
    public IrRequest decodeRequest(String wireJson) {
        return GeminiRequestCodec.decodeRequest(json, wireJson);
    }

    @Override
    public String encodeRequest(IrRequest request) {
        return GeminiRequestCodec.encodeRequest(json, request);
    }

    @Override
    public IrResponse decodeResponse(String wireJson) {
        return GeminiResponseCodec.decodeResponse(json, wireJson);
    }

    @Override
    public String encodeResponse(IrResponse response) {
        return GeminiResponseCodec.encodeResponse(json, response);
    }

    @Override
    public StreamDecoder newStreamDecoder() {
        return new GeminiStreamDecoder(json);
    }

    @Override
    public StreamEncoder newStreamEncoder() {
        return new GeminiStreamEncoder(json);
    }
}
