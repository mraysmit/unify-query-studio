package com.unify.querystudio.execution;

import org.apache.calcite.rel.type.RelDataType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Converts what the driver returned into the Java type the validated column type calls for, so
 * results do not depend on how a database stores a type (SQLite: booleans as 0/1, dates as text).
 */
final class Values {

    private Values() {
    }

    static Object typed(Object value, RelDataType type) {
        if (value == null) {
            return null;
        }
        return switch (type.getSqlTypeName()) {
            case BOOLEAN -> value instanceof Number n ? n.longValue() != 0 : value instanceof Boolean b ? b
                    : Boolean.parseBoolean(value.toString()) || "1".equals(value.toString());
            case TINYINT, SMALLINT, INTEGER, BIGINT -> value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
            case DECIMAL -> value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
            case FLOAT, REAL, DOUBLE -> value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
            case DATE -> value instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(value.toString().substring(0, 10));
            case TIMESTAMP -> value instanceof java.sql.Timestamp t ? t.toLocalDateTime()
                    : LocalDateTime.parse(value.toString().replace(' ', 'T'));
            case TIME -> value instanceof java.sql.Time t ? t.toLocalTime() : LocalTime.parse(value.toString());
            case CHAR, VARCHAR -> value.toString();
            default -> value;
        };
    }
}
