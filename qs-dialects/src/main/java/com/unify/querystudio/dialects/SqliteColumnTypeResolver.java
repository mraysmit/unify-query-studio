package com.unify.querystudio.dialects;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/**
 * SQLite: a declared type only gives a column an affinity. This applies SQLite's affinity rules in
 * SQLite's order of precedence, after first recognising the conventional names for booleans, dates,
 * times and timestamps, which SQLite applications store as integers or ISO-8601 text.
 */
public final class SqliteColumnTypeResolver implements ColumnTypeResolver {

    @Override
    public RelDataType resolve(JdbcColumn column, RelDataTypeFactory typeFactory) {
        String declared = column.typeName() == null ? "" : column.typeName().toUpperCase(Locale.ROOT).strip();
        RelDataType type = baseType(declared, column, typeFactory);
        return typeFactory.createTypeWithNullability(type, column.nullable());
    }

    private static RelDataType baseType(String declared, JdbcColumn column, RelDataTypeFactory f) {
        if (declared.isEmpty()) {
            return f.createSqlType(SqlTypeName.ANY);
        }
        if (declared.contains("BOOL")) {
            return f.createSqlType(SqlTypeName.BOOLEAN);
        }
        if (declared.contains("DATETIME") || declared.contains("TIMESTAMP")) {
            return f.createSqlType(SqlTypeName.TIMESTAMP);
        }
        if (declared.contains("DATE")) {
            return f.createSqlType(SqlTypeName.DATE);
        }
        if (declared.equals("TIME")) {
            return f.createSqlType(SqlTypeName.TIME);
        }
        // SQLite affinity rules, in SQLite's order.
        if (declared.contains("INT")) {
            return f.createSqlType(SqlTypeName.BIGINT);
        }
        if (declared.contains("CHAR") || declared.contains("CLOB") || declared.contains("TEXT")) {
            return f.createSqlType(SqlTypeName.VARCHAR);
        }
        if (declared.contains("BLOB")) {
            return f.createSqlType(SqlTypeName.VARBINARY);
        }
        if (declared.contains("REAL") || declared.contains("FLOA") || declared.contains("DOUB")) {
            return f.createSqlType(SqlTypeName.DOUBLE);
        }
        return column.columnSize() > 0
                ? f.createSqlType(SqlTypeName.DECIMAL, column.columnSize(), column.decimalDigits())
                : f.createSqlType(SqlTypeName.DECIMAL);
    }
}
