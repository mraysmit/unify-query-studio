package com.unify.querystudio.compiler;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * A named parameter a query asks for, typed by Calcite's validator from where it is used.
 *
 * @param name the name, without its colon
 */
public record QueryParameter(String name, RelDataType type) {

    /**
     * A value from the user (text, number or boolean, as JSON gives them) in the form the dialect's
     * binder takes, the same forms literals have.
     *
     * @throws ParameterException if the value does not fit the parameter's type
     */
    public Object convert(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        try {
            return switch (type.getSqlTypeName().getFamily()) {
                case NUMERIC -> value instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(text);
                case BOOLEAN -> switch (text.toLowerCase(java.util.Locale.ROOT)) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> throw wrong("true or false");
                };
                case DATE -> new DateString(LocalDate.parse(text).toString());
                case TIME -> new TimeString(LocalTime.parse(text).toString());
                case TIMESTAMP -> new TimestampString(LocalDateTime.parse(text.replace(' ', 'T')).toString().replace('T', ' '));
                case null, default -> value.toString();
            };
        } catch (NumberFormatException e) {
            throw wrong("a number");
        } catch (DateTimeParseException e) {
            throw wrong(switch (type.getSqlTypeName().getFamily()) {
                case DATE -> "a date such as 2026-11-01";
                case TIME -> "a time such as 14:30:00";
                default -> "a date and time such as 2026-11-01 14:30:00";
            });
        }
    }

    private ParameterException wrong(String expected) {
        return new ParameterException("The value for :" + name + " must be " + expected + ".");
    }
}
