package com.unify.querystudio.catalog;

import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.dialects.JdbcColumn;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.RelReferentialConstraintImpl;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.mapping.IntPair;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a database's catalog through standard JDBC metadata into a Calcite schema: one
 * {@link CatalogTable} per table or view, with primary keys as {@code Statistic} keys and foreign
 * keys as {@link RelReferentialConstraint}s. Database-specific behaviour comes from the
 * {@link DialectSupport}: column types and which system tables to hide.
 */
public final class CatalogReader {

    private CatalogReader() {
    }

    public static SchemaPlus read(Connection connection, DialectSupport dialect) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();

        Map<String, Schema.TableType> kinds = new LinkedHashMap<>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!dialect.isSystemTable(name)) {
                    kinds.put(name, "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"))
                            ? Schema.TableType.VIEW : Schema.TableType.TABLE);
                }
            }
        }

        Map<String, List<JdbcColumn>> columns = new LinkedHashMap<>();
        for (String table : kinds.keySet()) {
            columns.put(table, columns(meta, table));
        }

        SchemaPlus root = Frameworks.createRootSchema(false);
        for (var entry : kinds.entrySet()) {
            String table = entry.getKey();
            List<JdbcColumn> cols = columns.get(table);
            List<ImmutableBitSet> keys = new ArrayList<>();
            List<RelReferentialConstraint> foreignKeys = new ArrayList<>();
            if (entry.getValue() == Schema.TableType.TABLE) {
                List<String> primaryKey = primaryKey(meta, table);
                if (!primaryKey.isEmpty()) {
                    keys.add(ImmutableBitSet.of(primaryKey.stream().map(c -> indexOf(cols, c)).toList()));
                }
                foreignKeys.addAll(foreignKeys(meta, table, columns));
            }
            root.add(table, new CatalogTable(table, entry.getValue(), cols, dialect.columnTypes(),
                    Statistics.of(null, keys, foreignKeys, List.of())));
        }
        return root;
    }

    private static List<JdbcColumn> columns(DatabaseMetaData meta, String table) throws SQLException {
        TreeMap<Integer, JdbcColumn> byPosition = new TreeMap<>();
        try (ResultSet rs = meta.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                byPosition.put(rs.getInt("ORDINAL_POSITION"), new JdbcColumn(
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("DATA_TYPE"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
            }
        }
        return List.copyOf(byPosition.values());
    }

    private static List<String> primaryKey(DatabaseMetaData meta, String table) throws SQLException {
        TreeMap<Integer, String> bySequence = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, null, table)) {
            while (rs.next()) {
                bySequence.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(bySequence.values());
    }

    /** One constraint per foreign key; a compound key's columns arrive as KEY_SEQ 1, 2, … */
    private static List<RelReferentialConstraint> foreignKeys(DatabaseMetaData meta, String table,
                                                              Map<String, List<JdbcColumn>> columns) throws SQLException {
        List<RelReferentialConstraint> result = new ArrayList<>();
        String target = null;
        List<IntPair> pairs = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(null, null, table)) {
            while (rs.next()) {
                int sequence = rs.getInt("KEY_SEQ");
                String referenced = rs.getString("PKTABLE_NAME");
                if (sequence == 1 && target != null) {
                    result.add(RelReferentialConstraintImpl.of(List.of(table), List.of(target), pairs));
                    pairs = new ArrayList<>();
                }
                target = referenced;
                List<JdbcColumn> targetColumns = columns.get(referenced);
                if (targetColumns == null) {
                    continue; // references a table the catalog does not show
                }
                String referencedColumn = rs.getString("PKCOLUMN_NAME");
                if (referencedColumn == null || referencedColumn.isEmpty()) {
                    // "REFERENCES parent" without a column list means the parent's primary key.
                    referencedColumn = primaryKey(meta, referenced).get(sequence - 1);
                }
                pairs.add(IntPair.of(indexOf(columns.get(table), rs.getString("FKCOLUMN_NAME")),
                        indexOf(targetColumns, referencedColumn)));
            }
        }
        if (target != null && columns.containsKey(target)) {
            result.add(RelReferentialConstraintImpl.of(List.of(table), List.of(target), pairs));
        }
        return result;
    }

    private static int indexOf(List<JdbcColumn> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException("Column " + name + " is not in the table");
    }
}
