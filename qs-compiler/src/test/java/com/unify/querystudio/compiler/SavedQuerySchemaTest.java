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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saved queries are views to Calcite, in the {@code queries} schema and named by their ID, so a
 * query can use one as a source, as Access does. Calcite expands the view, so the target SQL is
 * complete without them.
 */
class SavedQuerySchemaTest {

    @TempDir
    static Path dir;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static QueryCompiler compiler;
    static Path db;

    static final Map<String, String> SAVED = Map.of(
            "big", "SELECT \"Orders\".\"customer_id\", \"Orders\".\"total\" FROM \"Orders\" WHERE \"Orders\".\"total\" >= 100",
            "bigger", "SELECT \"b\".\"customer_id\", \"b\".\"total\" FROM \"queries\".\"big\" AS \"b\" WHERE \"b\".\"total\" >= 300",
            "loop", "SELECT \"l\".\"total\" FROM \"queries\".\"loop\" AS \"l\"",
            "prompt", "SELECT \"Orders\".\"total\" FROM \"Orders\" WHERE \"Orders\".\"total\" >= :MinTotal");

    @BeforeAll
    static void setUp() throws Exception {
        db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        SchemaPlus schema;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            schema = CatalogReader.read(c, SQLITE);
        }
        SavedQuerySchema.attach(schema, SQLITE, new SavedQuerySchema.Source() {
            @Override
            public Set<String> ids() {
                return SAVED.keySet();
            }

            @Override
            public Optional<String> sql(String id) {
                return Optional.ofNullable(SAVED.get(id));
            }
        });
        compiler = new QueryCompiler(schema, SQLITE);
    }

    static List<List<Object>> runSql(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            return QueryCompilerTest.rows(c.createStatement().executeQuery(sql));
        }
    }

    @Test
    void aSavedQueryIsASourceNamedByItsId_expandedIntoTheTargetSql() throws Exception {
        CompiledQuery q = compiler.compile("""
                SELECT "Customers"."name", "Big orders"."total"
                FROM "Customers" INNER JOIN "queries"."big" AS "Big orders" ON "Customers"."customer_id" = "Big orders"."customer_id"
                ORDER BY "Big orders"."total\"""");
        assertThat(q.columns()).extracting(ResultColumn::name).containsExactly("name", "total");
        assertThat(q.sql()).doesNotContain("queries").contains("FROM \"Orders\"").contains(">= 100");
        assertThat(q.references()).extracting(FieldReference::table).doesNotContain(List.of("queries", "big"))
                .contains(List.of("Orders"), List.of("Customers"));
        assertThat(runSql(q.sql())).isNotEmpty().isEqualTo(runSql("""
                SELECT c.name, o.total FROM Customers c JOIN Orders o ON c.customer_id = o.customer_id
                WHERE o.total >= 100 ORDER BY o.total"""));
    }

    @Test
    void aSavedQueryCanUseAnotherSavedQuery() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"x\".\"total\" FROM \"queries\".\"bigger\" AS \"x\"");
        assertThat(runSql(q.sql())).isNotEmpty()
                .isEqualTo(runSql("SELECT total FROM Orders WHERE total >= 100 AND total >= 300"));
    }

    @Test
    void anUnknownSavedQueryIsNotFound() {
        assertThatThrownBy(() -> compiler.compile("SELECT \"x\".\"total\" FROM \"queries\".\"nope\" AS \"x\""))
                .isInstanceOf(CompilationException.class).hasMessageContaining("nope");
    }

    @Test
    void aSavedQueryThatUsesItselfIsRefused() {
        assertThatThrownBy(() -> compiler.compile("SELECT \"x\".\"total\" FROM \"queries\".\"loop\" AS \"x\""))
                .isInstanceOf(CompilationException.class).hasMessageContaining("uses itself");
    }

    @Test
    void aSavedQueryWithParametersCannotBeUsedAsASourceYet() {
        assertThatThrownBy(() -> compiler.compile("SELECT \"x\".\"total\" FROM \"queries\".\"prompt\" AS \"x\""))
                .isInstanceOf(CompilationException.class).hasMessageContaining("parameters");
    }
}
