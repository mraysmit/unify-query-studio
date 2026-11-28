package com.unify.querystudio.dialects;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite column types are declarations, not constraints: a declared type only gives the column an
 * affinity (https://www.sqlite.org/datatype3.html#type_affinity). The resolver follows those rules,
 * recognises the conventional names for booleans, dates and timestamps, and produces Calcite types.
 */
class SqliteColumnTypeResolverTest {

    final RelDataTypeFactory types = new JavaTypeFactoryImpl();
    final ColumnTypeResolver resolver = new SqliteColumnTypeResolver();

    RelDataType resolve(String declared, int size, int digits, boolean nullable) {
        return resolver.resolve(new JdbcColumn("c", Types.OTHER, declared, size, digits, nullable), types);
    }

    @ParameterizedTest
    @CsvSource({
            // integer affinity: any declared type containing INT
            "INTEGER, BIGINT", "INT, BIGINT", "BIGINT, BIGINT", "SMALLINT, BIGINT", "UNSIGNED BIG INT, BIGINT",
            // text affinity: CHAR, CLOB or TEXT
            "TEXT, VARCHAR", "VARCHAR(40), VARCHAR", "NVARCHAR(100), VARCHAR", "CLOB, VARCHAR",
            // real affinity: REAL, FLOA or DOUB
            "REAL, DOUBLE", "DOUBLE, DOUBLE", "DOUBLE PRECISION, DOUBLE", "FLOAT, DOUBLE",
            // blob affinity
            "BLOB, VARBINARY",
            // conventional names SQLite stores as integers or ISO-8601 text
            "BOOLEAN, BOOLEAN", "BOOL, BOOLEAN",
            "DATE, DATE", "DATETIME, TIMESTAMP", "TIMESTAMP, TIMESTAMP", "TIME, TIME"})
    void followsSqliteAffinityAndConventionalNames(String declared, SqlTypeName expected) {
        assertThat(resolve(declared, 0, 0, true).getSqlTypeName()).isEqualTo(expected);
    }

    @Test
    void containingIntWinsAsInSqlite() {
        // SQLite's own example: "POINT" contains "INT", so it has integer affinity.
        assertThat(resolve("POINT", 0, 0, true).getSqlTypeName()).isEqualTo(SqlTypeName.BIGINT);
    }

    @Test
    void numericAffinityKeepsDeclaredPrecisionAndScale() {
        RelDataType decimal = resolve("DECIMAL(10,2)", 10, 2, true);
        assertThat(decimal.getSqlTypeName()).isEqualTo(SqlTypeName.DECIMAL);
        assertThat(decimal.getPrecision()).isEqualTo(10);
        assertThat(decimal.getScale()).isEqualTo(2);
        assertThat(resolve("NUMERIC", 0, 0, true).getSqlTypeName()).isEqualTo(SqlTypeName.DECIMAL);
    }

    @Test
    void textHasNoLengthBecauseSqliteDoesNotEnforceOne() {
        assertThat(resolve("VARCHAR(40)", 40, 0, true).getPrecision()).isEqualTo(RelDataType.PRECISION_NOT_SPECIFIED);
    }

    @Test
    void noDeclaredTypeIsAny() {
        // Columns of views built from expressions often have no declared type.
        assertThat(resolve("", 0, 0, true).getSqlTypeName()).isEqualTo(SqlTypeName.ANY);
        assertThat(resolve(null, 0, 0, true).getSqlTypeName()).isEqualTo(SqlTypeName.ANY);
    }

    @Test
    void declaredTypesAreCaseInsensitive() {
        assertThat(resolve("integer", 0, 0, true).getSqlTypeName()).isEqualTo(SqlTypeName.BIGINT);
        assertThat(resolve("Date", 0, 0, true).getSqlTypeName()).isEqualTo(SqlTypeName.DATE);
    }

    @Test
    void nullabilityComesFromTheColumn() {
        assertThat(resolve("TEXT", 0, 0, true).isNullable()).isTrue();
        assertThat(resolve("TEXT", 0, 0, false).isNullable()).isFalse();
    }
}
