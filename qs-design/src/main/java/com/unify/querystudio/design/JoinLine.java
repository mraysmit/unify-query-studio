package com.unify.querystudio.design;

import org.apache.calcite.sql.JoinType;

/**
 * A join line between two fields on the canvas. {@code LEFT} keeps every row of the left source,
 * {@code RIGHT} every row of the right source (Access's Join Properties options 2 and 3); the left
 * source is the one the user dragged from.
 */
public record JoinLine(String leftSource, String leftColumn, String rightSource, String rightColumn, JoinType type) {

    public JoinLine {
        if (type != JoinType.INNER && type != JoinType.LEFT && type != JoinType.RIGHT) {
            throw new IllegalArgumentException("A join line is INNER, LEFT or RIGHT, as in Access: " + type);
        }
    }

    boolean outer() {
        return type != JoinType.INNER;
    }

    /** For an outer join: the source showing only matching rows, where Access's arrow points. */
    String head() {
        return type == JoinType.LEFT ? rightSource : leftSource;
    }

    /** For an outer join: the source whose rows are all kept. */
    String tail() {
        return type == JoinType.LEFT ? leftSource : rightSource;
    }

    boolean touches(String source) {
        return leftSource.equals(source) || rightSource.equals(source);
    }

    String other(String source) {
        return leftSource.equals(source) ? rightSource : leftSource;
    }
}
