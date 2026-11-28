package com.unify.querystudio.design;

import java.util.List;

/**
 * The design view: what the canvas and grid hold. It is derived from and turned back into canonical
 * SQL; it is never stored or compiled directly.
 *
 * @param totals       the toolbar's Totals toggle (shows the Total row)
 * @param uniqueValues the Unique Values property (SELECT DISTINCT)
 * @param topValues    the Top Values property: return the first n rows in sort order
 *                     ({@code FETCH NEXT n ROWS ONLY}); null for All
 * @param outputAllFields the Output All Fields property: every field of every source is output
 *                     ({@code SELECT *}), before the grid's shown fields
 */
public record DesignView(List<Source> sources, List<JoinLine> joins, List<GridColumn> columns,
                         boolean totals, boolean uniqueValues, Integer topValues, boolean outputAllFields) {

    public DesignView(List<Source> sources, List<JoinLine> joins, List<GridColumn> columns,
                      boolean totals, boolean uniqueValues, Integer topValues) {
        this(sources, joins, columns, totals, uniqueValues, topValues, false);
    }

    public DesignView {
        if (topValues != null && topValues < 1) {
            throw new IllegalArgumentException("Top Values must be a whole number greater than 0, or All.");
        }
        sources = List.copyOf(sources);
        joins = List.copyOf(joins);
        columns = List.copyOf(columns);
    }

    public static DesignView of(List<Source> sources, List<JoinLine> joins, List<GridColumn> columns) {
        return new DesignView(sources, joins, columns, false, false, null);
    }

    public DesignView withTotals(boolean totals) {
        return new DesignView(sources, joins, columns, totals, uniqueValues, topValues, outputAllFields);
    }

    public DesignView withUniqueValues(boolean uniqueValues) {
        return new DesignView(sources, joins, columns, totals, uniqueValues, topValues, outputAllFields);
    }

    public DesignView withTopValues(Integer topValues) {
        return new DesignView(sources, joins, columns, totals, uniqueValues, topValues, outputAllFields);
    }

    public DesignView withOutputAllFields(boolean outputAllFields) {
        return new DesignView(sources, joins, columns, totals, uniqueValues, topValues, outputAllFields);
    }
}
