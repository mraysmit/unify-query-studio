package com.unify.querystudio.design;

import com.unify.querystudio.compiler.NamedParameters;
import com.unify.querystudio.compiler.ParameterException;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.fun.SqlBetweenOperator;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.SqlAggFunction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rebuilds the design view from canonical SQL, as Access rebuilds Design view from a query's SQL.
 * Criteria and sorts on fields not in the select list get hidden columns, as Access shows them.
 * SQL the designer cannot show (unions, unequal joins, derived tables, window functions) stays
 * SQL-only, with the reason.
 *
 * <p>Predicates are assigned to grid columns left to right, so a design turned into SQL and back
 * produces the same SQL.
 */
public final class SqlToDesign {

    /** The outcome: a design view, or the reason the query can only be shown as SQL. */
    public sealed interface Result permits Representable, SqlOnly {
    }

    public record Representable(DesignView design) implements Result {
    }

    public record SqlOnly(String reason) implements Result {
    }

    private static final SqlParser.Config CANONICAL = SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true);

    private static final Map<String, SqlAggFunction> AGGREGATES = Map.of(
            "SUM", SqlStdOperatorTable.SUM, "AVG", SqlStdOperatorTable.AVG, "MIN", SqlStdOperatorTable.MIN,
            "MAX", SqlStdOperatorTable.MAX, "COUNT", SqlStdOperatorTable.COUNT,
            "STDDEV_SAMP", SqlStdOperatorTable.STDDEV_SAMP, "VAR_SAMP", SqlStdOperatorTable.VAR_SAMP);

    private static final EnumSet<SqlKind> SET_OPERATIONS = EnumSet.of(SqlKind.UNION, SqlKind.INTERSECT, SqlKind.EXCEPT);

    private SqlToDesign() {
    }

    public static Result toDesign(String sql) {
        SqlNode node;
        try {
            NamedParameters.Marked marked = NamedParameters.mark(sql);
            node = marked.stable(SqlParser.create(marked.sql(), CANONICAL).parseQuery());
        } catch (SqlParseException e) {
            return new SqlOnly(e.getMessage().lines().findFirst().orElse("The SQL cannot be read."));
        } catch (ParameterException e) {
            return new SqlOnly(e.getMessage());
        }
        try {
            return new Representable(new Builder().build(node));
        } catch (NotRepresentable e) {
            return new SqlOnly(e.getMessage());
        }
    }

    private static final class NotRepresentable extends RuntimeException {
        NotRepresentable(String reason) {
            super(reason);
        }
    }

    /** One design under construction. */
    private static final class Builder {
        private final List<Source> sources = new ArrayList<>();
        private final List<JoinLine> joins = new ArrayList<>();
        private final List<GridColumn> columns = new ArrayList<>();
        /** Each column's SQL value (field, aggregate or expression), as text, for matching. */
        private final List<String> values = new ArrayList<>();
        private final Map<Integer, TreeMap<Integer, String>> cells = new HashMap<>();
        private boolean totals;
        private boolean outputAllFields;
        private int expressionNames;

        DesignView build(SqlNode node) {
            SqlNodeList orderList = null;
            SqlNode offset = null;
            SqlNode fetch = null;
            if (node instanceof SqlOrderBy orderBy) {
                orderList = orderBy.orderList;
                offset = orderBy.offset;
                fetch = orderBy.fetch;
                node = orderBy.query;
            }
            if (SET_OPERATIONS.contains(node.getKind())) {
                throw new NotRepresentable(node.getKind() + " queries can only be shown in SQL view.");
            }
            if (!(node instanceof SqlSelect select)) {
                throw new NotRepresentable("Only SELECT queries can be shown in Design view.");
            }
            if (orderList == null) {
                orderList = select.getOrderList();
                offset = select.getOffset();
                fetch = select.getFetch();
            }
            if (offset != null) {
                throw new NotRepresentable("OFFSET can only be shown in SQL view.");
            }
            Integer topValues = null;
            if (fetch != null) {
                if (!(fetch instanceof SqlNumericLiteral n) || !n.isInteger() || n.intValue(false) < 1) {
                    throw new NotRepresentable("Top Values must be a whole number of rows to show this query in Design view.");
                }
                topValues = n.intValue(true);
            }
            if (select.getWindowList() != null && !select.getWindowList().isEmpty() || select.getQualify() != null) {
                throw new NotRepresentable("Window functions can only be shown in SQL view.");
            }
            rejectWindows(select.getSelectList());

            from(select.getFrom());
            totals = select.getGroup() != null || select.getHaving() != null
                    || select.getSelectList().stream().anyMatch(Builder::containsAggregate);

            for (SqlNode item : select.getSelectList()) {
                selectItem(item);
            }
            if (select.getGroup() != null) {
                for (SqlNode group : select.getGroup()) {
                    SqlNode g = qualify(group);
                    if (indexOf(text(g), c -> !(c.total() instanceof Total.Aggregate)) < 0) {
                        addHidden(g, Total.GROUP_BY);
                    }
                }
            }
            List<List<SqlNode>> whereRows = rows(select.getWhere());
            List<List<SqlNode>> havingRows = rows(select.getHaving());
            // As Access lays them out: WHERE's rows and HAVING's rows each start at the first criteria row.
            criteria(whereRows, true);
            criteria(havingRows, false);
            if (orderList != null) {
                sorts(orderList);
            }

            List<GridColumn> withCriteria = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                TreeMap<Integer, String> rows = cells.get(i);
                if (rows == null) {
                    withCriteria.add(columns.get(i));
                    continue;
                }
                String[] cellsInOrder = new String[rows.lastKey() + 1];
                for (int r = 0; r < cellsInOrder.length; r++) {
                    cellsInOrder[r] = rows.getOrDefault(r, "");
                }
                withCriteria.add(columns.get(i).criteria(cellsInOrder));
            }
            return new DesignView(sources, joins, withCriteria, totals, select.isDistinct(), topValues, outputAllFields);
        }

        // ---------------------------------------------------------------- FROM

        private void from(SqlNode node) {
            switch (node) {
                case SqlJoin join -> join(join);
                case SqlIdentifier id -> sources.add(new Source(id.names.getLast(), id.names));
                case SqlBasicCall as when as.getKind() == SqlKind.AS && as.operand(0) instanceof SqlIdentifier table ->
                        sources.add(new Source(((SqlIdentifier) as.operand(1)).getSimple(), table.names));
                default -> throw new NotRepresentable("Subqueries in FROM can only be shown in SQL view.");
            }
        }

        private void join(SqlJoin join) {
            from(join.getLeft());
            if (join.getRight() instanceof SqlJoin) {
                throw new NotRepresentable("Nested joins on the right can only be shown in SQL view.");
            }
            from(join.getRight());
            String right = sources.getLast().alias();
            JoinType type = join.getJoinType();
            if (type == JoinType.CROSS || type == JoinType.COMMA) {
                return;
            }
            if (join.isNatural() || join.getConditionType() != JoinConditionType.ON
                    || !(type == JoinType.INNER || type == JoinType.LEFT || type == JoinType.RIGHT)) {
                throw new NotRepresentable("Query Studio can't represent the join expression " + text(join) + " in Design view.");
            }
            for (SqlNode equality : conjuncts(join.getCondition())) {
                if (equality.getKind() != SqlKind.EQUALS
                        || !(((SqlCall) equality).operand(0) instanceof SqlIdentifier a && a.names.size() == 2)
                        || !(((SqlCall) equality).operand(1) instanceof SqlIdentifier b && b.names.size() == 2)) {
                    throw new NotRepresentable("Query Studio can't represent the join expression "
                            + text(join.getCondition()) + " in Design view.");
                }
                JoinType lineType = type == JoinType.INNER ? JoinType.INNER
                        // The joined (right) source is null-supplying for LEFT, preserved for RIGHT.
                        : (type == JoinType.LEFT) == !a.names.getFirst().equals(right) ? JoinType.LEFT : JoinType.RIGHT;
                joins.add(new JoinLine(a.names.get(0), a.names.get(1), b.names.get(0), b.names.get(1), lineType));
            }
        }

        // ---------------------------------------------------------------- SELECT

        private void selectItem(SqlNode item) {
            if (item instanceof SqlIdentifier star && star.isStar() && star.names.size() == 1) {
                // A bare * is Access's Output All Fields; Table.* is a grid column.
                outputAllFields = true;
                return;
            }
            SqlNode expression = item;
            String name = null;
            if (item.getKind() == SqlKind.AS) {
                expression = ((SqlCall) item).operand(0);
                name = ((SqlIdentifier) ((SqlCall) item).operand(1)).getSimple();
            }
            expression = qualify(expression);
            GridColumn column = column(expression, name);
            columns.add(column);
            values.add(text(expression));
        }

        /** The grid column showing {@code expression}, named {@code name} (null for the default). */
        private GridColumn column(SqlNode expression, String name) {
            if (expression instanceof SqlIdentifier id && id.isStar()) {
                return GridColumn.allFields(id.names.getFirst());
            }
            if (expression instanceof SqlIdentifier id && id.names.size() == 2) {
                GridColumn c = GridColumn.field(id.names.get(0), id.names.get(1));
                return name == null || name.equals(id.names.get(1)) ? c : c.alias(name);
            }
            SqlAggFunction aggregate = aggregateOfField(expression);
            if (aggregate != null) {
                SqlIdentifier field = (SqlIdentifier) ((SqlCall) expression).operand(0);
                Total.Aggregate total = (Total.Aggregate) Total.aggregate(aggregate);
                GridColumn c = GridColumn.field(field.names.get(0), field.names.get(1)).total(total);
                return name == null || name.equals(total.accessName() + "Of" + field.names.get(1)) ? c : c.alias(name);
            }
            String defaultName = "Expr" + (++expressionNames);
            GridColumn c = GridColumn.expression(CellSql.text(expression));
            if (name != null && !name.equals(defaultName)) {
                c = c.alias(name);
            }
            return totals && containsAggregate(expression) ? c.total(Total.EXPRESSION) : c;
        }

        private SqlNode qualify(SqlNode node) {
            if (node instanceof SqlIdentifier id && id.isStar() && id.names.size() == 1) {
                return SqlIdentifier.star(List.of(onlySource(), ""), SqlParserPos.ZERO,
                        List.of(SqlParserPos.ZERO, SqlParserPos.ZERO));
            }
            if (node instanceof SqlIdentifier id && id.isSimple() && id.isComponentQuoted(0)) {
                return new SqlIdentifier(List.of(onlySource(), id.getSimple()), SqlParserPos.ZERO);
            }
            if (node instanceof SqlCall call) {
                List<SqlNode> operands = call.getOperandList();
                for (int i = 0; i < operands.size(); i++) {
                    if (operands.get(i) != null) {
                        call.setOperand(i, qualify(operands.get(i)));
                    }
                }
            }
            return node;
        }

        private String onlySource() {
            if (sources.size() != 1) {
                throw new NotRepresentable("Qualify every field with its table name to show this query in Design view.");
            }
            return sources.getFirst().alias();
        }

        private static SqlAggFunction aggregateOfField(SqlNode node) {
            if (node instanceof SqlBasicCall call && call.operandCount() == 1 && call.getFunctionQuantifier() == null
                    && call.operand(0) instanceof SqlIdentifier field && field.names.size() == 2 && !field.isStar()) {
                return AGGREGATES.get(call.getOperator().getName().toUpperCase(Locale.ROOT));
            }
            return null;
        }

        private static boolean containsAggregate(SqlNode node) {
            boolean[] found = {false};
            node.accept(new SqlBasicVisitor<Void>() {
                @Override
                public Void visit(SqlCall call) {
                    if (AGGREGATES.containsKey(call.getOperator().getName().toUpperCase(Locale.ROOT))) {
                        found[0] = true;
                    }
                    return super.visit(call);
                }
            });
            return found[0];
        }

        private static void rejectWindows(SqlNodeList list) {
            list.accept(new SqlBasicVisitor<Void>() {
                @Override
                public Void visit(SqlCall call) {
                    if (call.getKind() == SqlKind.OVER) {
                        throw new NotRepresentable("Window functions can only be shown in SQL view.");
                    }
                    return super.visit(call);
                }
            });
        }

        // ---------------------------------------------------------------- criteria

        /** OR of ANDs: each disjunct is a criteria row, each conjunct a predicate. */
        private static List<List<SqlNode>> rows(SqlNode condition) {
            List<List<SqlNode>> rows = new ArrayList<>();
            if (condition != null) {
                for (SqlNode row : disjuncts(condition)) {
                    rows.add(conjuncts(row));
                }
            }
            return rows;
        }

        private void criteria(List<List<SqlNode>> rows, boolean where) {
            for (int r = 0; r < rows.size(); r++) {
                int last = -1;
                for (SqlNode predicate : rows.get(r)) {
                    Criterion c = criterion(qualify(predicate));
                    int index = place(c.target(), where, r, last);
                    TreeMap<Integer, String> rowsOfColumn = cells.computeIfAbsent(index, k -> new TreeMap<>());
                    rowsOfColumn.merge(r, c.cell(), (a, b) -> a + " AND " + b);
                    last = index;
                }
            }
        }

        /** The column a predicate's target belongs to: an existing one to the right, else a new hidden one. */
        private int place(SqlNode target, boolean where, int row, int last) {
            String value = text(target);
            for (int i = Math.max(last, 0); i < columns.size(); i++) {
                GridColumn c = columns.get(i);
                boolean free = !cells.containsKey(i) || !cells.get(i).containsKey(row) || i == last;
                boolean kind = !totals || (where == c.total() instanceof Total.Where);
                if (free && kind && values.get(i).equals(value) && !c.allFields()) {
                    return i;
                }
            }
            Total total = !totals ? Total.GROUP_BY
                    : where ? Total.WHERE
                    : aggregateOfField(target) != null ? Total.aggregate(aggregateOfField(target))
                    : containsAggregate(target) ? Total.EXPRESSION : Total.GROUP_BY;
            return addHidden(target, total);
        }

        private int addHidden(SqlNode target, Total total) {
            GridColumn c;
            if (target instanceof SqlIdentifier id && id.names.size() == 2) {
                c = GridColumn.field(id.names.get(0), id.names.get(1));
            } else if (aggregateOfField(target) != null) {
                SqlIdentifier field = (SqlIdentifier) ((SqlCall) target).operand(0);
                c = GridColumn.field(field.names.get(0), field.names.get(1));
            } else {
                c = GridColumn.expression(CellSql.text(target));
            }
            columns.add(c.total(total).show(false));
            values.add(text(target));
            return columns.size() - 1;
        }

        private record Criterion(SqlNode target, String cell) {
        }

        /** A predicate as its target and the criteria cell for it, with the target left out. */
        private static Criterion criterion(SqlNode predicate) {
            if (!(predicate instanceof SqlCall call)) {
                throw new NotRepresentable("The criteria " + text(predicate) + " cannot be shown in Design view.");
            }
            SqlNode target = call.operandCount() > 0 ? call.operand(0) : null;
            if (target == null || target instanceof org.apache.calcite.sql.SqlLiteral) {
                throw new NotRepresentable("The criteria " + text(predicate) + " cannot be shown in Design view.");
            }
            String cell = switch (call.getKind()) {
                case EQUALS -> CellSql.text(call.operand(1));
                case NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL ->
                        call.getOperator().getName() + " " + CellSql.text(call.operand(1));
                case IN, NOT_IN -> (call.getKind() == SqlKind.NOT_IN ? "NOT IN (" : "IN (")
                        + String.join(", ", ((SqlNodeList) call.operand(1)).stream().map(CellSql::text).toList()) + ")";
                case BETWEEN -> (((SqlBetweenOperator) call.getOperator()).isNegated() ? "NOT BETWEEN " : "BETWEEN ")
                        + CellSql.text(call.operand(1)) + " AND " + CellSql.text(call.operand(2));
                case LIKE -> (((SqlLikeOperator) call.getOperator()).isNegated() ? "NOT LIKE " : "LIKE ")
                        + CellSql.text(call.operand(1));
                case IS_NULL -> "IS NULL";
                case IS_NOT_NULL -> "IS NOT NULL";
                default -> throw new NotRepresentable("The criteria " + text(predicate) + " cannot be shown in Design view.");
            };
            return new Criterion(target, cell);
        }

        // ---------------------------------------------------------------- ORDER BY

        private void sorts(SqlNodeList orderList) {
            int last = -1;
            List<Integer> placed = new ArrayList<>();
            List<Direction> directions = new ArrayList<>();
            List<SqlNode> keys = new ArrayList<>();
            boolean inOrder = true;
            for (SqlNode item : orderList) {
                Direction direction = Direction.ASCENDING;
                SqlNode key = item;
                if (item.getKind() == SqlKind.DESCENDING) {
                    direction = Direction.DESCENDING;
                    key = ((SqlCall) item).operand(0);
                }
                if (key.getKind() == SqlKind.NULLS_FIRST || key.getKind() == SqlKind.NULLS_LAST) {
                    throw new NotRepresentable("NULLS FIRST and NULLS LAST can only be shown in SQL view.");
                }
                key = qualify(key);
                int index = indexOf(text(key), c -> true);
                if (index < 0 && key instanceof SqlIdentifier id && id.isSimple()) {
                    index = indexOfName(id.getSimple());
                }
                if (index <= last) {
                    inOrder = false;
                }
                last = Math.max(last, index);
                placed.add(index);
                directions.add(direction);
                keys.add(key);
            }
            for (int k = 0; k < keys.size(); k++) {
                int index = inOrder ? placed.get(k) : -1;
                if (index < 0) {
                    index = addHidden(keys.get(k), Total.GROUP_BY);
                }
                columns.set(index, columns.get(index).sort(directions.get(k)));
            }
        }

        private int indexOf(String value, java.util.function.Predicate<GridColumn> accept) {
            for (int i = 0; i < columns.size(); i++) {
                if (values.get(i).equals(value) && accept.test(columns.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        private int indexOfName(String name) {
            for (int i = 0; i < columns.size(); i++) {
                GridColumn c = columns.get(i);
                if (name.equals(c.alias())) {
                    return i;
                }
            }
            return -1;
        }

        // ---------------------------------------------------------------- helpers

        private static List<SqlNode> conjuncts(SqlNode node) {
            return flatten(node, SqlKind.AND);
        }

        private static List<SqlNode> disjuncts(SqlNode node) {
            return flatten(node, SqlKind.OR);
        }

        private static List<SqlNode> flatten(SqlNode node, SqlKind kind) {
            List<SqlNode> out = new ArrayList<>();
            if (node.getKind() == kind) {
                for (SqlNode operand : ((SqlCall) node).getOperandList()) {
                    out.addAll(flatten(operand, kind));
                }
            } else {
                out.add(node);
            }
            return out;
        }

        private static String text(SqlNode node) {
            return NamedParameters.unmark(node.toSqlString(c -> c.withDialect(CalciteSqlDialect.DEFAULT)), NamedParameters::name)
                    .replaceAll("\\s+", " ");
        }
    }
}
