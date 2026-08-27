// Generated from Java sources. Do not edit.

/**
 * A stateful handle over one stream decode, as a TypeScript consumer sees it.
 *
 * @remarks
 * Never implemented, only emitted. The Java handle it describes speaks
 * `JSString` and extends `JSObject`, neither of which means anything to a TypeScript
 * caller, which is why this shape is declared apart from it rather than annotated onto it.
 */
export interface JsStreamDecoderHandle {
  /**
   * Feeds one raw vendor chunk and returns the IR stream events it completed, as a JSON array.
   *
   * @param chunk - the bytes as they arrived, at whatever boundary the transport gave them
   * @returns the IR stream events the chunk completed, as a JSON array
   * @remarks
   * Partial lines and frames are buffered inside the handle across calls, so a chunk
   * completing nothing correctly returns an empty array.
   */
  decode(chunk: string): string;
}

/**
 * A stateful handle over one stream encode, as a TypeScript consumer sees it.
 *
 * @remarks
 * Never implemented, only emitted, for the same reason as
 * {@link JsStreamDecoderHandle}.
 */
export interface JsStreamEncoderHandle {
  /**
   * Encodes one IR stream event to this vendor's wire text.
   *
   * @param irEventJson - the IR stream event
   * @returns the wire text to emit
   * @remarks
   * An event with no wire representation for this vendor encodes to the empty string
   * rather than being reported as an error.
   */
  encode(irEventJson: string): string;
}

/**
 * Gemini wire JSON to an IR request.
 *
 * @param wireJson - the request in Gemini's own format
 * @returns the canonical IR request
 */
export declare function geminiDecodeRequest(wireJson: string): string;
/**
 * Gemini wire JSON to an IR response.
 *
 * @param wireJson - the response in Gemini's own format
 * @returns the canonical IR response
 */
export declare function geminiDecodeResponse(wireJson: string): string;
/**
 * An IR request to Gemini wire JSON.
 *
 * @param irRequestJson - the canonical IR request
 * @returns the request in Gemini's own format
 */
export declare function geminiEncodeRequest(irRequestJson: string): string;
/**
 * An IR response to Gemini wire JSON.
 *
 * @param irResponseJson - the canonical IR response
 * @returns the response in Gemini's own format
 */
export declare function geminiEncodeResponse(irResponseJson: string): string;
/**
 * Opens a decode handle for one connection's stream.
 *
 * @returns a handle carrying that connection's decode state
 */
export declare function geminiNewStreamDecoder(): JsStreamDecoderHandle;
/**
 * Opens an encode handle for one connection's stream.
 *
 * @returns a handle carrying that connection's encode state
 */
export declare function geminiNewStreamEncoder(): JsStreamEncoderHandle;
/**
 * Parse and stringify with no IR type involved, proving the JSON codec crosses TeaVM.
 *
 * @param json - any JSON document
 * @returns the same document, parsed and stringified again
 */
export declare function jsonRoundTrip(json: string): string;

