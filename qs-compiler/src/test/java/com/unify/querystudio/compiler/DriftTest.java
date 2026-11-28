package com.unify.querystudio.compiler;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.testkit.NorthstarDatabase;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A compiled query records the fields it uses, with their types (its catalog fingerprint); opening a
 * saved query compares them with the catalog as it is now.
 */
class DriftTest {

    @TempDir
    static Path dir;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static QueryCompiler northstar;

    @BeforeAll
    static void setUp() throws Exception {
        Path db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            northstar = new QueryCompiler(CatalogReader.read(c, SQLITE), SQLITE);
        }
    }

    static SchemaPlus schema(String file, String... ddl) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(file));
             Statement s = c.createStatement()) {
            for (String statement : ddl) {
                s.execute(statement);
            }
            return CatalogReader.read(c, SQLITE);
        }
    }

    static FieldReference ref(String table, String column, String type) {
        return new FieldReference(List.of(table), column, type);
    }

    @Test
    void aQueryRecordsEveryFieldItUses_inSelectJoinsCriteriaAndSort_andNoOthers() {
        CompiledQuery q = northstar.compile("""
                SELECT "Customers"."name" FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                WHERE "Orders"."status" = 'Completed' ORDER BY "Orders"."total\"""");
        assertThat(q.references()).containsExactlyInAnyOrder(
                ref("Customers", "customer_id", "BIGINT"), ref("Customers", "name", "VARCHAR"),
                ref("Orders", "customer_id", "BIGINT"), ref("Orders", "status", "VARCHAR"), ref("Orders", "total", "BIGINT"));
    }

    @Test
    void anUnchangedCatalogHasNoDrift_andNewColumnsAreNotDrift() throws Exception {
        SchemaPlus before = schema("same.db", "CREATE TABLE T (a INTEGER, b TEXT)");
        List<FieldReference> refs = new QueryCompiler(before, SQLITE).compile("SELECT \"T\".\"a\", \"T\".\"b\" FROM \"T\"").references();
        assertThat(Drift.check(refs, before)).isEmpty();
        SchemaPlus after = schema("same.db", "ALTER TABLE T ADD COLUMN c TEXT");
        assertThat(Drift.check(refs, after)).isEmpty();
    }

    @Test
    void aMissingTableAMissingFieldAndAChangedTypeAreReported() throws Exception {
        SchemaPlus before = schema("before.db", "CREATE TABLE T (a INTEGER, b TEXT)", "CREATE TABLE U (x INTEGER)");
        List<FieldReference> refs = new QueryCompiler(before, SQLITE)
                .compile("SELECT \"T\".\"a\", \"T\".\"b\", \"U\".\"x\" FROM \"T\" CROSS JOIN \"U\"").references();
        SchemaPlus after = schema("after.db", "CREATE TABLE T (a TEXT)");
        assertThat(Drift.check(refs, after)).containsExactlyInAnyOrder(
                new Drift.Change(List.of("T"), "a", Drift.Problem.TYPE_CHANGED, "BIGINT", "VARCHAR"),
                new Drift.Change(List.of("T"), "b", Drift.Problem.FIELD_MISSING, "VARCHAR", null),
                new Drift.Change(List.of("U"), null, Drift.Problem.TABLE_MISSING, null, null));
    }
}
