package com.unify.querystudio.design;

/**
 * The design cannot become valid SQL. It names the design element at fault so the designer can mark
 * it: a grid column ({@link #column()}, 0-based) or one of its criteria cells (with
 * {@link #criteriaRow()}, 0-based), a source by alias ({@link #source()}), or a join line by its
 * position in the design ({@link #join()}). All are null for the design as a whole.
 */
public final class DesignException extends RuntimeException {

    private final Integer column;
    private final Integer criteriaRow;
    private final String source;
    private final Integer join;

    private DesignException(String message, Integer column, Integer criteriaRow, String source, Integer join,
                            Throwable cause) {
        super(message, cause);
        this.column = column;
        this.criteriaRow = criteriaRow;
        this.source = source;
        this.join = join;
    }

    public DesignException(String message, Integer column, Integer criteriaRow, Throwable cause) {
        this(message, column, criteriaRow, null, null, cause);
    }

    public DesignException(String message, Integer column) {
        this(message, column, null, null);
    }

    public static DesignException atSource(String message, String alias, Throwable cause) {
        return new DesignException(message, null, null, alias, null, cause);
    }

    public static DesignException atJoin(String message, int join, Throwable cause) {
        return new DesignException(message, null, null, null, join, cause);
    }

    public Integer column() {
        return column;
    }

    public Integer criteriaRow() {
        return criteriaRow;
    }

    public String source() {
        return source;
    }

    public Integer join() {
        return join;
    }
}
