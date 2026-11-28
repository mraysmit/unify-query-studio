package learning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What SQLite and the xerial sqlite-jdbc driver (3.54.1.0) do, as the design relies on it. */
class SqliteDriverLearningTest {

    @Test
    void driverBindsBigDecimalAsText_andLongAsInteger() throws Exception {
        assertThat(Fixture.run("SELECT typeof(?)", new BigDecimal("400")).getFirst()).containsExactly("text");
        assertThat(Fixture.run("SELECT typeof(?)", 400L).getFirst()).containsExactly("integer");
    }

    @Test
    void anAggregateComparedWithATextParameterNeverMatches() throws Exception {
        String sql = "SELECT country FROM Customers c JOIN Orders o ON o.customer_id = c.customer_id "
                + "GROUP BY country HAVING SUM(o.total) > ?";
        assertThat(Fixture.run(sql, new BigDecimal("400"))).isEmpty();
        assertThat(Fixture.run(sql, 400L)).hasSize(4);
    }

    @Test
    void sqliteRejectsAnsiDateLiterals_andComparesIsoTextDates() throws Exception {
        assertThatThrownBy(() -> Fixture.run("SELECT 1 FROM Orders WHERE order_date >= DATE '2026-11-01'"))
                .hasMessageContaining("syntax error");
        assertThat(Fixture.run("SELECT count(*) FROM Orders WHERE order_date >= ?", "2026-11-01").getFirst().getFirst())
                .isEqualTo(12);
    }

    @Test
    void sqliteTextEqualityIsCaseSensitive_whileLikeIsNotForAscii() throws Exception {
        assertThat(Fixture.run("SELECT 1 FROM Orders WHERE status = 'completed'")).isEmpty();
        assertThat(Fixture.run("SELECT 1 FROM Orders WHERE LOWER(status) = LOWER('completed')")).isNotEmpty();
        assertThat(Fixture.run("SELECT 1 FROM Customers WHERE name LIKE 'amelia%'")).hasSize(1);
    }

    @Test
    void bundledSqliteSupportsWindowFunctionsAndRightJoins() throws Exception {
        String version = (String) Fixture.run("SELECT sqlite_version()").getFirst().getFirst();
        List<Integer> parts = java.util.Arrays.stream(version.split("\\.")).map(Integer::parseInt).toList();
        // Window functions since 3.25, RIGHT and FULL JOIN since 3.39.
        assertThat(parts.get(0) * 1000 + parts.get(1)).isGreaterThanOrEqualTo(3039);
        assertThat(Fixture.run("SELECT RANK() OVER (ORDER BY total DESC) FROM Orders")).hasSize(20);
    }

    @Test
    void readOnlyConnectionsRefuseWrites() throws Exception {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + Fixture.DB, config.toProperties());
             var s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("DELETE FROM Orders")).hasMessageContaining("readonly");
        }
    }
}
