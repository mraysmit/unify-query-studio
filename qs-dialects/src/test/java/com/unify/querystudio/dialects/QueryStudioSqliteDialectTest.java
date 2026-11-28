package com.unify.querystudio.dialects;

import org.apache.calcite.config.NullCollation;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.SqliteSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.TimestampString;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Corrections to Calcite's SqliteSqlDialect found in phase 1 (docs/spike-results.md). */
class QueryStudioSqliteDialectTest {

    final SqlDialect dialect = QueryStudioSqliteDialect.INSTANCE;

    static String unparse(String sql, SqlDialect dialect) throws Exception {
        SqlNode node = SqlParser.create(sql).parseQuery();
        return node.toSqlString(c -> c.withDialect(dialect)).getSql();
    }

    /**
     * Typed literals, as RelToSqlConverter produces them. (The parser's DATE '…' is an untyped
     * literal until validation, so unparsing parsed SQL never reaches the dialect's hook.)
     */
    static String unparse(SqlNode literal, SqlDialect dialect) {
        return literal.toSqlString(c -> c.withDialect(dialect)).getSql();
    }

    @Test
    void writesDateLiteralsAsIsoTextBecauseSqliteRejectsDateKeywordLiterals() {
        SqlNode date = SqlLiteral.createDate(new DateString("2026-11-01"), SqlParserPos.ZERO);
        assertThat(unparse(date, SqliteSqlDialect.DEFAULT)).isEqualTo("DATE '2026-11-01'");
        assertThat(unparse(date, dialect)).isEqualTo("'2026-11-01'");
    }

    @Test
    void writesTimestampLiteralsAsIsoText() {
        SqlNode ts = SqlLiteral.createTimestamp(SqlTypeName.TIMESTAMP, new TimestampString("2026-11-01 13:45:00"), 0, SqlParserPos.ZERO);
        assertThat(unparse(ts, dialect)).isEqualTo("'2026-11-01 13:45:00'");
    }

    @Test
    void sortsByExpressionNotOrdinal() {
        // Ordinal ORDER BY forces a subquery when sorting on a hidden column (phase 1 finding).
        assertThat(dialect.getConformance().isSortByOrdinal()).isFalse();
    }

    @Test
    void nullsSortLowAsInSqlite() {
        assertThat(dialect.getNullCollation()).isEqualTo(NullCollation.LOW);
    }

    @Test
    void quotesIdentifiersWithDoubleQuotes() throws Exception {
        assertThat(unparse("SELECT \"Order Date\" FROM \"Orders\"", dialect)).contains("\"Order Date\"").contains("\"Orders\"");
    }

    @Test
    void declaresTheStatisticalAggregatesSqliteLacks_soTheCompilerRewritesThem() {
        var d = QueryStudioSqliteDialect.INSTANCE;
        for (var kind : java.util.List.of(org.apache.calcite.sql.SqlKind.STDDEV_SAMP, org.apache.calcite.sql.SqlKind.VAR_SAMP,
                org.apache.calcite.sql.SqlKind.STDDEV_POP, org.apache.calcite.sql.SqlKind.VAR_POP)) {
            assertThat(d.supportsAggregateFunction(kind)).as(kind.name()).isFalse();
        }
        assertThat(d.supportsAggregateFunction(org.apache.calcite.sql.SqlKind.SUM)).isTrue();
        // SQLite has AVG; Calcite's stock dialect does not say so, and AVG would be rewritten needlessly.
        assertThat(d.supportsAggregateFunction(org.apache.calcite.sql.SqlKind.AVG)).isTrue();
        assertThat(d.supportsAggregateFunction(org.apache.calcite.sql.SqlKind.COUNT)).isTrue();
    }
}
