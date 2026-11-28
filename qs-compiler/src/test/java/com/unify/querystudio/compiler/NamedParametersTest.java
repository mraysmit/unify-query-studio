package com.unify.querystudio.compiler;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.testkit.NorthstarDatabase;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Named parameters, written {@code :name} (standard SQL's host-variable form): typed by Calcite
 * from where they are used, kept by name in the complete SQL, and bound from values at run time.
 */
class NamedParametersTest {

    @TempDir
    static Path dir;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static QueryCompiler compiler;
    static Path db;

    /** Runs compiled SQL with its parameters, bound by the dialect's binder. */
    static List<List<Object>> run(CompiledQuery q) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(q.targetSql())) {
            for (int i = 0; i < q.parameters().size(); i++) {
                BoundValue p = q.parameters().get(i);
                SQLITE.binder().bind(ps, i + 1, p.value(), p.type());
            }
            return QueryCompilerTest.rows(ps.executeQuery());
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            compiler = new QueryCompiler(CatalogReader.read(c, SQLITE), SQLITE);
        }
    }

    static final String BY_TOTAL_AND_STATUS = """
            SELECT "Orders"."order_id" FROM "Orders"
            WHERE "Orders"."total" >= :MinTotal AND "Orders"."status" = :Status
            ORDER BY "Orders"."order_id\"""";

    @Test
    void markingTurnsNamesIntoMarkers_andLeavesQuotedTextAlone() {
        NamedParameters.Marked m = NamedParameters.mark("SELECT ':Not' AS \":nor\" FROM t WHERE a = :A AND b > :b_2 OR c = :A");
        assertThat(m.sql()).isEqualTo("SELECT ':Not' AS \":nor\" FROM t WHERE a = ? AND b > ? OR c = ?");
        assertThat(m.names()).containsExactly("A", "b_2", "A");
    }

    @Test
    void aBareQuestionMarkIsRefused_parametersHaveNames() {
        assertThatThrownBy(() -> NamedParameters.mark("SELECT a FROM t WHERE a = ?"))
                .isInstanceOf(ParameterException.class).hasMessageContaining(":name");
    }

    @Test
    void parametersAreTypedFromWhereTheyAreUsed_andKeptByNameInTheCompleteSql() {
        CompiledQuery q = compiler.compile(BY_TOTAL_AND_STATUS);
        assertThat(q.queryParameters()).extracting(QueryParameter::name).containsExactly("MinTotal", "Status");
        assertThat(q.queryParameters()).extracting(p -> p.type().getSqlTypeName())
                .containsExactly(SqlTypeName.BIGINT, SqlTypeName.VARCHAR);
        assertThat(q.sql()).contains("WHERE \"total\" >= :MinTotal AND \"status\" = :Status");
    }

    @Test
    void theSameParameterUsedTwiceIsAskedForOnce() {
        CompiledQuery q = compiler.compile("""
                SELECT "Orders"."order_id" FROM "Orders" WHERE "Orders"."total" >= :Limit OR "Orders"."customer_id" >= :Limit""");
        assertThat(q.queryParameters()).extracting(QueryParameter::name).containsExactly("Limit");
        assertThat(q.sql()).contains(">= :Limit OR").endsWith(">= :Limit");
    }

    @Test
    void aParameterUsedAsTwoTypesIsRefused() {
        assertThatThrownBy(() -> compiler.compile("""
                SELECT "Orders"."order_id" FROM "Orders" WHERE "Orders"."total" >= :X AND "Orders"."status" = :X"""))
                .isInstanceOf(CompilationException.class).hasMessageContaining(":X");
    }

    @Test
    void boundValuesRunTheQuery_withTheRowsOfTheSameQueryWrittenWithValues() throws Exception {
        CompiledQuery bound = compiler.compile(BY_TOTAL_AND_STATUS).bind(Map.of("MinTotal", 100, "Status", "Completed"));
        assertThat(run(bound)).isNotEmpty().isEqualTo(run(compiler.compile("""
                SELECT "Orders"."order_id" FROM "Orders"
                WHERE "Orders"."total" >= 100 AND "Orders"."status" = 'Completed'
                ORDER BY "Orders"."order_id\"""")));
    }

    @Test
    void valuesAreCheckedAgainstTheParameterTypes() throws Exception {
        CompiledQuery q = compiler.compile(BY_TOTAL_AND_STATUS);
        assertThatThrownBy(() -> q.bind(Map.of("Status", "Completed")))
                .isInstanceOf(ParameterException.class).hasMessageContaining(":MinTotal");
        assertThatThrownBy(() -> q.bind(Map.of("MinTotal", "lots", "Status", "Completed")))
                .isInstanceOf(ParameterException.class).hasMessageContaining(":MinTotal").hasMessageContaining("number");

        CompiledQuery dated = compiler.compile("""
                SELECT "Orders"."order_id" FROM "Orders" WHERE "Orders"."order_date" >= :From ORDER BY "Orders"."order_id\"""");
        assertThat(run(dated.bind(Map.of("From", "2026-10-15")))).isNotEmpty();
        assertThatThrownBy(() -> dated.bind(Map.of("From", "15/10/2026")))
                .isInstanceOf(ParameterException.class).hasMessageContaining("date");
        Map<String, Object> nothing = new HashMap<>();
        nothing.put("From", null);
        assertThat(run(dated.bind(nothing))).as("NULL matches no row").isEmpty();
    }

    @Test
    void aQueryWithoutParametersHasNone_andTextLiteralsAreNotParameters() {
        CompiledQuery q = compiler.compile("SELECT \"Orders\".\"order_id\" FROM \"Orders\" WHERE \"Orders\".\"status\" = 'a:b'");
        assertThat(q.queryParameters()).isEmpty();
        assertThat(q.sql()).contains("'a:b'");
        assertThat(q.bind(Map.of()).parameters()).hasSize(1);
        assertThat(List.of(q.bind(Map.of()).parameters().getFirst().value().toString())).containsExactly("a:b");
    }
}
