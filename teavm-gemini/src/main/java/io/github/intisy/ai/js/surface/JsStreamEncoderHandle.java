package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * A stateful handle over one stream encode, as a TypeScript consumer sees it.
 *
 * @implNote Never implemented, only emitted, for the same reason as
 * {@link JsStreamDecoderHandle}.
 */
@TsInterface
public interface JsStreamEncoderHandle {

    /**
     * Encodes one IR stream event to this vendor's wire text.
     *
     * @implNote An event with no wire representation for this vendor encodes to the empty string
     * rather than being reported as an error.
     */
    String encode(String irEventJson);
}
