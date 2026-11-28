package com.unify.querystudio.server;

import com.unify.querystudio.design.DesignView;
import com.unify.querystudio.design.GridColumn;
import com.unify.querystudio.design.JoinLine;
import com.unify.querystudio.design.Source;
import com.unify.querystudio.design.Total;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The design view as the API exchanges it (technical design, API contract). Columns carry IDs so
 * that criteria rows and diagnostics can name a grid cell.
 */
public record DesignJson(List<SourceJson> sources, List<JoinJson> joins, List<ColumnJson> columns,
                         List<Map<String, String>> criteria, Boolean totals, Boolean uniqueValues, Integer topValues,
                         Boolean outputAllFields) {

    public record SourceJson(String alias, List<String> table) {
    }

    /** {@code type} is a Calcite {@code JoinType} name: INNER, LEFT or RIGHT. */
    public record JoinJson(String type, EndJson left, EndJson right) {
    }

    public record EndJson(String source, String column) {
    }

    /**
     * A grid column: a field of a table ({@code "*"} for all fields), or an expression in Access syntax.
     * {@code total} is GROUP_BY (default), WHERE, EXPRESSION, SUM, AVG, MIN, MAX, COUNT, STDEV or VAR;
     * {@code sort} is ASC or DESC.
     */
    public record ColumnJson(String id, String table, String field, String expression, String alias,
                             String total, String sort, Boolean show) {
    }

    /** The design view; IllegalArgumentException names what is malformed. */
    public DesignView toDesignView() {
        List<Source> s = list(sources).stream().map(x -> new Source(x.alias(), x.table())).toList();
        List<JoinLine> j = list(joins).stream().map(x -> new JoinLine(x.left().source(), x.left().column(),
                x.right().source(), x.right().column(), JoinType.valueOf(x.type()))).toList();
        List<Map<String, String>> rows = list(criteria);
        List<GridColumn> c = new ArrayList<>();
        for (ColumnJson x : list(columns)) {
            GridColumn g = x.expression() != null ? GridColumn.expression(x.expression())
                    : "*".equals(x.field()) ? GridColumn.allFields(x.table())
                    : GridColumn.field(x.table(), x.field());
            if (x.alias() != null) g = g.alias(x.alias());
            g = g.total(total(x.total()));
            if (x.sort() != null) g = g.sort(sort(x.sort()));
            if (x.show() != null) g = g.show(x.show());
            if (!rows.isEmpty()) {
                g = g.criteria(rows.stream().map(r -> r.getOrDefault(x.id(), "")).toArray(String[]::new));
            }
            c.add(g);
        }
        return new DesignView(s, j, c, Boolean.TRUE.equals(totals), Boolean.TRUE.equals(uniqueValues), topValues,
                Boolean.TRUE.equals(outputAllFields));
    }

    /** The ID of the column at this grid position. */
    public String columnId(int index) {
        return columns.get(index).id();
    }

    /** A design view as JSON; columns get the IDs c1, c2, … in grid order. */
    public static DesignJson from(DesignView view) {
        List<SourceJson> s = view.sources().stream().map(x -> new SourceJson(x.alias(), x.table())).toList();
        List<JoinJson> j = view.joins().stream().map(x -> new JoinJson(x.type().name(),
                new EndJson(x.leftSource(), x.leftColumn()), new EndJson(x.rightSource(), x.rightColumn()))).toList();
        List<ColumnJson> c = new ArrayList<>();
        int rowCount = view.columns().stream().mapToInt(x -> x.criteria().size()).max().orElse(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            rows.add(new java.util.LinkedHashMap<>());
        }
        for (int i = 0; i < view.columns().size(); i++) {
            GridColumn g = view.columns().get(i);
            String id = "c" + (i + 1);
            c.add(new ColumnJson(id, g.table(), g.field(), g.expression(), g.alias(), totalName(g.total()),
                    g.sort() == null ? null : g.sort() == Direction.DESCENDING ? "DESC" : "ASC", g.show()));
            for (int r = 0; r < g.criteria().size(); r++) {
                if (!g.criteria().get(r).isBlank()) {
                    rows.get(r).put(id, g.criteria().get(r));
                }
            }
        }
        return new DesignJson(s, j, c, rows, view.totals(), view.uniqueValues(), view.topValues(),
                view.outputAllFields());
    }

    private static String totalName(Total total) {
        return switch (total) {
            case Total.GroupBy g -> "GROUP_BY";
            case Total.Where w -> "WHERE";
            case Total.Expression e -> "EXPRESSION";
            case Total.Aggregate a -> switch (a.function().getKind()) {
                case STDDEV_SAMP -> "STDEV";
                case VAR_SAMP -> "VAR";
                default -> a.function().getKind().name();
            };
        };
    }

    private static Total total(String name) {
        return switch (name == null ? "GROUP_BY" : name) {
            case "GROUP_BY" -> Total.GROUP_BY;
            case "WHERE" -> Total.WHERE;
            case "EXPRESSION" -> Total.EXPRESSION;
            case "SUM" -> Total.aggregate(SqlStdOperatorTable.SUM);
            case "AVG" -> Total.aggregate(SqlStdOperatorTable.AVG);
            case "MIN" -> Total.aggregate(SqlStdOperatorTable.MIN);
            case "MAX" -> Total.aggregate(SqlStdOperatorTable.MAX);
            case "COUNT" -> Total.aggregate(SqlStdOperatorTable.COUNT);
            case "STDEV" -> Total.aggregate(SqlStdOperatorTable.STDDEV_SAMP);
            case "VAR" -> Total.aggregate(SqlStdOperatorTable.VAR_SAMP);
            default -> throw new IllegalArgumentException("Unknown total: " + name);
        };
    }

    private static Direction sort(String name) {
        return switch (name) {
            case "ASC" -> Direction.ASCENDING;
            case "DESC" -> Direction.DESCENDING;
            default -> throw new IllegalArgumentException("Unknown sort: " + name);
        };
    }

    private static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : list;
    }
}
