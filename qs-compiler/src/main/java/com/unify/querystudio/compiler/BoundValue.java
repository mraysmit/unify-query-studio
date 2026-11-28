package com.unify.querystudio.compiler;

import org.apache.calcite.rel.type.RelDataType;

/**
 * A bind value as a literal carried it ({@code String}, {@code BigDecimal}, {@code Boolean},
 * {@code DateString}, …) with its Calcite type; the dialect's binder decides how it is sent.
 *
 * @param parameter the named parameter this value stands for, or null for a literal from the query;
 *                  its value is null until {@link CompiledQuery#bind} supplies one
 */
public record BoundValue(Object value, RelDataType type, String parameter) {

    public BoundValue(Object value, RelDataType type) {
        this(value, type, null);
    }
}
