package com.unify.querystudio.dialects;

import org.apache.calcite.sql.SqlAbstractDateTimeLiteral;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.SqliteSqlDialect;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;

/**
 * Calcite's {@link SqliteSqlDialect} with the corrections found in phase 1:
 * <ul>
 *   <li>ORDER BY is written by expression, not ordinal, so sorting on a column that is not
 *       selected never forces the ORDER BY into a subquery;</li>
 *   <li>date and timestamp literals are written as ISO-8601 text, because SQLite rejects
 *       {@code DATE '…'} and stores dates as text;</li>
 *   <li>AVG is declared supported (SQLite has it; Calcite's dialect lists only COUNT, SUM, MIN and
 *       MAX), so the compiler rewrites only the aggregates SQLite really lacks: STDDEV and VAR.</li>
 * </ul>
 */
public final class QueryStudioSqliteDialect extends SqliteSqlDialect {

    public static final QueryStudioSqliteDialect INSTANCE = new QueryStudioSqliteDialect();

    private final SqlConformance conformance = new SqlDelegatingConformance(super.getConformance()) {
        @Override
        public boolean isSortByOrdinal() {
            return false;
        }
    };

    private QueryStudioSqliteDialect() {
        super(SqliteSqlDialect.DEFAULT_CONTEXT);
    }

    @Override
    public SqlConformance getConformance() {
        return conformance;
    }

    @Override
    public boolean supportsAggregateFunction(SqlKind kind) {
        return kind == SqlKind.AVG || super.supportsAggregateFunction(kind);
    }

    @Override
    public void unparseDateTimeLiteral(SqlWriter writer, SqlAbstractDateTimeLiteral literal, int leftPrec, int rightPrec) {
        writer.literal("'" + literal.toFormattedString() + "'");
    }
}
