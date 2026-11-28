package com.unify.querystudio.design;

import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canvas's join lines become a SQL join tree. Access's rule decides which graphs are ambiguous;
 * canvas order never changes the result. Arrow notation in test names: A --> B is a LEFT join from A
 * (Access's arrow points at B, the side showing only matching rows).
 */
class JoinTreeTest {

    static final Source C = Source.of("Customers");
    static final Source O = Source.of("Orders");
    static final Source I = Source.of("OrderItems");
    static final Source P = Source.of("Products");

    static JoinLine line(Source l, String lc, Source r, String rc, JoinType type) {
        return new JoinLine(l.alias(), lc, r.alias(), rc, type);
    }

    static final JoinLine C_O = line(C, "customer_id", O, "customer_id", JoinType.INNER);
    static final JoinLine O_I = line(O, "order_id", I, "order_id", JoinType.INNER);
    static final JoinLine P_I = line(P, "product_id", I, "product_id", JoinType.INNER);

    static JoinLine as(JoinLine l, JoinType type) {
        return new JoinLine(l.leftSource(), l.leftColumn(), l.rightSource(), l.rightColumn(), type);
    }

    /** The FROM clause as text. (Calcite prints a bare join as "SELECT * FROM …", so that prefix is dropped.) */
    static String sql(SqlNode from) {
        return from.toSqlString(c -> c.withDialect(CalciteSqlDialect.DEFAULT)).getSql()
                .replaceAll("\\s+", " ").strip().replaceFirst("^SELECT \\* FROM ", "");
    }

    @Test
    void oneSourceIsJustItsTable() {
        assertThat(sql(JoinTree.from(List.of(C), List.of()))).isEqualTo("\"Customers\"");
    }

    @Test
    void aSecondCopyOfATableIsAliased() {
        Source c1 = new Source("Customers_1", List.of("Customers"));
        assertThat(sql(JoinTree.from(List.of(c1), List.of()))).isEqualTo("\"Customers\" AS \"Customers_1\"");
    }

    @Test
    void innerJoinsOverFourTables() {
        assertThat(sql(JoinTree.from(List.of(P, C, I, O), List.of(C_O, O_I, P_I)))).isEqualTo(
                "\"Customers\" INNER JOIN \"Orders\" ON \"Customers\".\"customer_id\" = \"Orders\".\"customer_id\" "
                + "INNER JOIN \"OrderItems\" ON \"Orders\".\"order_id\" = \"OrderItems\".\"order_id\" "
                + "INNER JOIN \"Products\" ON \"Products\".\"product_id\" = \"OrderItems\".\"product_id\"");
    }

    @Test
    void severalLinesBetweenTwoSourcesFormOneCompoundCondition() {
        JoinLine second = line(C, "name", O, "status", JoinType.INNER);
        assertThat(sql(JoinTree.from(List.of(C, O), List.of(C_O, second)))).isEqualTo(
                "\"Customers\" INNER JOIN \"Orders\" ON \"Customers\".\"customer_id\" = \"Orders\".\"customer_id\" "
                + "AND \"Customers\".\"name\" = \"Orders\".\"status\"");
    }

    @Test
    void unconnectedSourcesAreCrossJoined() {
        assertThat(sql(JoinTree.from(List.of(P, C), List.of()))).isEqualTo("\"Customers\" CROSS JOIN \"Products\"");
    }

    // ---- Access's documented examples ----

    @Test
    void outerChainIsFine_C_to_O_to_I() {
        assertThat(sql(JoinTree.from(List.of(C, O, I), List.of(as(C_O, JoinType.LEFT), as(O_I, JoinType.LEFT))))).isEqualTo(
                "\"Customers\" LEFT JOIN \"Orders\" ON \"Customers\".\"customer_id\" = \"Orders\".\"customer_id\" "
                + "LEFT JOIN \"OrderItems\" ON \"Orders\".\"order_id\" = \"OrderItems\".\"order_id\"");
    }

    @Test
    void outersPointingApartAreFine_O_from_I_to_P() {
        // OrderItems keeps all its rows; both arrows point away from it.
        assertThat(sql(JoinTree.from(List.of(O, I, P), List.of(as(O_I, JoinType.RIGHT), as(P_I, JoinType.RIGHT))))).isEqualTo(
                "\"OrderItems\" LEFT JOIN \"Orders\" ON \"Orders\".\"order_id\" = \"OrderItems\".\"order_id\" "
                + "LEFT JOIN \"Products\" ON \"Products\".\"product_id\" = \"OrderItems\".\"product_id\"");
    }

    @Test
    void outerAwayFromAnInnerIsFine_C_from_O_inner_I() {
        assertThat(sql(JoinTree.from(List.of(C, O, I), List.of(as(C_O, JoinType.RIGHT), O_I)))).isEqualTo(
                "\"OrderItems\" INNER JOIN \"Orders\" ON \"Orders\".\"order_id\" = \"OrderItems\".\"order_id\" "
                + "LEFT JOIN \"Customers\" ON \"Customers\".\"customer_id\" = \"Orders\".\"customer_id\"");
    }

    @Test
    void outerPointingAtAnInnerIsAmbiguous_C_to_O_inner_I() {
        assertThatThrownBy(() -> JoinTree.from(List.of(C, O, I), List.of(as(C_O, JoinType.LEFT), O_I)))
                .isInstanceOfSatisfying(AmbiguousJoinException.class, e -> {
                    assertThat(e).hasMessage(AmbiguousJoinException.ACCESS_MESSAGE);
                    assertThat(e.table()).isEqualTo("Orders");
                });
    }

    @Test
    void twoOutersPointingTogetherAreAmbiguous_O_to_I_from_P() {
        assertThatThrownBy(() -> JoinTree.from(List.of(O, I, P), List.of(as(O_I, JoinType.LEFT), as(P_I, JoinType.LEFT))))
                .isInstanceOfSatisfying(AmbiguousJoinException.class, e -> assertThat(e.table()).isEqualTo("OrderItems"));
    }

    @Test
    void canvasOrderNeverChangesTheTree() {
        List<JoinLine> lines = List.of(as(C_O, JoinType.LEFT), as(O_I, JoinType.LEFT));
        String a = sql(JoinTree.from(List.of(C, O, I), lines));
        assertThat(sql(JoinTree.from(List.of(I, C, O), lines))).isEqualTo(a);
        assertThat(sql(JoinTree.from(List.of(O, I, C), lines))).isEqualTo(a);
    }
}
