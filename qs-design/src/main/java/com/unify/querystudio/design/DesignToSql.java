package com.unify.querystudio.design;

import com.unify.querystudio.compiler.NamedParameters;

import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the canonical query (a Calcite {@link SqlSelect}) from the design view, following the
 * rules of the Access query designer's grid (docs/access-reference.md). Cells hold standard SQL
 * ({@link CellSql}).
 * <ul>
 *   <li>shown columns become the select list, in grid order; output names default to Access's
 *       {@code SumOfField} for aggregates and {@code Expr1}, {@code Expr2}… for calculated fields;</li>
 *   <li>criteria are ANDed across a row and the rows are ORed; with Totals on, criteria on a Where
 *       column go to WHERE and all others to HAVING;</li>
 *   <li>Group By columns form GROUP BY; sorted columns form ORDER BY, left to right.</li>
 * </ul>
 */
public final class DesignToSql {

    private static final SqlParserPos POS = SqlParserPos.ZERO;

    private DesignToSql() {
    }

    public static SqlSelect toSelect(DesignView design) {
        List<GridColumn> columns = design.columns();
        List<SqlNode> values = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int unnamedExpressions = 0;

        for (int i = 0; i < columns.size(); i++) {
            GridColumn c = columns.get(i);
            check(design, c, i);
            SqlNode expression;
            String name = c.alias();
            if (c.expression() != null) {
                try {
                    expression = CellSql.expression(c.expression());
                } catch (CriteriaException e) {
                    throw new DesignException(e.getMessage(), i, null, e);
                }
                if (name == null) {
                    name = "Expr" + (++unnamedExpressions);
                }
            } else if (c.allFields()) {
                expression = SqlIdentifier.star(List.of(c.table(), ""), POS, List.of(POS, POS));
            } else {
                expression = new SqlIdentifier(List.of(c.table(), c.field()), POS);
            }
            if (design.totals() && c.total() instanceof Total.Aggregate aggregate) {
                expression = aggregate.function().createCall(POS, expression);
                if (name == null) {
                    name = aggregate.accessName() + "Of" + c.field();
                }
            }
            values.add(expression);
            names.add(name);
        }

        SqlNodeList select = new SqlNodeList(POS);
        if (design.outputAllFields()) {
            if (design.totals()) {
                throw new DesignException("Output All Fields can't be used in a totals query; add the fields "
                        + "to the grid instead.", null);
            }
            select.add(SqlIdentifier.star(POS));
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).show()) {
                select.add(names.get(i) == null ? values.get(i)
                        : SqlStdOperatorTable.AS.createCall(POS, values.get(i), new SqlIdentifier(names.get(i), POS)));
            }
        }

        Criteria criteria = criteria(design, values);

        SqlNodeList groupBy = null;
        if (design.totals()) {
            Set<String> seen = new LinkedHashSet<>();
            groupBy = new SqlNodeList(POS);
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).total() instanceof Total.GroupBy && seen.add(values.get(i).toString())) {
                    groupBy.add(values.get(i));
                }
            }
            if (groupBy.isEmpty()) {
                groupBy = null;
            }
        }

        SqlNodeList orderBy = new SqlNodeList(POS);
        for (int i = 0; i < columns.size(); i++) {
            Direction sort = columns.get(i).sort();
            if (sort != null) {
                orderBy.add(sort == Direction.DESCENDING ? SqlStdOperatorTable.DESC.createCall(POS, values.get(i)) : values.get(i));
            }
        }

        SqlNodeList keywords = design.uniqueValues()
                ? SqlNodeList.of(SqlSelectKeyword.DISTINCT.symbol(POS)) : SqlNodeList.EMPTY;
        return new SqlSelect(POS, keywords, select, JoinTree.from(design.sources(), design.joins()),
                criteria.where(), groupBy, criteria.having(), null, null,
                orderBy.isEmpty() ? null : orderBy, null,
                design.topValues() == null ? null : SqlLiteral.createExactNumeric(design.topValues().toString(), POS), null);
    }

    /** The canonical SQL text, in Calcite's own dialect. */
    public static String toSql(DesignView design) {
        return NamedParameters.unmark(toSelect(design).toSqlString(
                        c -> c.withDialect(CalciteSqlDialect.DEFAULT).withClauseStartsLine(true)), NamedParameters::name)
                .replace("\r\n", "\n").strip();
    }

    private static void check(DesignView design, GridColumn c, int index) {
        if (design.totals() && c.total() instanceof Total.Where && c.show()) {
            throw new DesignException("Query Studio can't display the field for which you entered Where in the Total row.", index);
        }
        if (c.allFields()) {
            if (c.sort() != null) {
                throw new DesignException("You can't sort on the asterisk (*). Add the field you want to sort on to the grid "
                        + "and clear its Show check box.", index);
            }
            if (c.criteria().stream().anyMatch(s -> s != null && !s.isBlank())) {
                throw new DesignException("You can't specify criteria for the asterisk (*). Add the field to the grid "
                        + "and clear its Show check box.", index);
            }
            if (design.totals()) {
                throw new DesignException("You can't calculate totals on the asterisk (*).", index);
            }
        }
    }

    private record Criteria(SqlNode where, SqlNode having) {
    }

    /**
     * Criteria rows: AND across a row, OR between rows; WHERE or HAVING per Access's Total rules.
     * As in Access, WHERE and HAVING are built independently: the Where cells of each row are OR-ed
     * into WHERE and the grouped cells into HAVING, so a row's two halves are not kept together.
     */
    private static Criteria criteria(DesignView design, List<SqlNode> values) {
        List<GridColumn> columns = design.columns();
        int rows = columns.stream().mapToInt(c -> c.criteria().size()).max().orElse(0);
        List<SqlNode> whereRows = new ArrayList<>();
        List<SqlNode> havingRows = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<SqlNode> where = new ArrayList<>();
            List<SqlNode> having = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                GridColumn c = columns.get(i);
                String cell = r < c.criteria().size() ? c.criteria().get(r) : null;
                SqlNode predicate;
                try {
                    predicate = CellSql.predicate(cell, values.get(i));
                } catch (CriteriaException e) {
                    throw new DesignException(e.getMessage(), i, r, e);
                }
                if (predicate == null) {
                    continue;
                }
                boolean toWhere = !design.totals() || c.total() instanceof Total.Where;
                (toWhere ? where : having).add(predicate);
            }
            if (!where.isEmpty()) whereRows.add(and(where));
            if (!having.isEmpty()) havingRows.add(and(having));
        }
        return new Criteria(or(whereRows), or(havingRows));
    }

    private static SqlNode and(List<SqlNode> nodes) {
        SqlNode result = nodes.getFirst();
        for (int i = 1; i < nodes.size(); i++) {
            result = SqlStdOperatorTable.AND.createCall(POS, result, nodes.get(i));
        }
        return result;
    }

    private static SqlNode or(List<SqlNode> nodes) {
        if (nodes.isEmpty()) {
            return null;
        }
        SqlNode result = nodes.getFirst();
        for (int i = 1; i < nodes.size(); i++) {
            result = SqlStdOperatorTable.OR.createCall(POS, result, nodes.get(i));
        }
        return result;
    }
}
