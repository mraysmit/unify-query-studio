package learning;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What Calcite 1.42's parser accepts with the canonical configuration (double-quoted identifiers,
 * case kept), which grid cells are parsed with. These pin the facts behind criteria cells.
 */
class CalciteParserLearningTest {

    static final SqlParser.Config CANONICAL = SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true);

    static SqlNode parse(String expression) throws SqlParseException {
        return SqlParser.create(expression, CANONICAL).parseExpression();
    }

    @Test
    void doubleQuotesQuoteIdentifiers() throws Exception {
        SqlBasicCall call = (SqlBasicCall) parse("\"Order Details\".\"Unit Price\" > 10");
        SqlIdentifier id = call.operand(0);
        assertThat(id.names).containsExactly("Order Details", "Unit Price");
        assertThat(id.isComponentQuoted(0)).isTrue();
    }

    @Test
    void singleQuotedTextParses_withTheQuoteDoubledInside() throws Exception {
        SqlBasicCall call = (SqlBasicCall) parse("\"status\" = 'O''Brien'");
        assertThat(((SqlLiteral) call.operand(1)).getValueAs(String.class)).isEqualTo("O'Brien");
    }

    @Test
    void parsedDateLiteralsAreUntypedUntilValidation() throws Exception {
        // So a dialect's unparseDateTimeLiteral hook only applies to SQL that RelToSqlConverter writes,
        // never to parsed SQL unparsed directly.
        SqlNode literal = ((SqlBasicCall) parse("\"d\" >= DATE '2026-11-01'")).operand(1);
        assertThat(literal).isNotInstanceOf(org.apache.calcite.sql.SqlDateLiteral.class);
    }

    @Test
    void criteriaOperatorsParseOnceTheColumnIsInPlace() throws Exception {
        assertThat(parse("\"total\" BETWEEN 50 AND 100").getKind()).isEqualTo(SqlKind.BETWEEN);
        assertThat(parse("\"status\" IN ('A', 'B')").getKind()).isEqualTo(SqlKind.IN);
        assertThat(parse("\"status\" NOT IN ('A')").getKind()).isEqualTo(SqlKind.NOT_IN);
        assertThat(parse("\"name\" LIKE 'A%'").getKind()).isEqualTo(SqlKind.LIKE);
        assertThat(parse("\"status\" IS NULL").getKind()).isEqualTo(SqlKind.IS_NULL);
        // Keywords are case-insensitive.
        assertThat(parse("\"total\" between 50 and 100").getKind()).isEqualTo(SqlKind.BETWEEN);
    }

    @Test
    void anOperatorWithoutItsLeftOperandDoesNotParse() {
        // The grid's implied operand (">= 100") has to be supplied before parsing.
        assertThatThrownBy(() -> parse(">= 100")).isInstanceOf(SqlParseException.class);
    }

    @Test
    void parseErrorsCarryLineAndColumn() {
        assertThatThrownBy(() -> parse("\"total\" >= (100"))
                .isInstanceOfSatisfying(SqlParseException.class, e -> {
                    assertThat(e.getPos().getLineNum()).isEqualTo(1);
                    assertThat(e.getPos().getColumnNum()).isPositive();
                });
    }
}
