package com.unify.querystudio.dialects;

import org.apache.calcite.rel.type.RelDataType;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Binds a parameter value to a statement the way the target database stores that type.
 * Values are those Calcite literals carry: {@code NlsString}, {@code BigDecimal}, {@code Boolean},
 * {@code DateString}, {@code TimeString}, {@code TimestampString}, or plain Java equivalents.
 */
@FunctionalInterface
public interface ParameterBinder {

    void bind(PreparedStatement statement, int index, Object value, RelDataType type) throws SQLException;
}
