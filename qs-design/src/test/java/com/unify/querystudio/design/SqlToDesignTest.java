package com.unify.querystudio.design;

import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edited SQL becomes a design view again when the designer can show it (as Access rebuilds Design
 * view from SQL), and stays SQL-only when it cannot.
 */
class SqlToDesignTest {

    static final Source CUSTOMERS = Source.of("Customers");
    static final Source ORDERS = Source.of("Orders");
    static final Source ITEMS = Source.of("OrderItems");
    static final JoinLine CUSTOMER_ORDERS = new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.INNER);

    static GridColumn field(String table, String field) {
        return GridColumn.field(table, field);
    }

    static DesignView design(String sql) {
        SqlToDesign.Result result = SqlToDesign.toDesign(sql);
        assertThat(result).as("expected a representable query: %s", result).isInstanceOf(SqlToDesign.Representable.class);
        return ((SqlToDesign.Representable) result).design();
    }

    static Stream<DesignView> designs() {
        return Stream.of(
                DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "name").alias("customer"),
                        field("Customers", "city"),
                        field("Orders", "order_date").sort(Direction.DESCENDING),
                        field("Orders", "total").criteria(">= 100"),
                        field("Orders", "status").criteria("'Completed'"))),
                DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country"),
                        field("Orders", "order_id").alias("orders").total(Total.aggregate(SqlStdOperatorTable.COUNT)),
                        field("Orders", "total").alias("revenue").total(Total.aggregate(SqlStdOperatorTable.SUM))
                                .sort(Direction.DESCENDING))).withTotals(true),
                DesignView.of(List.of(CUSTOMERS, ORDERS),
                        List.of(new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.LEFT)), List.of(
                                field("Customers", "name"),
                                field("Customers", "country"),
                                field("Orders", "order_id").show(false).criteria("IS NULL"))),
                DesignView.of(List.of(CUSTOMERS), List.of(), List.of(
                        field("Customers", "name"),
                        field("Customers", "country").criteria("'USA'", "'UK'"),
                        field("Customers", "city").criteria("'Seattle'"))),
                DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country").criteria("<> 'Italy'"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)).criteria("> 400")))
                        .withTotals(true),
                DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)),
                        field("Orders", "status").total(Total.WHERE).show(false).criteria("'Completed'")))
                        .withTotals(true),
                DesignView.of(List.of(ORDERS), List.of(), List.of(
                        GridColumn.expression("\"Orders\".\"total\" * 2").alias("Doubled"),
                        GridColumn.expression("\"Orders\".\"total\" + 1"))),
                DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        GridColumn.expression("UPPER(\"Customers\".\"name\") || ' (' || CHAR_LENGTH(\"Customers\".\"city\") || ')'")
                                .alias("Label"),
                        GridColumn.expression("\"Orders\".\"order_date\" > DATE '2026-11-01'").alias("Late"))),
                DesignView.of(List.of(CUSTOMERS), List.of(), List.of(field("Customers", "country")))
                        .withUniqueValues(true),
                DesignView.of(List.of(ORDERS), List.of(), List.of(GridColumn.allFields("Orders"))),
                DesignView.of(List.of(CUSTOMERS, ORDERS, ITEMS), List.of(
                        new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.LEFT),
                        new JoinLine("Orders", "order_id", "OrderItems", "order_id", JoinType.LEFT)), List.of(
                        field("Customers", "name"),
                        field("OrderItems", "quantity").criteria("BETWEEN 1 AND 5", "IS NULL"),
                        field("Orders", "status").criteria("IN ('Completed', 'Shipped')"),
                        field("Customers", "name").show(false).criteria("LIKE 'A%'"))),
                DesignView.of(List.of(ORDERS), List.of(), List.of(
                        field("Orders", "order_id"),
                        field("Orders", "total").sort(Direction.DESCENDING))).withTopValues(5),
                DesignView.of(List.of(ORDERS), List.of(), List.of(field("Orders", "order_id"))).withTopValues(2),
                DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country").criteria("'USA'", "'UK'"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)).criteria("> 100"),
                        field("Orders", "status").total(Total.WHERE).show(false).criteria("'Completed'", "'Shipped'")))
                        .withTotals(true),
                DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country").criteria("", "'UK'"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)),
                        field("Orders", "status").total(Total.WHERE).show(false).criteria("'Completed'")))
                        .withTotals(true),
                DesignView.of(List.of(ORDERS), List.of(), List.of(
                        field("Orders", "order_id"),
                        field("Orders", "order_date").criteria(">= DATE '2026-11-01'"),
                        field("Orders", "total").criteria("> 100 AND < 300"))));
    }

    @ParameterizedTest
    @MethodSource("designs")
    void designSqlRoundTripsThroughTheDesignView(DesignView original) {
        String sql = DesignToSql.toSql(original);
        assertThat(DesignToSql.toSql(design(sql))).isEqualTo(sql);
    }

    @Test
    void handWrittenSqlGetsHiddenColumnsForItsCriteriaAndSorts_asAccessShowsThem() {
        DesignView d = design("""
                SELECT "Customers"."name" FROM "Customers"
                WHERE "Customers"."country" = 'UK' ORDER BY "Customers"."city\"""");
        assertThat(d.sources()).containsExactly(CUSTOMERS);
        assertThat(d.columns()).containsExactly(
                field("Customers", "name"),
                field("Customers", "country").show(false).criteria("'UK'"),
                field("Customers", "city").show(false).sort(Direction.ASCENDING));
    }

    @Test
    void criteriaBecomeCellTextWithTheirColumnLeftOut() {
        DesignView d = design("""
                SELECT "Orders"."order_id" FROM "Orders"
                WHERE "Orders"."total" > 100 AND "Orders"."total" < 300 AND "Orders"."status" IN ('A', 'B')
                AND "Orders"."order_date" >= DATE '2026-11-01' AND "Orders"."status" IS NOT NULL""");
        assertThat(d.columns()).extracting(GridColumn::field, c -> c.criteria())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("order_id", List.of()),
                        org.assertj.core.groups.Tuple.tuple("total", List.of("> 100 AND < 300")),
                        org.assertj.core.groups.Tuple.tuple("status", List.of("IN ('A', 'B')")),
                        org.assertj.core.groups.Tuple.tuple("order_date", List.of(">= DATE '2026-11-01'")),
                        org.assertj.core.groups.Tuple.tuple("status", List.of("IS NOT NULL")));
    }

    @Test
    void predicatesKeepTheirOrder_soTheDesignWritesBackTheSameWhereClause() {
        String sql = """
                SELECT "Orders"."order_id"
                FROM "Orders"
                WHERE "Orders"."status" IN ('A', 'B') AND "Orders"."order_date" >= DATE '2026-11-01' AND "Orders"."status" IS NOT NULL""";
        assertThat(DesignToSql.toSql(design(sql))).isEqualTo(sql);
    }

    @Test
    void anUnqualifiedFieldBelongsToTheOnlySource() {
        DesignView d = design("SELECT \"name\" FROM \"Customers\"");
        assertThat(d.columns()).containsExactly(field("Customers", "name"));
    }

    @Test
    void aSecondCopyOfATableKeepsItsAlias() {
        DesignView d = design("""
                SELECT "Customers"."name", "Customers_1"."name" AS "other"
                FROM "Customers" INNER JOIN "Customers" AS "Customers_1" ON "Customers"."city" = "Customers_1"."city\"""");
        assertThat(d.sources()).containsExactly(CUSTOMERS, new Source("Customers_1", List.of("Customers")));
    }

    @Test
    void whereAndHavingRowsAreLaidOutIndependently_asAccessDoes() {
        DesignView d = design("""
                SELECT "Customers"."country", SUM("Orders"."total")
                FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."status" = 'Completed' OR "Orders"."status" = 'Shipped'
                GROUP BY "Customers"."country"
                HAVING "Customers"."country" = 'UK'""");
        assertThat(d.columns()).containsExactly(
                field("Customers", "country").criteria("'UK'"),
                field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)),
                field("Orders", "status").total(Total.WHERE).show(false).criteria("'Completed'", "'Shipped'"));
    }

    // ---------------- SQL-only, as in Access ----------------

    static String sqlOnly(String sql) {
        SqlToDesign.Result result = SqlToDesign.toDesign(sql);
        assertThat(result).isInstanceOf(SqlToDesign.SqlOnly.class);
        return ((SqlToDesign.SqlOnly) result).reason();
    }

    @Test
    void unionsStaySqlOnly() {
        assertThat(sqlOnly("SELECT \"name\" FROM \"Customers\" UNION SELECT \"name\" FROM \"Products\""))
                .contains("UNION");
    }

    @Test
    void unequalJoinsStaySqlOnlyWithAccessWording() {
        assertThat(sqlOnly("""
                SELECT "Customers"."name" FROM "Customers"
                INNER JOIN "Orders" ON "Customers"."customer_id" < "Orders"."customer_id\""""))
                .startsWith("Query Studio can't represent the join expression")
                .endsWith("in Design view.");
    }

    @Test
    void subqueriesInFromAndWindowFunctionsStaySqlOnly() {
        sqlOnly("SELECT \"t\".\"name\" FROM (SELECT \"name\" FROM \"Customers\") AS \"t\"");
        sqlOnly("SELECT \"name\", RANK() OVER (ORDER BY \"name\") FROM \"Customers\"");
    }

    @Test
    void fetchBecomesTopValues_butAnOffsetStaysSqlOnly() {
        assertThat(design("SELECT \"name\" FROM \"Customers\" ORDER BY \"name\" FETCH NEXT 4 ROWS ONLY").topValues())
                .isEqualTo(4);
        assertThat(sqlOnly("SELECT \"name\" FROM \"Customers\" ORDER BY \"name\" OFFSET 2 ROWS FETCH NEXT 4 ROWS ONLY"))
                .contains("OFFSET");
    }

    @Test
    void sqlThatDoesNotParseIsReportedNotGuessed() {
        assertThat(SqlToDesign.toDesign("SELECT FROM")).isInstanceOf(SqlToDesign.SqlOnly.class);
    }

    @Test
    void aBareAsteriskIsOutputAllFields_withTheOtherFieldsInTheGrid() {
        String sql = """
                SELECT *, "Orders"."total" * 2 AS "doubled"
                FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."total" >= 100""";
        DesignView d = design(sql);
        assertThat(d.outputAllFields()).isTrue();
        assertThat(d.columns()).containsExactly(
                GridColumn.expression("\"Orders\".\"total\" * 2").alias("doubled"),
                field("Orders", "total").show(false).criteria(">= 100"));
        String written = DesignToSql.toSql(d);
        assertThat(DesignToSql.toSql(design(written))).isEqualTo(written);
    }

    @Test
    void namedParametersComeBackIntoTheirCells() {
        String sql = """
                SELECT "Orders"."order_id"
                FROM "Orders"
                WHERE "Orders"."total" >= :MinTotal AND "Orders"."status" = :Status""";
        DesignView d = design(sql);
        assertThat(d.columns()).containsExactly(
                field("Orders", "order_id"),
                field("Orders", "total").show(false).criteria(">= :MinTotal"),
                field("Orders", "status").show(false).criteria(":Status"));
        String written = DesignToSql.toSql(d);
        assertThat(DesignToSql.toSql(design(written))).isEqualTo(written);
        assertThat(written).contains(":MinTotal").contains(":Status");
    }
}
