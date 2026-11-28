package com.unify.querystudio.compiler;

import java.util.List;

/**
 * A field a query uses, with its type when the query was compiled; together they are the query's
 * catalog fingerprint, saved with each version and compared with the catalog when it is opened.
 *
 * @param table  the table's qualified name
 * @param column the field's name
 * @param type   the Calcite {@code SqlTypeName} of the field
 */
public record FieldReference(List<String> table, String column, String type) {

    public FieldReference {
        table = List.copyOf(table);
    }
}
