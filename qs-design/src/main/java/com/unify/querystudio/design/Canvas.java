package com.unify.querystudio.design;

import com.unify.querystudio.compiler.SavedQuerySchema;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.mapping.IntPair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Canvas edits that follow Access's rules and need the catalog. Reads only Calcite's model of the
 * connection: row types, primary keys as statistic keys, and foreign keys as referential constraints.
 */
public final class Canvas {

    private static final SqlTypeFactoryImpl TYPES = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);

    private Canvas() {
    }

    /**
     * Adds a table or view to the canvas, as Access does when it is added from the Add Tables pane:
     * <ul>
     *   <li>the first copy is named after the table; further copies take the first free name of
     *       {@code Customers_1}, {@code Customers_2}…;</li>
     *   <li>the first copy is joined to each first copy already on the canvas: one inner join per
     *       foreign-key column, drawn from the referenced table; between tables with no foreign key,
     *       Access's AutoJoin rule joins fields of the same name and type where one side is the
     *       primary key. AutoJoin is never used with a view;</li>
     *   <li>a further copy draws no joins: it is added for a self-join or another role, which the
     *       user joins by hand.</li>
     * </ul>
     *
     * @throws DesignException if the connection has no such table
     */
    public static DesignView addSource(DesignView design, SchemaPlus schema, String table) {
        Table added = schema.tables().get(table);
        if (added == null) {
            throw new DesignException("There is no table or view named " + table + ".", null);
        }
        String alias = freeAlias(design, table);
        List<Source> sources = new ArrayList<>(design.sources());
        sources.add(new Source(alias, List.of(table)));
        List<JoinLine> joins = new ArrayList<>(design.joins());
        if (alias.equals(table)) {
            for (Source existing : design.sources()) {
                String other = existing.table().getLast();
                if (existing.savedQuery() || existing.aliased() || other.equals(table)) {
                    continue;
                }
                Table otherTable = schema.tables().get(other);
                if (otherTable == null) {
                    continue;
                }
                List<JoinLine> keyed = foreignKeyJoins(table, added, other, otherTable);
                joins.addAll(keyed.isEmpty() ? autoJoins(table, added, other, otherTable) : keyed);
            }
        }
        return new DesignView(sources, joins, design.columns(), design.totals(), design.uniqueValues(),
                design.topValues(), design.outputAllFields());
    }

    /**
     * Adds a saved query to the canvas as a source, under its name ({@code Name_1} for a further
     * copy). It draws no joins: Access never auto-joins queries, and a query has no keys.
     *
     * @param id   the saved query's ID, which the {@code queries} schema knows it by
     * @param name the saved query's name, shown on its card
     * @throws DesignException if the user has no such saved query on this connection
     */
    public static DesignView addSavedQuery(DesignView design, SchemaPlus schema, String id, String name) {
        SchemaPlus queries = schema.subSchemas().get(SavedQuerySchema.NAME);
        if (queries == null || queries.tables().get(id) == null) {
            throw new DesignException("There is no saved query named " + name + ".", null);
        }
        List<Source> sources = new ArrayList<>(design.sources());
        sources.add(new Source(freeAlias(design, name), List.of(SavedQuerySchema.NAME, id)));
        return new DesignView(sources, design.joins(), design.columns(), design.totals(), design.uniqueValues(),
                design.topValues(), design.outputAllFields());
    }

    /** The name itself, or the first free one of {@code name_1}, {@code name_2}… */
    private static String freeAlias(DesignView design, String name) {
        Set<String> taken = new HashSet<>();
        design.sources().forEach(s -> taken.add(s.alias()));
        String alias = name;
        for (int n = 1; taken.contains(alias); n++) {
            alias = name + "_" + n;
        }
        return alias;
    }

    /** Foreign keys in either direction, one line per column pair, from the referenced table. */
    private static List<JoinLine> foreignKeyJoins(String a, Table aTable, String b, Table bTable) {
        List<JoinLine> joins = new ArrayList<>();
        joins.addAll(referencing(a, aTable, b, bTable));
        joins.addAll(referencing(b, bTable, a, aTable));
        return joins;
    }

    /** Joins for {@code from}'s foreign keys that reference {@code to}. */
    private static List<JoinLine> referencing(String from, Table fromTable, String to, Table toTable) {
        List<JoinLine> joins = new ArrayList<>();
        List<RelReferentialConstraint> constraints = fromTable.getStatistic().getReferentialConstraints();
        if (constraints == null) {
            return joins;
        }
        List<RelDataTypeField> fromFields = fields(fromTable);
        List<RelDataTypeField> toFields = fields(toTable);
        for (RelReferentialConstraint fk : constraints) {
            if (!fk.getTargetQualifiedName().getLast().equals(to)) {
                continue;
            }
            for (IntPair pair : fk.getColumnPairs()) {
                joins.add(new JoinLine(to, toFields.get(pair.target).getName(),
                        from, fromFields.get(pair.source).getName(), JoinType.INNER));
            }
        }
        return joins;
    }

    /** Access's AutoJoin: same name, same type, one side the primary key; tables only. */
    private static List<JoinLine> autoJoins(String added, Table addedTable, String existing, Table existingTable) {
        List<JoinLine> joins = new ArrayList<>();
        if (addedTable.getJdbcTableType() != Schema.TableType.TABLE
                || existingTable.getJdbcTableType() != Schema.TableType.TABLE) {
            return joins;
        }
        List<RelDataTypeField> existingFields = fields(existingTable);
        for (RelDataTypeField field : fields(addedTable)) {
            RelDataTypeField match = existingFields.stream()
                    .filter(f -> f.getName().equals(field.getName())).findFirst().orElse(null);
            if (match == null || match.getType().getSqlTypeName() != field.getType().getSqlTypeName()) {
                continue;
            }
            if (isKey(existingTable, match.getIndex())) {
                joins.add(new JoinLine(existing, match.getName(), added, field.getName(), JoinType.INNER));
            } else if (isKey(addedTable, field.getIndex())) {
                joins.add(new JoinLine(added, field.getName(), existing, match.getName(), JoinType.INNER));
            }
        }
        return joins;
    }

    /** The field alone is the table's primary key. */
    private static boolean isKey(Table table, int field) {
        List<ImmutableBitSet> keys = table.getStatistic().getKeys();
        return keys != null && keys.contains(ImmutableBitSet.of(field));
    }

    private static List<RelDataTypeField> fields(Table table) {
        RelDataType rowType = table.getRowType(TYPES);
        return rowType.getFieldList();
    }
}
