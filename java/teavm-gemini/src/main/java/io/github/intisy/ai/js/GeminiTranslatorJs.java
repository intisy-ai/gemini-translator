package io.github.intisy.ai.js;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.JsonCodec;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.ir.stream.IrStreamEvent;
import io.github.intisy.ai.ir.json.IrJson;
import io.github.intisy.ai.ir.translators.gemini.GeminiTranslator;

import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSString;

import java.util.ArrayList;
import java.util.List;

/**
 * TeaVM JS export surface over the Gemini translator (round-trip smoke export plus the
 * {@link GeminiTranslator} non-streaming/streaming exports). Mirrors core-ir's
 * {@code io.github.intisy.ai.js.CoreIrJs} export style.
 */
public final class GeminiTranslatorJs {
    private GeminiTranslatorJs() {
    }

    /**
     * Bare parse+stringify round trip through {@link SimpleJsonCodec}, with no IR type involved --
     * proves the JSON codec itself is wired through TeaVM correctly.
     */
    @JSExport
    public static String jsonRoundTrip(String json) {
        JsonCodec codec = new SimpleJsonCodec();
        return codec.stringify(codec.parse(json));
    }

    // ---- Non-streaming translator exports -----------------------------------------------------

    @JSExport
    public static String geminiDecodeRequest(String wireJson) {
        JsonCodec json = new SimpleJsonCodec();
        IrRequest request = new GeminiTranslator(json).decodeRequest(wireJson);
        return IrJson.serializeRequest(json, request);
    }

    @JSExport
    public static String geminiEncodeRequest(String irRequestJson) {
        JsonCodec json = new SimpleJsonCodec();
        IrRequest request = IrJson.parseRequest(json, irRequestJson);
        return new GeminiTranslator(json).encodeRequest(request);
    }

    @JSExport
    public static String geminiDecodeResponse(String wireJson) {
        JsonCodec json = new SimpleJsonCodec();
        IrResponse response = new GeminiTranslator(json).decodeResponse(wireJson);
        return IrJson.serializeResponse(json, response);
    }

    @JSExport
    public static String geminiEncodeResponse(String irResponseJson) {
        JsonCodec json = new SimpleJsonCodec();
        IrResponse response = IrJson.parseResponse(json, irResponseJson);
        return new GeminiTranslator(json).encodeResponse(response);
    }

    // ---- Streaming translator exports ----------------------------------------------------------

    /** Stateful JS handle over one {@link StreamDecoder} -- feed a raw vendor chunk, get back a JSON array of IR events. */
    public interface JsStreamDecoderHandle extends JSObject {
        JSString decode(JSString chunk);
    }

    /** Stateful JS handle over one {@link StreamEncoder} -- feed one IR event's JSON, get back the vendor's wire text for it. */
    public interface JsStreamEncoderHandle extends JSObject {
        JSString encode(JSString irEventJson);
    }

    @JSExport
    public static JsStreamDecoderHandle geminiNewStreamDecoder() {
        return newStreamDecoderHandle(new GeminiTranslator(new SimpleJsonCodec()));
    }

    @JSExport
    public static JsStreamEncoderHandle geminiNewStreamEncoder() {
        return newStreamEncoderHandle(new GeminiTranslator(new SimpleJsonCodec()));
    }

    private static JsStreamDecoderHandle newStreamDecoderHandle(Translator translator) {
        JsonCodec json = new SimpleJsonCodec();
        StreamDecoder decoder = translator.newStreamDecoder();
        return new JsStreamDecoderHandle() {
            @Override
            public JSString decode(JSString chunk) {
                String text = chunk == null ? "" : chunk.stringValue();
                List<IrStreamEvent> events = decoder.decode(text);
                List<Object> eventMaps = new ArrayList<>();
                for (IrStreamEvent event : events) eventMaps.add(IrJson.toMap(event));
                return JSString.valueOf(json.stringify(eventMaps));
            }
        };
    }

    private static JsStreamEncoderHandle newStreamEncoderHandle(Translator translator) {
        JsonCodec json = new SimpleJsonCodec();
        StreamEncoder encoder = translator.newStreamEncoder();
        return new JsStreamEncoderHandle() {
            @Override
            public JSString encode(JSString irEventJson) {
                String text = irEventJson == null ? "" : irEventJson.stringValue();
                IrStreamEvent event = IrJson.parseStreamEvent(json, text);
                return JSString.valueOf(encoder.encode(event));
            }
        };
    }
}
