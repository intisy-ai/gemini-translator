package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * A stateful handle over one stream decode, as a TypeScript consumer sees it.
 *
 * @implNote Never implemented, only emitted. The Java handle it describes speaks
 * {@code JSString} and extends {@code JSObject}, neither of which means anything to a TypeScript
 * caller, which is why this shape is declared apart from it rather than annotated onto it.
 */
@TsInterface
public interface JsStreamDecoderHandle {

    /**
     * Feeds one raw vendor chunk and returns the IR stream events it completed, as a JSON array.
     *
     * @param chunk the bytes as they arrived, at whatever boundary the transport gave them
     * @return the IR stream events the chunk completed, as a JSON array
     * @implNote Partial lines and frames are buffered inside the handle across calls, so a chunk
     * completing nothing correctly returns an empty array.
     */
    String decode(String chunk);
}
