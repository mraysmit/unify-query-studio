package com.unify.querystudio.design;

import org.apache.calcite.rel.RelFieldCollation.Direction;

import java.util.List;

/**
 * One column of the design grid.
 *
 * @param table      the Table row: the source alias, or {@code null} for an expression column
 * @param field      the field name, {@code "*"} for all fields, or {@code null} for an expression column
 * @param expression the Field cell of a calculated column, in standard SQL; its name is {@code alias}
 * @param alias      the Alias row; {@code null} for the default name
 * @param total      the Total row (used only when Totals is on)
 * @param sort       the Sort row; {@code null} when not sorted
 * @param show       the Show row
 * @param criteria   the Criteria row followed by the "or" rows, in standard SQL with the column left
 *                   out ({@link CellSql}); blank cells allowed
 */
public record GridColumn(String table, String field, String expression, String alias, Total total,
                         Direction sort, boolean show, List<String> criteria) {

    public GridColumn {
        criteria = List.copyOf(criteria);
    }

    public static GridColumn field(String table, String field) {
        return new GridColumn(table, field, null, null, Total.GROUP_BY, null, true, List.of());
    }

    public static GridColumn allFields(String table) {
        return field(table, "*");
    }

    public static GridColumn expression(String expression) {
        return new GridColumn(null, null, expression, null, Total.GROUP_BY, null, true, List.of());
    }

    public GridColumn alias(String alias) {
        return new GridColumn(table, field, expression, alias, total, sort, show, criteria);
    }

    public GridColumn total(Total total) {
        return new GridColumn(table, field, expression, alias, total, sort, show, criteria);
    }

    public GridColumn sort(Direction sort) {
        return new GridColumn(table, field, expression, alias, total, sort, show, criteria);
    }

    public GridColumn show(boolean show) {
        return new GridColumn(table, field, expression, alias, total, sort, show, criteria);
    }

    public GridColumn criteria(String... rows) {
        return new GridColumn(table, field, expression, alias, total, sort, show, List.of(rows));
    }

    boolean allFields() {
        return "*".equals(field);
    }
}
