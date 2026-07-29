package io.github.intisy.ai.ir.translators.gemini;

import java.util.List;
import java.util.Map;

/**
 * Narrowing helpers over the {@code JsonCodec} parsed shape. Each translator package keeps its
 * own copy rather than sharing one across vendors.
 */
final class GeminiJsonUtil {
    private GeminiJsonUtil() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    static Boolean asBoolean(Object o) {
        return o instanceof Boolean ? (Boolean) o : null;
    }

    static Integer asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    static Double asDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }
}
