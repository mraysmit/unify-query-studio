package com.unify.querystudio.server;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.catalog.CatalogTable;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.lookup.LikePattern;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.mapping.IntPair;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The catalog the designer loads: tables, columns with Calcite types, keys and foreign keys. */
@Path("/api/connections/{id}/catalog")
public class CatalogResource {

    public record Catalog(List<Table> tables) {
    }

    public record Table(String name, String type, List<Column> columns, List<String> primaryKey,
                        List<ForeignKey> foreignKeys) {
    }

    /** A column typed as the validator sees it: Calcite {@code SqlTypeName}, precision and scale. */
    public record Column(String name, String type, Integer precision, Integer scale, boolean nullable) {
    }

    public record ForeignKey(List<String> columns, String referencedTable, List<String> referencedColumns) {
    }

    @Inject
    TargetDatabases databases;

    @GET
    public Catalog catalog(@PathParam("id") String id) throws SQLException {
        TargetDatabases.Target target = databases.target(id);
        SchemaPlus schema;
        try (Connection connection = databases.open(target)) {
            schema = CatalogReader.read(connection, target.dialect());
        }
        RelDataTypeFactory types = new JavaTypeFactoryImpl();
        List<Table> tables = new ArrayList<>();
        for (String name : schema.tables().getNames(LikePattern.any())) {
            CatalogTable table = (CatalogTable) schema.tables().get(name);
            tables.add(table(schema, table, types));
        }
        tables.sort(Comparator.comparing(Table::name));
        return new Catalog(tables);
    }

    private static Table table(SchemaPlus schema, CatalogTable table, RelDataTypeFactory types) {
        List<RelDataTypeField> fields = table.getRowType(types).getFieldList();
        List<Column> columns = new ArrayList<>();
        for (RelDataTypeField f : fields) {
            RelDataType t = f.getType();
            boolean precise = t.getSqlTypeName().allowsPrec() && t.getPrecision() != RelDataType.PRECISION_NOT_SPECIFIED;
            boolean scaled = t.getSqlTypeName().allowsScale() && t.getScale() != RelDataType.SCALE_NOT_SPECIFIED;
            columns.add(new Column(f.getName(), t.getSqlTypeName().getName(),
                    precise ? t.getPrecision() : null, scaled ? t.getScale() : null, t.isNullable()));
        }
        List<String> primaryKey = new ArrayList<>();
        List<ImmutableBitSet> keys = table.getStatistic().getKeys();
        if (keys != null && !keys.isEmpty()) {
            keys.getFirst().forEach(i -> primaryKey.add(fields.get(i).getName()));
        }
        List<ForeignKey> foreignKeys = new ArrayList<>();
        List<RelReferentialConstraint> constraints = table.getStatistic().getReferentialConstraints();
        for (RelReferentialConstraint fk : constraints == null ? List.<RelReferentialConstraint>of() : constraints) {
            String referenced = fk.getTargetQualifiedName().getLast();
            List<RelDataTypeField> targetFields = schema.tables().get(referenced).getRowType(types).getFieldList();
            List<String> from = new ArrayList<>();
            List<String> to = new ArrayList<>();
            for (IntPair pair : fk.getColumnPairs()) {
                from.add(fields.get(pair.source).getName());
                to.add(targetFields.get(pair.target).getName());
            }
            foreignKeys.add(new ForeignKey(from, referenced, to));
        }
        return new Table(table.name(), table.getJdbcTableType().jdbcName, columns, primaryKey, foreignKeys);
    }
}
