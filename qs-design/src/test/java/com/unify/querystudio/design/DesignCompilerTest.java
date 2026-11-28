package com.unify.querystudio.design;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.compiler.CompilationException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Compiling a design reports a validation error against the design element it came from (a
 * source, a join line, a grid column or a criteria cell), not as a position in SQL the user never
 * typed.
 */
class DesignCompilerTest {

    @TempDir
    static Path dir;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static QueryCompiler compiler;

    @BeforeAll
    static void setUp() throws Exception {
        Path db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            compiler = new QueryCompiler(CatalogReader.read(c, SQLITE), SQLITE);
        }
    }

    static final List<Source> SOURCES = List.of(Source.of("Customers"), Source.of("Orders"));
    static final JoinLine CUSTOMER_ORDERS = new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.INNER);

    static DesignView design(GridColumn... columns) {
        return DesignView.of(SOURCES, List.of(CUSTOMER_ORDERS), List.of(columns));
    }

    static DesignException error(DesignView design) {
        return catchThrowableOfType(DesignException.class, () -> DesignCompiler.compile(design, compiler));
    }

    @Test
    void aValidDesignGivesItsCanonicalSqlAndTheCompiledQuery() {
        DesignCompiler.Result r = DesignCompiler.compile(design(GridColumn.field("Orders", "total").criteria(">= 100")), compiler);
        assertThat(r.canonicalSql()).contains("\"Orders\".\"total\" >= 100");
        assertThat(r.query().sql()).contains("WHERE \"Orders\".\"total\" >= 100");
    }

    @Test
    void anUnknownFieldIsReportedOnItsColumn() {
        DesignException e = error(design(GridColumn.field("Customers", "name"), GridColumn.field("Orders", "nope")));
        assertThat(e.column()).isEqualTo(1);
        assertThat(e.criteriaRow()).isNull();
        assertThat(e.getMessage()).contains("nope");
    }

    @Test
    void aBadExpressionIsReportedOnItsColumn() {
        DesignException e = error(design(GridColumn.field("Customers", "name"), GridColumn.expression("NO_SUCH_FUNCTION(\"Orders\".\"total\")")));
        assertThat(e.column()).isEqualTo(1);
        assertThat(e.getMessage()).containsIgnoringCase("NO_SUCH_FUNCTION");
    }

    @Test
    void aBadCriteriaCellIsReportedOnThatCell() {
        DesignException e = error(design(GridColumn.field("Customers", "name"),
                GridColumn.field("Orders", "status").criteria("'Completed'", "= \"Orders\".\"nope\"")));
        assertThat(e.column()).isEqualTo(1);
        assertThat(e.criteriaRow()).isEqualTo(1);
        assertThat(e.getMessage()).contains("nope");
    }

    @Test
    void aJoinOnAnUnknownFieldIsReportedOnItsLine() {
        DesignView d = DesignView.of(List.of(Source.of("Customers"), Source.of("Orders"), Source.of("OrderItems")),
                List.of(CUSTOMER_ORDERS, new JoinLine("Orders", "order_id", "OrderItems", "nope", JoinType.INNER)),
                List.of(GridColumn.field("Customers", "name")));
        DesignException e = error(d);
        assertThat(e.join()).isEqualTo(1);
        assertThat(e.column()).isNull();
        assertThat(e.getMessage()).contains("nope");
    }

    @Test
    void anUnknownTableIsReportedOnItsSource() {
        DesignView d = DesignView.of(List.of(Source.of("Customers"), Source.of("Nope")), List.of(),
                List.of(GridColumn.field("Customers", "name")));
        DesignException e = error(d);
        assertThat(e.source()).isEqualTo("Nope");
        assertThat(e.getMessage()).contains("Nope");
    }

    @Test
    void anUngroupedFieldInATotalsQueryIsReportedOnItsColumn() {
        DesignView d = design(GridColumn.field("Customers", "country"), GridColumn.field("Orders", "total").total(Total.EXPRESSION),
                GridColumn.field("Orders", "order_id").total(Total.aggregate(SqlStdOperatorTable.COUNT))).withTotals(true);
        DesignException e = error(d);
        assertThat(e.column()).isEqualTo(1);
    }

    @Test
    void ambiguousOuterJoinsAreReportedOnTheSourceWhereTheyMeet_inAccessWords() {
        // Customers -> Orders (outer) then Orders = OrderItems (inner): Access's ambiguous case.
        DesignView d = DesignView.of(List.of(Source.of("Customers"), Source.of("Orders"), Source.of("OrderItems")),
                List.of(new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.LEFT),
                        new JoinLine("Orders", "order_id", "OrderItems", "order_id", JoinType.INNER)),
                List.of(GridColumn.field("Customers", "name")));
        DesignException e = error(d);
        assertThat(e.getMessage()).isEqualTo(AmbiguousJoinException.ACCESS_MESSAGE);
        assertThat(e.source()).isEqualTo("Orders");
    }

    @Test
    void anErrorNoSingleElementCausesIsReportedOnTheWholeQuery() {
        // Unique Values with a sort on a hidden field: each element is fine alone.
        DesignView d = design(GridColumn.field("Customers", "name"),
                GridColumn.field("Orders", "total").show(false).sort(Direction.DESCENDING)).withUniqueValues(true);
        assertThatThrownBy(() -> DesignCompiler.compile(d, compiler)).isInstanceOf(CompilationException.class);
    }

    @Test
    void aJoinBetweenFieldsOfDifferentTypesIsRefusedOnItsLine() {
        DesignView d = DesignView.of(SOURCES, List.of(new JoinLine("Customers", "name", "Orders", "customer_id", JoinType.INNER)),
                List.of(GridColumn.field("Customers", "name")));
        DesignException e = error(d);
        assertThat(e.join()).isEqualTo(0);
        assertThat(e.getMessage()).startsWith("Type mismatch in expression.").contains("Customers.name", "Orders.customer_id");
    }

    @Test
    void sumAvgStDevAndVarAreRefusedOnFieldsThatAreNotNumbers() {
        for (var function : List.of(SqlStdOperatorTable.SUM, SqlStdOperatorTable.AVG, SqlStdOperatorTable.STDDEV_SAMP,
                SqlStdOperatorTable.VAR_SAMP)) {
            DesignException e = error(design(GridColumn.field("Customers", "country"),
                    GridColumn.field("Orders", "status").total(Total.aggregate(function))).withTotals(true));
            assertThat(e.column()).isEqualTo(1);
            assertThat(e.getMessage()).contains("number").contains("status");
        }
    }

    @Test
    void minMaxAndCountWorkOnAnyField() {
        for (var function : List.of(SqlStdOperatorTable.MIN, SqlStdOperatorTable.MAX, SqlStdOperatorTable.COUNT)) {
            DesignCompiler.compile(design(GridColumn.field("Customers", "country"),
                    GridColumn.field("Orders", "status").total(Total.aggregate(function))).withTotals(true), compiler);
        }
    }

    @Test
    void anAggregateOfANumberExpressionIsFine() {
        DesignCompiler.compile(design(GridColumn.field("Customers", "country"),
                GridColumn.expression("\"Orders\".\"total\" * 2").alias("doubled").total(Total.aggregate(SqlStdOperatorTable.SUM)))
                .withTotals(true), compiler);
    }
}
