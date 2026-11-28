package com.unify.querystudio.design;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.compiler.BoundValue;
import com.unify.querystudio.compiler.CompiledQuery;
import com.unify.querystudio.compiler.QueryCompiler;
import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.testkit.NorthstarDatabase;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The design view (canvas and grid) becomes canonical Calcite SQL following Access's rules, and that
 * SQL compiles and returns the expected rows from SQLite.
 */
class DesignToSqlTest {

    @TempDir
    static Path dir;
    static Path db;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static QueryCompiler compiler;

    @BeforeAll
    static void setUp() throws Exception {
        db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            compiler = new QueryCompiler(CatalogReader.read(c, SQLITE), SQLITE);
        }
    }

    static List<List<Object>> run(String canonicalSql) throws Exception {
        CompiledQuery q = compiler.compile(canonicalSql);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(q.targetSql())) {
            for (int i = 0; i < q.parameters().size(); i++) {
                BoundValue p = q.parameters().get(i);
                SQLITE.binder().bind(ps, i + 1, p.value(), p.type());
            }
            List<List<Object>> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    static final Source CUSTOMERS = Source.of("Customers");
    static final Source ORDERS = Source.of("Orders");
    static final JoinLine CUSTOMER_ORDERS = new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.INNER);

    static GridColumn field(String table, String field) {
        return GridColumn.field(table, field);
    }

    static String oneLine(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    // ---------------- the prototype's starter queries ----------------

    @Test
    void completedOrders() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                field("Customers", "name").alias("customer"),
                field("Customers", "city"),
                field("Orders", "order_date").sort(Direction.DESCENDING),
                field("Orders", "total").criteria(">= 100"),
                field("Orders", "status").criteria("'Completed'")));
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).isEqualTo(oneLine("""
                SELECT "Customers"."name" AS "customer", "Customers"."city", "Orders"."order_date", "Orders"."total", "Orders"."status"
                FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."total" >= 100 AND "Orders"."status" = 'Completed'
                ORDER BY "Orders"."order_date" DESC"""));
        assertThat(run(sql)).hasSize(10);
    }

    @Test
    void salesByCountry() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country"),
                        field("Orders", "order_id").alias("orders").total(Total.aggregate(SqlStdOperatorTable.COUNT)),
                        field("Orders", "total").alias("revenue").total(Total.aggregate(SqlStdOperatorTable.SUM))
                                .sort(Direction.DESCENDING)))
                .withTotals(true);
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).isEqualTo(oneLine("""
                SELECT "Customers"."country", COUNT("Orders"."order_id") AS "orders", SUM("Orders"."total") AS "revenue"
                FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                GROUP BY "Customers"."country"
                ORDER BY SUM("Orders"."total") DESC"""));
        assertThat(run(sql)).extracting(r -> r.getFirst()).containsExactly("USA", "UK", "South Korea", "France", "Italy");
    }

    @Test
    void customersWithoutOrders() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS),
                List.of(new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.LEFT)), List.of(
                        field("Customers", "name"),
                        field("Customers", "country"),
                        field("Orders", "order_id").show(false).criteria("IS NULL")));
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).isEqualTo(oneLine("""
                SELECT "Customers"."name", "Customers"."country"
                FROM "Customers" LEFT JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."order_id" IS NULL"""));
        assertThat(run(sql)).containsExactlyInAnyOrder(List.of("Emma Wilson", "Canada"), List.of("Leo Nakamura", "Japan"));
    }

    // ---------------- the designer's rules (from the Access query designer) ----------------

    @Test
    void criteriaRowsAreAndedAcrossAndOredBetween() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS), List.of(), List.of(
                field("Customers", "name"),
                field("Customers", "country").criteria("'USA'", "'UK'"),
                field("Customers", "city").criteria("'Seattle'")));
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).endsWith(
                "WHERE \"Customers\".\"country\" = 'USA' AND \"Customers\".\"city\" = 'Seattle' OR \"Customers\".\"country\" = 'UK'");
        assertThat(run(sql)).hasSize(1 + 3);
    }

    @Test
    void criteriaUnderAGroupByColumnGoToHaving_asInAccess() {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country").criteria("<> 'Italy'"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)).criteria("> 400")))
                .withTotals(true);
        assertThat(oneLine(DesignToSql.toSql(design))).endsWith(
                "GROUP BY \"Customers\".\"country\" HAVING \"Customers\".\"country\" <> 'Italy' AND SUM(\"Orders\".\"total\") > 400");
    }

    @Test
    void aWhereTotalFiltersRowsBeforeGrouping() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)),
                        field("Orders", "status").total(Total.WHERE).show(false).criteria("'Completed'")))
                .withTotals(true);
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).contains("WHERE \"Orders\".\"status\" = 'Completed' GROUP BY \"Customers\".\"country\"");
        assertThat(oneLine(sql)).doesNotContain("\"status\" ,").doesNotContain("GROUP BY \"Customers\".\"country\", \"Orders\".\"status\"");
        assertThat(run(sql)).isNotEmpty();
    }

    @Test
    void aWhereColumnCannotBeShown_asInAccess() {
        DesignView design = DesignView.of(List.of(ORDERS), List.of(), List.of(
                        field("Orders", "status").total(Total.WHERE).criteria("'Completed'")))
                .withTotals(true);
        assertThatThrownBy(() -> DesignToSql.toSql(design))
                .isInstanceOf(DesignException.class)
                .hasMessage("Query Studio can't display the field for which you entered Where in the Total row.");
    }

    @Test
    void defaultOutputNamesFollowAccess() {
        DesignView design = DesignView.of(List.of(ORDERS), List.of(), List.of(
                        field("Orders", "status"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)),
                        field("Orders", "order_id").total(Total.aggregate(SqlStdOperatorTable.COUNT))))
                .withTotals(true);
        assertThat(oneLine(DesignToSql.toSql(design))).startsWith(
                "SELECT \"Orders\".\"status\", SUM(\"Orders\".\"total\") AS \"SumOftotal\", COUNT(\"Orders\".\"order_id\") AS \"CountOforder_id\"");
    }

    @Test
    void calculatedFieldsAreStandardSqlNamedByTheirAliasOrExprN() throws Exception {
        DesignView design = DesignView.of(List.of(ORDERS), List.of(), List.of(
                GridColumn.expression("\"Orders\".\"total\" * 2").alias("Doubled"),
                GridColumn.expression("\"Orders\".\"total\" + 1"),
                GridColumn.expression("\"Orders\".\"total\" + 2")));
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).startsWith(
                "SELECT \"Orders\".\"total\" * 2 AS \"Doubled\", \"Orders\".\"total\" + 1 AS \"Expr1\", \"Orders\".\"total\" + 2 AS \"Expr2\"");
        assertThat(compiler.compile(sql).columns()).extracting(c -> c.name()).containsExactly("Doubled", "Expr1", "Expr2");
    }

    @Test
    void uniqueValuesIsSelectDistinct() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS), List.of(), List.of(field("Customers", "country")))
                .withUniqueValues(true);
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).startsWith("SELECT DISTINCT \"Customers\".\"country\"");
        assertThat(run(sql)).hasSize(7);
    }

    @Test
    void theAsteriskSelectsEveryFieldAndRefusesSortCriteriaAndTotals() throws Exception {
        DesignView all = DesignView.of(List.of(ORDERS), List.of(), List.of(GridColumn.allFields("Orders")));
        assertThat(oneLine(DesignToSql.toSql(all))).isEqualTo("SELECT \"Orders\".* FROM \"Orders\"");
        assertThat(run(DesignToSql.toSql(all)).getFirst()).hasSize(5);

        DesignView sorted = DesignView.of(List.of(ORDERS), List.of(), List.of(GridColumn.allFields("Orders").sort(Direction.ASCENDING)));
        assertThatThrownBy(() -> DesignToSql.toSql(sorted)).isInstanceOf(DesignException.class).hasMessageContaining("asterisk");
    }

    @Test
    void aBadCriteriaCellIsReportedAgainstItsCell() {
        DesignView design = DesignView.of(List.of(ORDERS), List.of(), List.of(
                field("Orders", "order_id"),
                field("Orders", "status").criteria("'Completed'", "'unclosed")));
        assertThatThrownBy(() -> DesignToSql.toSql(design))
                .isInstanceOfSatisfying(DesignException.class, e -> {
                    assertThat(e.column()).isEqualTo(1);
                    assertThat(e.criteriaRow()).isEqualTo(1);
                    assertThat(e).hasMessageContaining("quote");
                });
    }

    // ---------------- Top Values ----------------

    @Test
    void topValuesBecomeAFetchOfExactlyNRows() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country"),
                        field("Orders", "order_id").alias("orders").total(Total.aggregate(SqlStdOperatorTable.COUNT))
                                .sort(Direction.DESCENDING)))
                .withTotals(true).withTopValues(3);
        String sql = DesignToSql.toSql(design);
        assertThat(sql).endsWith("ORDER BY COUNT(\"Orders\".\"order_id\") DESC\nFETCH NEXT 3 ROWS ONLY");
        assertThat(run(sql)).hasSize(3);
    }

    @Test
    void topValuesAreAPositiveWholeNumber() {
        DesignView design = DesignView.of(List.of(CUSTOMERS), List.of(), List.of(field("Customers", "name")));
        assertThatThrownBy(() -> design.withTopValues(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(design.withTopValues(5).withTopValues(null).topValues()).isNull();
    }

    // ---------------- criteria rows mixing Where and grouped columns ----------------

    /**
     * As Access does (Microsoft Q&A 5230684): Where cells go to WHERE and grouped cells to HAVING,
     * each OR-ed across the rows on its own, so the rows are not paired. Access gives no warning.
     */
    @Test
    void mixedCriteriaRowsSplitIntoWhereAndHavingIndependently_asAccessDoes() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country").criteria("'USA'", "'UK'"),
                        field("Orders", "total").alias("revenue").total(Total.aggregate(SqlStdOperatorTable.SUM)),
                        field("Orders", "status").total(Total.WHERE).show(false).criteria("'Completed'", "'Shipped'")))
                .withTotals(true);
        String sql = oneLine(DesignToSql.toSql(design));
        assertThat(sql).contains("WHERE \"Orders\".\"status\" = 'Completed' OR \"Orders\".\"status\" = 'Shipped' GROUP BY")
                .endsWith("HAVING \"Customers\".\"country\" = 'USA' OR \"Customers\".\"country\" = 'UK'");
        assertThat(run(sql)).containsExactlyInAnyOrderElementsOf(run("""
                SELECT "Customers"."country", SUM("Orders"."total") AS "revenue"
                FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."status" IN ('Completed', 'Shipped') AND "Customers"."country" IN ('USA', 'UK')
                GROUP BY "Customers"."country\""""));
    }

    @Test
    void aRowWithOnlyGroupedCriteriaAddsNothingToWhere() {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS), List.of(CUSTOMER_ORDERS), List.of(
                        field("Customers", "country").criteria("", "'UK'"),
                        field("Orders", "total").total(Total.aggregate(SqlStdOperatorTable.SUM)),
                        field("Orders", "status").total(Total.WHERE).show(false).criteria("'Completed'")))
                .withTotals(true);
        assertThat(oneLine(DesignToSql.toSql(design)))
                .contains("WHERE \"Orders\".\"status\" = 'Completed' GROUP BY")
                .endsWith("HAVING \"Customers\".\"country\" = 'UK'");
    }

    @Test
    void outputAllFieldsSelectsEveryFieldOfEverySource_thenTheShownGridFields() throws Exception {
        DesignView design = DesignView.of(List.of(CUSTOMERS, ORDERS),
                List.of(new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.INNER)),
                List.of(field("Orders", "total").show(false).criteria(">= 100").sort(Direction.DESCENDING),
                        GridColumn.expression("\"Orders\".\"total\" * 2").alias("doubled")))
                .withOutputAllFields(true);
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).startsWith("SELECT *, \"Orders\".\"total\" * 2 AS \"doubled\" FROM");
        assertThat(run(sql).getFirst()).hasSize(5 + 5 + 1);
    }

    @Test
    void outputAllFieldsCannotBeUsedInATotalsQuery() {
        DesignView design = DesignView.of(List.of(ORDERS), List.of(), List.of(field("Orders", "status")))
                .withTotals(true).withOutputAllFields(true);
        assertThatThrownBy(() -> DesignToSql.toSql(design)).isInstanceOf(DesignException.class)
                .hasMessageContaining("Output All Fields");
    }

    @Test
    void criteriaCellsTakeNamedParameters_whichTheCompilerTypesAndKeeps() {
        DesignView design = DesignView.of(List.of(ORDERS), List.of(), List.of(
                field("Orders", "order_id"),
                field("Orders", "total").criteria(">= :MinTotal"),
                field("Orders", "status").criteria(":Status"),
                field("Orders", "order_date").show(false).criteria("BETWEEN :From AND :To")));
        String sql = DesignToSql.toSql(design);
        assertThat(oneLine(sql)).contains("\"Orders\".\"total\" >= :MinTotal AND \"Orders\".\"status\" = :Status")
                .contains("BETWEEN ASYMMETRIC :From AND :To");
        assertThat(compiler.compile(sql).queryParameters()).extracting(p -> p.name())
                .containsExactly("MinTotal", "Status", "From", "To");
    }

    @Test
    void aBareQuestionMarkInACellIsReportedOnTheCell() {
        DesignView design = DesignView.of(List.of(ORDERS), List.of(), List.of(field("Orders", "total").criteria(">= ?")));
        assertThatThrownBy(() -> DesignToSql.toSql(design)).isInstanceOfSatisfying(DesignException.class, e -> {
            assertThat(e.column()).isEqualTo(0);
            assertThat(e.criteriaRow()).isEqualTo(0);
            assertThat(e.getMessage()).contains(":name");
        });
    }
}
