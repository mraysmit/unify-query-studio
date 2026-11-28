package com.unify.querystudio.dialects;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the values Calcite literals carry ({@code NlsString}, {@code BigDecimal}, {@code DateString}…)
 * the way SQLite stores them. Checked against a real SQLite with {@code typeof()}.
 */
class SqliteParameterBinderTest {

    final RelDataTypeFactory types = new JavaTypeFactoryImpl();
    final ParameterBinder binder = new SqliteParameterBinder();

    /** Binds one value and returns SQLite's typeof() and the value as SQLite holds it. */
    List<Object> bound(Object value, SqlTypeName type) throws Exception {
        RelDataType t = types.createSqlType(type);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             PreparedStatement ps = c.prepareStatement("SELECT typeof(?1), ?1")) {
            binder.bind(ps, 1, value, t);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return List.of(rs.getString(1), rs.getObject(2) == null ? "null" : rs.getObject(2));
            }
        }
    }

    @Test
    void integralNumbersBindAsIntegers() throws Exception {
        assertThat(bound(new BigDecimal("400"), SqlTypeName.DECIMAL)).containsExactly("integer", 400);
        assertThat(bound(new BigDecimal("400.00"), SqlTypeName.DECIMAL)).containsExactly("integer", 400);
        assertThat(bound(9_000_000_000L, SqlTypeName.BIGINT)).containsExactly("integer", 9_000_000_000L);
    }

    @Test
    void fractionalNumbersBindAsReals_neverAsText() throws Exception {
        // The driver would bind BigDecimal as TEXT, and SUM(x) > '2.5' never matches.
        assertThat(bound(new BigDecimal("2.5"), SqlTypeName.DECIMAL)).containsExactly("real", 2.5);
        assertThat(bound(2.5d, SqlTypeName.DOUBLE)).containsExactly("real", 2.5);
    }

    @Test
    void textBindsAsText() throws Exception {
        assertThat(bound(new NlsString("O'Brien", null, null), SqlTypeName.VARCHAR)).containsExactly("text", "O'Brien");
        assertThat(bound("02134", SqlTypeName.VARCHAR)).containsExactly("text", "02134");
    }

    @Test
    void booleansBindAsZeroOrOne() throws Exception {
        assertThat(bound(true, SqlTypeName.BOOLEAN)).containsExactly("integer", 1);
        assertThat(bound(false, SqlTypeName.BOOLEAN)).containsExactly("integer", 0);
    }

    @Test
    void datesAndTimesBindAsIsoText() throws Exception {
        assertThat(bound(new DateString("2026-11-01"), SqlTypeName.DATE)).containsExactly("text", "2026-11-01");
        assertThat(bound(new TimestampString("2026-11-01 13:45:00"), SqlTypeName.TIMESTAMP))
                .containsExactly("text", "2026-11-01 13:45:00");
        assertThat(bound(new TimeString("13:45:00"), SqlTypeName.TIME)).containsExactly("text", "13:45:00");
    }

    @Test
    void nullBindsAsNull() throws Exception {
        assertThat(bound(null, SqlTypeName.VARCHAR)).containsExactly("null", "null");
    }
}
