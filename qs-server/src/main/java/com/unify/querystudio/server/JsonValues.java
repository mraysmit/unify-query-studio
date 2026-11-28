package com.unify.querystudio.server;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;

/**
 * Result values for JSON (technical design, execution): numbers JavaScript cannot hold exactly
 * (integers beyond 2^53, exact decimals) are sent as strings, dates and times as ISO text, and
 * binary values as a size note.
 */
final class JsonValues {

    private static final long SAFE_INTEGER = (1L << 53) - 1;

    private JsonValues() {
    }

    static Object of(Object value) {
        return switch (value) {
            case null -> null;
            case Long l when Math.abs(l) > SAFE_INTEGER -> l.toString();
            case BigDecimal d -> d.toPlainString();
            case TemporalAccessor t -> t.toString();
            case byte[] bytes -> "(" + bytes.length + " bytes)";
            default -> value;
        };
    }
}
