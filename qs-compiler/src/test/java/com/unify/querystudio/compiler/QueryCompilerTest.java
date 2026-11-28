package com.unify.querystudio.compiler;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.testkit.NorthstarDatabase;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;
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

class QueryCompilerTest {

    @TempDir
    static Path dir;
    static Path db;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static QueryCompiler compiler;

    @BeforeAll
    static void setUp() throws Exception {
        db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        SchemaPlus schema;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            schema = CatalogReader.read(c, SQLITE);
        }
        compiler = new QueryCompiler(schema, SQLITE);
    }

    /** Runs compiled SQL with its parameters, bound by the dialect's binder. */
    static List<List<Object>> run(CompiledQuery q) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(q.targetSql())) {
            for (int i = 0; i < q.parameters().size(); i++) {
                BoundValue p = q.parameters().get(i);
                SQLITE.binder().bind(ps, i + 1, p.value(), p.type());
            }
            return rows(ps.executeQuery());
        }
    }

    static List<List<Object>> runSql(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            return rows(c.createStatement().executeQuery(sql));
        }
    }

    static List<List<Object>> rows(ResultSet rs) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
            rows.add(row);
        }
        return rows;
    }

    static final String COMPLETED_ORDERS = """
            SELECT "Customers"."name" AS "customer", "Customers"."city", "Orders"."order_date", "Orders"."total", "Orders"."status"
            FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
            WHERE "Orders"."total" >= 100 AND "Orders"."status" = 'Completed'
            ORDER BY "Orders"."order_date" DESC""";

    @Test
    void compilesTheCompletedOrdersStarterQueryToParameterisedSqlite() {
        CompiledQuery q = compiler.compile(COMPLETED_ORDERS);
        assertThat(q.targetSql()).isEqualTo("""
                SELECT "Customers"."name" AS "customer", "Customers"."city", "Orders"."order_date", "Orders"."total", "Orders"."status"
                FROM "Customers"
                INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."total" >= ? AND "Orders"."status" = ?
                ORDER BY "Orders"."order_date" DESC""");
        assertThat(q.parameters()).extracting(BoundValue::value).extracting(Object::toString)
                .containsExactly("100", "Completed");
    }

    @Test
    void returnsTheSameRowsAsTheHandWrittenQuery() throws Exception {
        assertThat(run(compiler.compile(COMPLETED_ORDERS))).isEqualTo(runSql("""
                SELECT c.name, c.city, o.order_date, o.total, o.status FROM Customers c JOIN Orders o ON o.customer_id = c.customer_id
                WHERE o.total >= 100 AND o.status = 'Completed' ORDER BY o.order_date DESC"""));
    }

    /** The designer's output: one complete SQL string for the target database, values written in. */
    @Test
    void theCompleteSqlWritesItsValuesInline() {
        assertThat(compiler.compile(COMPLETED_ORDERS).sql()).isEqualTo("""
                SELECT "Customers"."name" AS "customer", "Customers"."city", "Orders"."order_date", "Orders"."total", "Orders"."status"
                FROM "Customers"
                INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."total" >= 100 AND "Orders"."status" = 'Completed'
                ORDER BY "Orders"."order_date" DESC""");
    }

    @Test
    void theCompleteSqlRunsAsItIs_withTheSameRowsAsTheParameterisedSql() throws Exception {
        for (String sql : List.of(COMPLETED_ORDERS, """
                SELECT "Orders"."order_id" FROM "Orders"
                WHERE "Orders"."order_date" >= DATE '2026-10-15' AND "Orders"."status" IN ('Completed', 'Shipped')
                ORDER BY "Orders"."order_id\"""")) {
            CompiledQuery q = compiler.compile(sql);
            assertThat(q.sql()).doesNotContain("?");
            assertThat(runSql(q.sql())).isNotEmpty().isEqualTo(run(q));
        }
    }

    @Test
    void quotesInsideValuesAreEscapedInTheCompleteSql() throws Exception {
        CompiledQuery q = compiler.compile("""
                SELECT "Customers"."name" FROM "Customers" WHERE "Customers"."name" <> 'O''Brien; DROP TABLE "Orders"'""");
        assertThat(q.sql()).contains("'O''Brien; DROP TABLE \"Orders\"'");
        assertThat(runSql(q.sql())).isEqualTo(run(q));
    }

    @Test
    void starterQuerySalesByCountry() throws Exception {
        CompiledQuery q = compiler.compile("""
                SELECT "Customers"."country", COUNT("Orders"."order_id") AS "orders", SUM("Orders"."total") AS "revenue"
                FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                GROUP BY "Customers"."country"
                ORDER BY SUM("Orders"."total") DESC""");
        assertThat(q.targetSql()).isEqualTo("""
                SELECT "Customers"."country", COUNT("Orders"."order_id") AS "orders", SUM("Orders"."total") AS "revenue"
                FROM "Customers"
                INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                GROUP BY "Customers"."country"
                ORDER BY SUM("Orders"."total") DESC""");
        assertThat(run(q)).containsExactly(
                List.of("USA", 10, 1806), List.of("UK", 4, 1142), List.of("South Korea", 2, 605),
                List.of("France", 2, 447), List.of("Italy", 2, 188));
    }

    @Test
    void starterQueryCustomersWithoutOrders() throws Exception {
        CompiledQuery q = compiler.compile("""
                SELECT "Customers"."name", "Customers"."country"
                FROM "Customers" LEFT JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."order_id" IS NULL""");
        assertThat(q.targetSql()).isEqualTo("""
                SELECT "Customers"."name", "Customers"."country"
                FROM "Customers"
                LEFT JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."order_id" IS NULL""");
        assertThat(q.parameters()).isEmpty();
        assertThat(run(q)).containsExactlyInAnyOrder(List.of("Emma Wilson", "Canada"), List.of("Leo Nakamura", "Japan"));
    }

    @Test
    void resultColumnsComeFromTheValidatedRowType() {
        CompiledQuery q = compiler.compile(COMPLETED_ORDERS);
        assertThat(q.columns()).extracting(ResultColumn::name).containsExactly("customer", "city", "order_date", "total", "status");
        assertThat(q.columns()).extracting(c -> c.type().getSqlTypeName())
                .containsExactly(SqlTypeName.VARCHAR, SqlTypeName.VARCHAR, SqlTypeName.DATE, SqlTypeName.BIGINT, SqlTypeName.VARCHAR);
    }

    @Test
    void textComparisonsFollowTheDatabase_noCaseFoldingIsAdded() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"order_id\" FROM \"Orders\" WHERE \"status\" = 'completed'");
        assertThat(q.targetSql()).doesNotContain("LOWER");
        // SQLite's = is case-sensitive, and the data says 'Completed'.
        assertThat(run(q)).isEmpty();
    }

    @Test
    void datesBindAsIsoText() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"order_id\" FROM \"Orders\" WHERE \"order_date\" >= DATE '2026-11-01'");
        assertThat(q.targetSql()).endsWith("WHERE \"order_date\" >= ?");
        assertThat(run(q)).hasSize(12);
    }

    @Test
    void groupedQueriesKeepHavingParametersAfterWhereParameters() throws Exception {
        CompiledQuery q = compiler.compile("""
                SELECT c."country", SUM(o."total") AS "revenue" FROM "Customers" AS c
                INNER JOIN "Orders" AS o ON c."customer_id" = o."customer_id"
                WHERE c."country" <> 'Italy' GROUP BY c."country" HAVING SUM(o."total") > 400
                ORDER BY SUM(o."total") DESC""");
        assertThat(q.parameters()).extracting(p -> p.value().toString()).containsExactly("Italy", "400");
        assertThat(run(q)).extracting(r -> r.getFirst()).containsExactly("USA", "UK", "South Korea", "France");
    }

    @Test
    void writesNoNullOrderingTheUserDidNotAskFor() {
        assertThat(compiler.compile("SELECT \"order_id\" FROM \"Orders\" ORDER BY \"total\" DESC").targetSql())
                .doesNotContain("NULLS");
    }

    @Test
    void longInListsStayConditions() throws Exception {
        String ids = String.join(", ", java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(String::valueOf).toList());
        CompiledQuery q = compiler.compile("SELECT \"name\" FROM \"Customers\" WHERE \"customer_id\" IN (" + ids + ")");
        assertThat(q.targetSql()).doesNotContain("VALUES");
        assertThat(run(q)).hasSize(12);
    }

    @Test
    void crossJoinsStayCrossJoinsWithoutParameters() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"Customers\".\"name\", \"Products\".\"name\" AS \"product\" "
                + "FROM \"Customers\" CROSS JOIN \"Products\"");
        assertThat(q.parameters()).isEmpty();
        assertThat(q.targetSql()).doesNotContain("?");
        assertThat(run(q)).hasSize(12 * 10);
    }

    @Test
    void unknownNamesAreReportedWithTheirPosition() {
        assertThatThrownBy(() -> compiler.compile("SELECT \"nope\" FROM \"Orders\""))
                .isInstanceOfSatisfying(CompilationException.class, e -> {
                    Diagnostic d = e.diagnostics().getFirst();
                    assertThat(d.message()).contains("Column 'nope' not found");
                    assertThat(d.line()).isEqualTo(1);
                    assertThat(d.column()).isEqualTo(8);
                });
    }

    @Test
    void syntaxErrorsAreReportedWithTheirPosition() {
        assertThatThrownBy(() -> compiler.compile("SELECT \"order_id\" FROM"))
                .isInstanceOfSatisfying(CompilationException.class, e ->
                        assertThat(e.diagnostics().getFirst().line()).isEqualTo(1));
    }

    @Test
    void onlyQueriesAreAccepted() {
        assertThatThrownBy(() -> compiler.compile("DELETE FROM \"Orders\""))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Only SELECT queries");
        assertThatThrownBy(() -> compiler.compile("INSERT INTO \"Orders\" (\"order_id\") VALUES (1)"))
                .isInstanceOf(CompilationException.class);
    }

    // ---------------- Top Values: FETCH, exactly n rows ----------------

    static final String TOP_COUNTRIES = """
            SELECT "Customers"."country", COUNT("Orders"."order_id") AS "orders"
            FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
            GROUP BY "Customers"."country"
            ORDER BY COUNT("Orders"."order_id") DESC
            FETCH NEXT 3 ROWS ONLY""";

    @Test
    void fetchReturnsExactlyNRows_evenWhenTheNthRowHasTies() throws Exception {
        // USA 10, UK 4, then three countries tied on 2: still three rows.
        CompiledQuery q = compiler.compile(TOP_COUNTRIES);
        assertThat(q.targetSql()).doesNotContain("RANK").endsWith("LIMIT 3");
        List<List<Object>> rows = run(q);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("USA", 10);
        assertThat(q.columns()).extracting(ResultColumn::name).containsExactly("country", "orders");
    }

    // ---------------- StDev and Var on a database without them ----------------

    static double sampleStdDev(List<List<Object>> values) {
        double n = values.size(), sum = 0, squares = 0;
        for (List<Object> v : values) {
            double x = ((Number) v.getFirst()).doubleValue();
            sum += x;
            squares += x * x;
        }
        return Math.sqrt((squares - sum * sum / n) / (n - 1));
    }

    @Test
    void stDevAndVarRunOnSqlite_rewrittenWithSumAndCount() throws Exception {
        CompiledQuery q = compiler.compile("""
                SELECT STDDEV_SAMP("Orders"."total") AS "sd", VAR_SAMP("Orders"."total") AS "v" FROM "Orders\"""");
        assertThat(q.targetSql()).doesNotContain("STDDEV_SAMP").doesNotContain("VAR_SAMP");
        double expected = sampleStdDev(runSql("SELECT total FROM Orders"));
        List<Object> row = run(q).getFirst();
        assertThat(((Number) row.get(0)).doubleValue()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) row.get(1)).doubleValue()).isCloseTo(expected * expected, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(q.columns()).extracting(ResultColumn::name).containsExactly("sd", "v");
    }

    @Test
    void stDevPerGroupRunsOnSqlite() throws Exception {
        CompiledQuery q = compiler.compile("""
                SELECT "Orders"."status", STDDEV_SAMP("Orders"."total") AS "sd" FROM "Orders"
                GROUP BY "Orders"."status" ORDER BY "Orders"."status\"""");
        List<List<Object>> rows = run(q);
        List<List<Object>> statuses = runSql("SELECT DISTINCT status FROM Orders ORDER BY status");
        assertThat(rows).hasSameSizeAs(statuses);
        for (List<Object> row : rows) {
            List<List<Object>> totals = runSql("SELECT total FROM Orders WHERE status = '" + row.get(0) + "'");
            if (totals.size() > 1) {
                assertThat(((Number) row.get(1)).doubleValue())
                        .isCloseTo(sampleStdDev(totals), org.assertj.core.data.Offset.offset(1e-9));
            } else {
                assertThat(row.get(1)).isNull();
            }
        }
    }

    @Test
    void avgOfWholeNumbersIsADouble_asTheDatabaseReturnsIt() throws Exception {
        CompiledQuery q = compiler.compile("SELECT AVG(\"Orders\".\"total\") AS \"a\" FROM \"Orders\"");
        assertThat(q.columns().getFirst().type().getSqlTypeName()).isEqualTo(SqlTypeName.DOUBLE);
        assertThat(q.targetSql()).contains("AVG(");
        double expected = ((Number) runSql("SELECT AVG(total) FROM Orders").getFirst().getFirst()).doubleValue();
        assertThat(expected).isNotEqualTo(Math.floor(expected));
        assertThat(((Number) run(q).getFirst().getFirst()).doubleValue()).isEqualTo(expected);
    }
}
