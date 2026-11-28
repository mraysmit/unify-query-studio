package com.unify.querystudio.compiler;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Schema drift: how the catalog has changed since a query was saved, judged only by the fields the
 * query uses. Tables and fields that were added are not drift.
 */
public final class Drift {

    public enum Problem { TABLE_MISSING, FIELD_MISSING, TYPE_CHANGED }

    /**
     * One change: {@code column} is null for a missing table; {@code savedType} is the field's type
     * when saved, {@code currentType} its type now (null if it is missing).
     */
    public record Change(List<String> table, String column, Problem problem, String savedType, String currentType) {

        public Change {
            table = List.copyOf(table);
        }
    }

    private Drift() {
    }

    public static List<Change> check(List<FieldReference> references, SchemaPlus schema) {
        JavaTypeFactoryImpl types = new JavaTypeFactoryImpl();
        List<Change> changes = new ArrayList<>();
        Set<List<String>> missingTables = new LinkedHashSet<>();
        for (FieldReference ref : references) {
            Table table = table(schema, ref.table());
            if (table == null) {
                if (missingTables.add(ref.table())) {
                    changes.add(new Change(ref.table(), null, Problem.TABLE_MISSING, null, null));
                }
                continue;
            }
            RelDataTypeField field = table.getRowType(types).getField(ref.column(), true, false);
            if (field == null) {
                changes.add(new Change(ref.table(), ref.column(), Problem.FIELD_MISSING, ref.type(), null));
            } else if (!field.getType().getSqlTypeName().getName().equals(ref.type())) {
                changes.add(new Change(ref.table(), ref.column(), Problem.TYPE_CHANGED, ref.type(),
                        field.getType().getSqlTypeName().getName()));
            }
        }
        return changes;
    }

    private static Table table(SchemaPlus schema, List<String> name) {
        SchemaPlus s = schema;
        for (String part : name.subList(0, name.size() - 1)) {
            s = s.subSchemas().get(part);
            if (s == null) {
                return null;
            }
        }
        return s.tables().get(name.getLast());
    }
}
