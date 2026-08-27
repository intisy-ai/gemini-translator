package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsModule;

/**
 * The JavaScript module surface {@link io.github.intisy.ai.js.GeminiTranslatorJs} exports, typed
 * for a TypeScript consumer.
 *
 * @implNote Never implemented, only emitted: {@link TsModule} renders its members as free functions,
 * which is the shape a TeaVM ES2015 module actually exports. The non-streaming members carry the
 * vendor's wire JSON one way and core-ir's own IR JSON the other; the streaming pair hands back a
 * stateful handle instead, because a stream spans calls.
 */
@TsModule
public interface GeminiTranslatorSurface {

    /**
     * Parse and stringify with no IR type involved, proving the JSON codec crosses TeaVM.
     *
     * @param json any JSON document
     * @return the same document, parsed and stringified again
     */
    String jsonRoundTrip(String json);

    /**
     * Gemini wire JSON to an IR request.
     *
     * @param wireJson the request in Gemini's own format
     * @return the canonical IR request
     */
    String geminiDecodeRequest(String wireJson);

    /**
     * An IR request to Gemini wire JSON.
     *
     * @param irRequestJson the canonical IR request
     * @return the request in Gemini's own format
     */
    String geminiEncodeRequest(String irRequestJson);

    /**
     * Gemini wire JSON to an IR response.
     *
     * @param wireJson the response in Gemini's own format
     * @return the canonical IR response
     */
    String geminiDecodeResponse(String wireJson);

    /**
     * An IR response to Gemini wire JSON.
     *
     * @param irResponseJson the canonical IR response
     * @return the response in Gemini's own format
     */
    String geminiEncodeResponse(String irResponseJson);

    /**
     * Opens a decode handle for one connection's stream.
     *
     * @return a handle carrying that connection's decode state
     */
    JsStreamDecoderHandle geminiNewStreamDecoder();

    /**
     * Opens an encode handle for one connection's stream.
     *
     * @return a handle carrying that connection's encode state
     */
    JsStreamEncoderHandle geminiNewStreamEncoder();
}
