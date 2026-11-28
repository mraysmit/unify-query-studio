package com.unify.querystudio.dialects;

/**
 * One column as {@link java.sql.DatabaseMetaData#getColumns} describes it: the raw input a
 * {@link ColumnTypeResolver} turns into a Calcite type.
 *
 * @param jdbcType      {@code DATA_TYPE}, a {@link java.sql.Types} code
 * @param typeName      {@code TYPE_NAME}, the type as declared in the database (may be empty)
 * @param columnSize    {@code COLUMN_SIZE}, or 0 when not reported
 * @param decimalDigits {@code DECIMAL_DIGITS}, or 0 when not reported
 */
public record JdbcColumn(String name, int jdbcType, String typeName, int columnSize, int decimalDigits, boolean nullable) {
}
