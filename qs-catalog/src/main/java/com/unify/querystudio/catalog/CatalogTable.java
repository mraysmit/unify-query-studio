package com.unify.querystudio.catalog;

import com.unify.querystudio.dialects.ColumnTypeResolver;
import com.unify.querystudio.dialects.JdbcColumn;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.List;

/**
 * A table or view of a connected database, as Calcite sees it: a row type and a statistic holding
 * its keys and foreign keys. It implements neither ScannableTable nor TranslatableTable, so Calcite
 * can plan against it but never execute against it; the target database runs the SQL.
 */
public final class CatalogTable extends AbstractTable {

    private final String name;
    private final Schema.TableType kind;
    private final List<JdbcColumn> columns;
    private final ColumnTypeResolver columnTypes;
    private final Statistic statistic;

    CatalogTable(String name, Schema.TableType kind, List<JdbcColumn> columns, ColumnTypeResolver columnTypes,
                 Statistic statistic) {
        this.name = name;
        this.kind = kind;
        this.columns = List.copyOf(columns);
        this.columnTypes = columnTypes;
        this.statistic = statistic;
    }

    public String name() {
        return name;
    }

    /** The columns as the database reported them, in database order. */
    public List<JdbcColumn> columns() {
        return columns;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (JdbcColumn column : columns) {
            builder.add(column.name(), columnTypes.resolve(column, typeFactory));
        }
        return builder.build();
    }

    @Override
    public Statistic getStatistic() {
        return statistic;
    }

    @Override
    public Schema.TableType getJdbcTableType() {
        return kind;
    }
}
