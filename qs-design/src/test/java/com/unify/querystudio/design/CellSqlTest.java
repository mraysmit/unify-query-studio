package com.unify.querystudio.design;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Grid cells hold standard SQL. A criteria cell may leave out its left operand, which is the cell's
 * column: {@code >= 100} means "this column >= 100", and a bare value means equality.
 */
class CellSqlTest {

    static final SqlIdentifier TOTAL = column("Orders", "total");
    static final SqlIdentifier STATUS = column("Orders", "status");
    static final SqlIdentifier ORDER_DATE = column("Orders", "order_date");
    static final SqlIdentifier NAME = column("Customers", "name");

    static SqlIdentifier column(String table, String field) {
        return new SqlIdentifier(List.of(table, field), SqlParserPos.ZERO);
    }

    static String sql(SqlNode node) {
        return node.toSqlString(c -> c.withDialect(CalciteSqlDialect.DEFAULT)).getSql();
    }

    static SqlIdentifier columnNamed(String key) {
        return switch (key) {
            case "total" -> TOTAL;
            case "status" -> STATUS;
            case "date" -> ORDER_DATE;
            default -> NAME;
        };
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '~', quoteCharacter = '`', value = {
            // implied operand
            "total  ~ >= 100                          ~ \"Orders\".\"total\" >= 100",
            "total  ~ <>5                             ~ \"Orders\".\"total\" <> 5",
            "total  ~ BETWEEN 150 AND 300             ~ \"Orders\".\"total\" BETWEEN ASYMMETRIC 150 AND 300",
            "total  ~ NOT BETWEEN 150 AND 300         ~ \"Orders\".\"total\" NOT BETWEEN ASYMMETRIC 150 AND 300",
            "total  ~ between 150 and 300             ~ \"Orders\".\"total\" BETWEEN ASYMMETRIC 150 AND 300",
            "status ~ IN ('Completed', 'Shipped')     ~ \"Orders\".\"status\" IN ('Completed', 'Shipped')",
            "status ~ NOT IN ('Completed')            ~ \"Orders\".\"status\" NOT IN ('Completed')",
            "status ~ IS NULL                         ~ \"Orders\".\"status\" IS NULL",
            "status ~ IS NOT NULL                     ~ \"Orders\".\"status\" IS NOT NULL",
            "name   ~ LIKE 'A%'                       ~ \"Customers\".\"name\" LIKE 'A%'",
            "name   ~ NOT LIKE 'A%'                   ~ \"Customers\".\"name\" NOT LIKE 'A%'",
            // a bare value means equality
            "total  ~ 248                             ~ \"Orders\".\"total\" = 248",
            "status ~ 'Completed'                     ~ \"Orders\".\"status\" = 'Completed'",
            "date   ~ DATE '2026-11-01'               ~ \"Orders\".\"order_date\" = DATE '2026-11-01'",
            // the implied operand applies to every top-level AND/OR segment
            "total  ~ > 100 AND < 300                 ~ \"Orders\".\"total\" > 100 AND \"Orders\".\"total\" < 300",
            "status ~ 'O''Brien' OR 'Smith'           ~ \"Orders\".\"status\" = 'O''Brien' OR \"Orders\".\"status\" = 'Smith'",
            "total  ~ BETWEEN 1 AND 5 OR > 300        ~ \"Orders\".\"total\" BETWEEN ASYMMETRIC 1 AND 5 OR \"Orders\".\"total\" > 300",
            // standard SQL values and functions
            "date   ~ >= CURRENT_DATE                 ~ \"Orders\".\"order_date\" >= CURRENT_DATE",
            "status ~ 'Comp' || 'leted'               ~ \"Orders\".\"status\" = 'Comp' || 'leted'",
            // double quotes name a column, as in standard SQL
            "total  ~ > \"Orders\".\"order_id\"       ~ \"Orders\".\"total\" > \"Orders\".\"order_id\"",
            // a cell may be a complete predicate naming other fields
            "name   ~ CHAR_LENGTH(\"Customers\".\"name\") > 10 ~ CHAR_LENGTH(\"Customers\".\"name\") > 10"})
    void cellBecomesAPredicateOnItsColumn(String columnKey, String cell, String expected) {
        assertThat(sql(CellSql.predicate(cell, columnNamed(columnKey)))).isEqualTo(expected);
    }

    @Test
    void textInsideStringsIsNeverSplit() {
        assertThat(sql(CellSql.predicate("'Yes AND No OR maybe'", STATUS)))
                .isEqualTo("\"Orders\".\"status\" = 'Yes AND No OR maybe'");
    }

    @Test
    void anEmptyCellHasNoCriterion() {
        assertThat(CellSql.predicate("   ", STATUS)).isNull();
    }

    @Test
    void unbalancedInputIsReportedNotGuessed() {
        assertThatThrownBy(() -> CellSql.predicate(">= (100", TOTAL)).isInstanceOf(CriteriaException.class);
        assertThatThrownBy(() -> CellSql.predicate("'unclosed", STATUS))
                .isInstanceOf(CriteriaException.class)
                .hasMessageContaining("quote");
    }

    @Test
    void aFieldCellExpressionIsStandardSql() {
        assertThat(sql(CellSql.expression("\"Orders\".\"total\" * 2"))).isEqualTo("\"Orders\".\"total\" * 2");
        assertThat(sql(CellSql.expression("UPPER(\"Customers\".\"name\") || ' ' || \"Customers\".\"city\"")))
                .isEqualTo("UPPER(\"Customers\".\"name\") || ' ' || \"Customers\".\"city\"");
    }

    @Test
    void sqlIsWrittenBackAsCellText() {
        assertThat(CellSql.text(CellSql.expression("\"Orders\".\"total\"   *  2"))).isEqualTo("\"Orders\".\"total\" * 2");
        assertThat(CellSql.text(CellSql.expression("DATE '2026-11-01'"))).isEqualTo("DATE '2026-11-01'");
    }
}
