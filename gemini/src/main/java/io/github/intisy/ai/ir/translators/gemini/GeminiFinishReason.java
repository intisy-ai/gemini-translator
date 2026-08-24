package io.github.intisy.ai.ir.translators.gemini;

import io.github.intisy.ai.ir.IrStopReason;

/**
 * Gemini {@code finishReason} <-> {@link IrStopReason}. Only {@code STOP} and {@code MAX_TOKENS}
 * have a clean IR analog; every other Gemini reason (e.g. {@code SAFETY}, {@code RECITATION},
 * {@code OTHER}, {@code MALFORMED_FUNCTION_CALL}) falls back to {@link IrStopReason#ERROR} going
 * in, with the exact original string preserved via the response/event {@code extensions} bag so
 * the round trip stays lossless for the same vendor (mirroring how other translators preserve a
 * vendor's own stop-reason string).
 *
 * <p>Gemini has no {@code finishReason} of its own for a tool call: a real Gemini response that
 * calls a function reports {@code finishReason=STOP} with a {@code functionCall} part present.
 * The tool-use precedence (a {@code functionCall} in the content always yields
 * {@link IrStopReason#TOOL_USE}, regardless of {@code finishReason}) lives in the request/response
 * codecs that call {@link #toIr}, matching the battle-tested precedence in antigravity-auth's
 * {@code AntigravityStreamMapper} ({@code stopReason = "tool_use"} wins over the
 * {@code GEMINI_STOP} table).
 */
final class GeminiFinishReason {
    private GeminiFinishReason() {
    }

    static String toIr(String geminiReason) {
        if ("STOP".equals(geminiReason)) return IrStopReason.END_TURN;
        if ("MAX_TOKENS".equals(geminiReason)) return IrStopReason.MAX_TOKENS;
        return IrStopReason.ERROR;
    }

    static String toGemini(String irStopReason) {
        if (IrStopReason.MAX_TOKENS.equals(irStopReason)) return "MAX_TOKENS";
        // END_TURN, TOOL_USE and any other IR reason (stop_sequence/pause_turn/refusal/error) all
        // correspond to a real Gemini STOP -- Gemini reports a completed function call as STOP too.
        return "STOP";
    }
}
