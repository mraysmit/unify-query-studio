package com.unify.querystudio.design;

import java.util.List;

/**
 * A table or saved query on the canvas. The alias is how the design refers to it: the table's own
 * name for the first copy, {@code Customers_1} for a second copy, as in Access; a saved query's alias
 * is its name.
 *
 * @param table the qualified name of the table (schema parts first); {@code ["queries", id]} for a
 *              saved query
 */
public record Source(String alias, List<String> table) {

    public Source {
        table = List.copyOf(table);
    }

    public static Source of(String table) {
        return new Source(table, List.of(table));
    }

    /** A saved query, which the {@code queries} schema names by its ID. */
    public boolean savedQuery() {
        return table.size() == 2 && table.getFirst().equals(com.unify.querystudio.compiler.SavedQuerySchema.NAME);
    }

    boolean aliased() {
        return !alias.equals(table.getLast());
    }
}
