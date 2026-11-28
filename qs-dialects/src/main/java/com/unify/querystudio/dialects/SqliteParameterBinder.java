package com.unify.querystudio.dialects;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * SQLite: integers as INTEGER, other numbers as REAL (the driver would bind {@code BigDecimal} as
 * TEXT, which never matches an aggregate), booleans as 0/1, dates and times as ISO-8601 text.
 */
public final class SqliteParameterBinder implements ParameterBinder {

    @Override
    public void bind(PreparedStatement statement, int index, Object value, RelDataType type) throws SQLException {
        switch (value) {
            case null -> statement.setNull(index, Types.NULL);
            case NlsString s -> statement.setString(index, s.getValue());
            case Boolean b -> statement.setInt(index, b ? 1 : 0);
            case BigDecimal d -> bindDecimal(statement, index, d);
            case Long l -> statement.setLong(index, l);
            case Integer i -> statement.setLong(index, i);
            case Number n -> statement.setDouble(index, n.doubleValue());
            case DateString d -> statement.setString(index, d.toString());
            case TimeString t -> statement.setString(index, t.toString());
            case TimestampString t -> statement.setString(index, t.toString());
            default -> statement.setString(index, value.toString());
        }
    }

    private static void bindDecimal(PreparedStatement statement, int index, BigDecimal d) throws SQLException {
        BigDecimal stripped = d.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.precision() - stripped.scale() <= 18) {
            statement.setLong(index, stripped.longValueExact());
        } else {
            statement.setDouble(index, d.doubleValue());
        }
    }
}
