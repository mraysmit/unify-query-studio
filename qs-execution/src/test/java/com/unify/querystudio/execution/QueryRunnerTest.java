package com.unify.querystudio.execution;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.compiler.CompiledQuery;
import com.unify.querystudio.compiler.QueryCompiler;
import com.unify.querystudio.compiler.ResultColumn;
import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.testkit.NorthstarDatabase;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryRunnerTest {

    @TempDir
    static Path dir;
    static Path db;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static QueryCompiler compiler;
    final QueryRunner runner = new QueryRunner(SQLITE);

    @BeforeAll
    static void setUp() throws Exception {
        db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        try (Connection c = connect()) {
            compiler = new QueryCompiler(CatalogReader.read(c, SQLITE), SQLITE);
        }
    }

    static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + db);
    }

    static final Duration GENEROUS = Duration.ofSeconds(30);

    @Test
    void returnsTypedValuesAccordingToTheValidatedColumnTypes() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"order_id\", \"order_date\", \"status\", \"total\" FROM \"Orders\" "
                + "WHERE \"order_id\" = 1001");
        try (Connection c = connect()) {
            RunResult r = runner.run(c, q, 100, GENEROUS);
            assertThat(r.rows()).containsExactly(List.of(1001L, LocalDate.of(2026, 10, 3), "Completed", 248L));
            assertThat(r.truncated()).isFalse();
        }
    }

    @Test
    void booleansStoredAsIntegersComeBackAsBooleans() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"in_stock\" FROM \"Products\" ORDER BY \"product_id\"");
        try (Connection c = connect()) {
            assertThat(runner.run(c, q, 100, GENEROUS).rows()).extracting(r -> r.getFirst())
                    .allMatch(v -> v instanceof Boolean).contains(true, false);
        }
    }

    @Test
    void bindsParametersThroughTheDialect() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"order_id\" FROM \"Orders\" WHERE \"order_date\" >= DATE '2026-11-01' "
                + "AND \"status\" = 'Completed'");
        try (Connection c = connect()) {
            assertThat(runner.run(c, q, 100, GENEROUS).rows()).isNotEmpty();
        }
    }

    @Test
    void stopsAtTheRowLimitAndSaysSo() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"order_id\" FROM \"Orders\" ORDER BY \"order_id\"");
        try (Connection c = connect()) {
            RunResult r = runner.run(c, q, 5, GENEROUS);
            assertThat(r.rows()).hasSize(5);
            assertThat(r.truncated()).isTrue();
            assertThat(runner.run(c, q, 20, GENEROUS).truncated()).isFalse();
        }
    }

    /** A statement SQLite takes seconds to finish, to exercise timeouts and cancellation. */
    static CompiledQuery slow() {
        var types = new JavaTypeFactoryImpl();
        String sql = "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 500000000) "
                + "SELECT count(*) FROM n";
        return new CompiledQuery(sql, sql, List.of(), List.of(new ResultColumn("count", types.createSqlType(SqlTypeName.BIGINT))), List.of());
    }

    @Test
    void aRunThatExceedsItsTimeoutIsStopped() throws Exception {
        try (Connection c = connect()) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> runner.run(c, slow(), 100, Duration.ofMillis(300)))
                    .isInstanceOf(QueryTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void aRunningQueryCanBeCancelled() throws Exception {
        try (Connection c = connect()) {
            RunHandle handle = runner.start(c, slow(), 100, GENEROUS);
            CompletableFuture<RunResult> result = CompletableFuture.supplyAsync(handle::await);
            TimeUnit.MILLISECONDS.sleep(200);
            handle.cancel();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(QueryCancelledException.class);
        }
    }

    @Test
    void reportsHowLongTheRunTook() throws Exception {
        CompiledQuery q = compiler.compile("SELECT \"order_id\" FROM \"Orders\"");
        try (Connection c = connect()) {
            assertThat(runner.run(c, q, 100, GENEROUS).elapsed()).isPositive();
        }
    }
}
