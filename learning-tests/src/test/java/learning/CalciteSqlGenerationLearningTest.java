package learning;

import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlAbstractDateTimeLiteral;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.SqliteSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What Calcite 1.42 does when it turns a query into SQLite SQL. Each test pins one
 * behaviour the technical design relies on or works around (see docs/spike-results.md).
 */
class CalciteSqlGenerationLearningTest {

    static final SqlDialect STOCK = SqliteSqlDialect.DEFAULT;

    static final String SALES_BY_COUNTRY = "SELECT c.\"country\", COUNT(o.\"order_id\") AS \"orders\" "
            + "FROM \"Customers\" AS c INNER JOIN \"Orders\" AS o ON c.\"customer_id\" = o.\"customer_id\" "
            + "GROUP BY c.\"country\" ORDER BY COUNT(o.\"order_id\") DESC";

    @Test
    void plannerSqlToRelWritesInAsOrAndBetweenAsAnd_withoutSearch() throws Exception {
        assertThat(filter("\"city\" IN ('Seattle', 'London', 'Paris')"))
                .contains("OR(=($3, 'Seattle'), =($3, 'London'), =($3, 'Paris'))").doesNotContain("SEARCH");
        assertThat(filter("\"customer_id\" BETWEEN 2 AND 5")).contains("AND(>=($0, 2), <=($0, 5))").doesNotContain("SEARCH");
        assertThat(filter("\"customer_id\" NOT IN (1, 2)")).contains("NOT(OR(=($0, 1), =($0, 2)))");
    }

    @Test
    void longInListsBecomeAJoinAgainstValues_unlessTheThresholdIsRaised() throws Exception {
        String longList = "\"customer_id\" IN (" + String.join(", ",
                java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(String::valueOf).toList()) + ")";
        String sql = "SELECT \"name\" FROM \"Customers\" WHERE " + longList;
        assertThat(RelOptUtilExplain.explain(Fixture.rel(sql))).contains("LogicalValues");

        org.apache.calcite.tools.Planner p = org.apache.calcite.tools.Frameworks.getPlanner(
                org.apache.calcite.tools.Frameworks.newConfigBuilder()
                        .parserConfig(Fixture.canonicalParser())
                        .defaultSchema(Fixture.schema())
                        .sqlToRelConverterConfig(org.apache.calcite.sql2rel.SqlToRelConverter.config()
                                .withInSubQueryThreshold(Integer.MAX_VALUE))
                        .build());
        RelNode rel = p.rel(p.validate(p.parse(sql))).project();
        assertThat(RelOptUtilExplain.explain(rel)).doesNotContain("LogicalValues").contains("OR(");
    }

    @Test
    void expandSearchUnfoldsASearchThatRelBuilderSimplificationCreates() throws Exception {
        RelBuilder b = RelBuilder.create(org.apache.calcite.tools.Frameworks.newConfigBuilder()
                .defaultSchema(Fixture.schema()).build());
        b.scan("Customers");
        b.filter(b.between(b.field("customer_id"), b.literal(2), b.literal(5)));
        RelNode rel = b.build();
        assertThat(RelOptUtilExplain.explain(rel)).contains("SEARCH");

        RexBuilder rex = rel.getCluster().getRexBuilder();
        RelNode expanded = rel.accept(new RexShuttleOnAll(new RexShuttle() {
            @Override
            public RexNode visitCall(RexCall call) {
                return call.getKind() == SqlKind.SEARCH ? RexUtil.expandSearch(rex, null, call) : super.visitCall(call);
            }
        }));
        assertThat(RelOptUtilExplain.explain(expanded)).doesNotContain("SEARCH").contains("AND(");
    }

    static String filter(String where) throws Exception {
        String plan = RelOptUtilExplain.explain(Fixture.rel("SELECT \"name\" FROM \"Customers\" WHERE " + where));
        return plan.lines().filter(l -> l.contains("Filter")).findFirst().orElse(plan);
    }

    @Test
    void dynamicParamsAreWrittenAsQuestionMarksInTextualOrder() throws Exception {
        RelBuilder b = RelBuilder.create(org.apache.calcite.tools.Frameworks.newConfigBuilder()
                .defaultSchema(Fixture.schema()).build());
        RexBuilder rex = b.getRexBuilder();
        b.scan("Orders");
        RexNode second = rex.makeDynamicParam(b.getTypeFactory().createSqlType(SqlTypeName.BIGINT), 1);
        RexNode first = rex.makeDynamicParam(b.getTypeFactory().createSqlType(SqlTypeName.VARCHAR), 0);
        b.filter(b.and(b.equals(b.field("status"), first), b.greaterThan(b.field("total"), second)));
        SqlString sql = Fixture.toSql(b.build(), STOCK);
        assertThat(Fixture.text(sql)).contains("\"status\" = ? AND \"total\" > ?");
        assertThat(sql.getDynamicParameters()).containsExactly(0, 1);
    }

    @Test
    void stockSqliteDialectSortsByOrdinal_andConformanceOverrideSortsByExpression() throws Exception {
        RelNode rel = Fixture.rel(SALES_BY_COUNTRY);
        assertThat(Fixture.text(Fixture.toSql(rel, STOCK))).contains("ORDER BY 2 DESC");

        SqlDialect byExpression = new SqliteSqlDialect(SqliteSqlDialect.DEFAULT_CONTEXT) {
            final SqlConformance c = new SqlDelegatingConformance(super.getConformance()) {
                @Override public boolean isSortByOrdinal() { return false; }
            };
            @Override public SqlConformance getConformance() { return c; }
        };
        assertThat(Fixture.text(Fixture.toSql(rel, byExpression))).contains("ORDER BY COUNT(\"Orders\".\"order_id\") DESC");
    }

    @Test
    void stockSqliteDialectWritesAnsiDateLiterals_whichSqliteRejects_andTheUnparseHookFixesIt() throws Exception {
        RelNode rel = Fixture.rel("SELECT \"order_id\" FROM \"Orders\" WHERE \"order_date\" >= DATE '2026-11-01'");
        String stock = Fixture.text(Fixture.toSql(rel, STOCK));
        assertThat(stock).contains("DATE '2026-11-01'");
        assertThatThrownBy(() -> Fixture.run(stock)).hasMessageContaining("syntax error");

        SqlDialect isoText = new SqliteSqlDialect(SqliteSqlDialect.DEFAULT_CONTEXT) {
            @Override
            public void unparseDateTimeLiteral(SqlWriter writer, SqlAbstractDateTimeLiteral literal, int l, int r) {
                writer.literal("'" + literal.toFormattedString() + "'");
            }
        };
        String fixed = Fixture.text(Fixture.toSql(rel, isoText));
        assertThat(fixed).contains(">= '2026-11-01'");
        assertThat(Fixture.run(fixed)).isNotEmpty();
    }

    @Test
    void stockSqliteDialectRewritesRightJoinAsLeftJoin() throws Exception {
        RelNode rel = Fixture.rel("SELECT c.\"name\", o.\"order_id\" FROM \"Orders\" AS o "
                + "RIGHT JOIN \"Customers\" AS c ON c.\"customer_id\" = o.\"customer_id\"");
        String sql = Fixture.text(Fixture.toSql(rel, STOCK));
        assertThat(sql).doesNotContain("RIGHT JOIN").contains("LEFT JOIN");
        assertThat(Fixture.run(sql)).hasSize(22);
    }

    @Test
    void plannerTakesNullOrderingFromTheConnectionConfig_notTheValidatorConfig() throws Exception {
        String sql = "SELECT \"order_id\" FROM \"Orders\" ORDER BY \"total\" DESC";
        assertThat(Fixture.text(Fixture.toSql(Fixture.rel(sql), STOCK))).contains("DESC NULLS FIRST");

        Properties p = new Properties();
        p.setProperty("defaultNullCollation", STOCK.getNullCollation().name());
        RelNode rel = Fixture.rel(sql, Contexts.of(new CalciteConnectionConfigImpl(p)));
        assertThat(Fixture.text(Fixture.toSql(rel, STOCK))).endsWith("ORDER BY \"total\" DESC");
    }

    @Test
    void calciteWritesThePlatformLineSeparator() throws Exception {
        String raw = Fixture.toSql(Fixture.rel(SALES_BY_COUNTRY), STOCK).getSql();
        assertThat(raw).contains(System.lineSeparator());
    }

    @Test
    void calciteCannotParseFetchWithTies() {
        assertThatThrownBy(() -> Fixture.rel(SALES_BY_COUNTRY + " FETCH FIRST 3 ROWS WITH TIES"))
                .isInstanceOf(SqlParseException.class);
    }

    @Test
    void windowFunctionsBuiltWithRelBuilderAreWrittenAndRunOnSqlite() throws Exception {
        RelNode base = Fixture.rel(SALES_BY_COUNTRY);
        RelBuilder b = RelBuilder.proto(Contexts.empty()).create(base.getCluster(), null);
        b.push(((org.apache.calcite.rel.core.Sort) base).getInput());
        RexNode rank = b.aggregateCall(SqlStdOperatorTable.RANK).over().orderBy(List.of(b.desc(b.field(1)))).toRex();
        b.projectPlus(b.alias(rank, "rnk"));
        b.filter(b.lessThanOrEqual(b.field("rnk"), b.literal(3)));
        String sql = Fixture.text(Fixture.toSql(b.build(), STOCK));
        assertThat(sql).contains("RANK() OVER (ORDER BY");
        // USA 10, UK 4, then three countries tied on 2: RANK keeps the ties.
        assertThat(Fixture.run(sql)).hasSize(5);
    }

    @Test
    void stockSqliteDialectDeclaresOnlyCountSumMinMaxSupported_evenAvg() {
        for (SqlKind kind : List.of(SqlKind.AVG, SqlKind.STDDEV_SAMP, SqlKind.VAR_SAMP, SqlKind.STDDEV_POP, SqlKind.VAR_POP)) {
            assertThat(STOCK.supportsAggregateFunction(kind)).as(kind.name()).isFalse();
        }
        for (SqlKind kind : List.of(SqlKind.COUNT, SqlKind.SUM, SqlKind.MIN, SqlKind.MAX)) {
            assertThat(STOCK.supportsAggregateFunction(kind)).as(kind.name()).isTrue();
        }
    }

    @Test
    void defaultTypeSystemKeepsTheIntegerTypeForAvgAndStddev() throws Exception {
        RelNode rel = Fixture.rel("SELECT AVG(\"total\"), STDDEV_SAMP(\"total\") FROM \"Orders\"");
        assertThat(rel.getRowType().getFieldList()).allSatisfy(f ->
                assertThat(SqlTypeName.INT_TYPES).contains(f.getType().getSqlTypeName()));
    }

    /** Small helpers kept local to the learning tests. */
    static final class RelOptUtilExplain {
        static String explain(RelNode rel) {
            return org.apache.calcite.plan.RelOptUtil.toString(rel);
        }
    }

    static final class RexShuttleOnAll extends org.apache.calcite.rel.RelHomogeneousShuttle {
        private final RexShuttle shuttle;

        RexShuttleOnAll(RexShuttle shuttle) {
            this.shuttle = shuttle;
        }

        @Override
        public RelNode visit(RelNode other) {
            return super.visit(other).accept(shuttle);
        }
    }

    /** Why Query Studio's join type check compares type families rather than using isComparable. */
    @Test
    void isComparableAcceptsTextAgainstNumbersAndDates_becauseItAllowsImplicitCasts() {
        SqlTypeFactoryImpl types = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
        RelDataType text = types.createSqlType(SqlTypeName.VARCHAR);
        RelDataType number = types.createSqlType(SqlTypeName.BIGINT);
        RelDataType date = types.createSqlType(SqlTypeName.DATE);
        assertThat(SqlTypeUtil.isComparable(text, number)).isTrue();
        assertThat(SqlTypeUtil.isComparable(text, date)).isTrue();
        assertThat(text.getFamily()).isNotEqualTo(number.getFamily()).isNotEqualTo(date.getFamily());
        assertThat(number.getFamily()).isEqualTo(types.createSqlType(SqlTypeName.INTEGER).getFamily());
    }
}
