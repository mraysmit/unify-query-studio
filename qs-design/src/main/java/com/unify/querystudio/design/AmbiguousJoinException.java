package com.unify.querystudio.design;

/** The join lines form a graph Access calls ambiguous; {@link #table()} is where the conflict meets. */
public final class AmbiguousJoinException extends RuntimeException {

    /** Access's own wording (Jet error 3258). */
    public static final String ACCESS_MESSAGE = "The SQL statement could not be executed because it contains ambiguous "
            + "outer joins. To force one of the joins to be performed first, create a separate query that performs "
            + "the first join and then include that query in your SQL statement.";

    private final String table;

    public AmbiguousJoinException(String table) {
        super(ACCESS_MESSAGE);
        this.table = table;
    }

    public String table() {
        return table;
    }
}
