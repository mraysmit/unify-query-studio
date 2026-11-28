package com.unify.querystudio.catalog;

import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.testkit.NorthstarDatabase;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.mapping.IntPair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogReaderTest {

    @TempDir
    static Path dir;
    static SchemaPlus northstar;
    static final JavaTypeFactoryImpl TYPES = new JavaTypeFactoryImpl();

    @BeforeAll
    static void readNorthstar() throws Exception {
        Path db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.createStatement().execute("CREATE VIEW \"Big orders\" AS SELECT order_id, total * 2 AS doubled FROM Orders");
            c.createStatement().execute("CREATE TABLE \"Counters\" (id INTEGER PRIMARY KEY AUTOINCREMENT, n INTEGER)");
            northstar = CatalogReader.read(c, DialectSupport.forId("sqlite"));
        }
    }

    static Table table(String name) {
        return northstar.tables().get(name);
    }

    static RelDataType rowType(String name) {
        return table(name).getRowType(TYPES);
    }

    @Test
    void exposesTablesAndViewsButNotSystemTables() {
        assertThat(northstar.tables().getNames(org.apache.calcite.schema.lookup.LikePattern.any()))
                .containsExactlyInAnyOrder("Customers", "Orders", "OrderItems", "Products", "Big orders", "Counters");
        assertThat(table("Orders").getJdbcTableType()).isEqualTo(Schema.TableType.TABLE);
        assertThat(table("Big orders").getJdbcTableType()).isEqualTo(Schema.TableType.VIEW);
    }

    @Test
    void columnsKeepDatabaseOrderAndResolveThroughTheDialect() {
        RelDataType orders = rowType("Orders");
        assertThat(orders.getFieldNames()).containsExactly("order_id", "customer_id", "order_date", "status", "total");
        assertThat(orders.getFieldList()).extracting(f -> f.getType().getSqlTypeName())
                .containsExactly(SqlTypeName.BIGINT, SqlTypeName.BIGINT, SqlTypeName.DATE, SqlTypeName.VARCHAR, SqlTypeName.BIGINT);
        assertThat(rowType("Products").getField("in_stock", true, false).getType().getSqlTypeName())
                .isEqualTo(SqlTypeName.BOOLEAN);
    }

    @Test
    void viewColumnsWithoutADeclaredTypeAreAny() {
        RelDataTypeField doubled = rowType("Big orders").getField("doubled", true, false);
        assertThat(doubled.getType().getSqlTypeName()).isEqualTo(SqlTypeName.ANY);
    }

    @Test
    void primaryKeysAreStatisticKeys() {
        assertThat(table("Orders").getStatistic().getKeys()).containsExactly(ImmutableBitSet.of(0));
        assertThat(table("Orders").getStatistic().isKey(ImmutableBitSet.of(0))).isTrue();
        assertThat(table("Orders").getStatistic().isKey(ImmutableBitSet.of(1))).isFalse();
    }

    @Test
    void foreignKeysAreReferentialConstraintsFromReferencingToReferencedTable() {
        List<RelReferentialConstraint> items = table("OrderItems").getStatistic().getReferentialConstraints();
        assertThat(items).hasSize(2);
        assertThat(items).anySatisfy(fk -> {
            assertThat(fk.getSourceQualifiedName()).containsExactly("OrderItems");
            assertThat(fk.getTargetQualifiedName()).containsExactly("Orders");
            assertThat(fk.getColumnPairs()).containsExactly(IntPair.of(1, 0));
        });
        assertThat(items).anySatisfy(fk -> {
            assertThat(fk.getTargetQualifiedName()).containsExactly("Products");
            assertThat(fk.getColumnPairs()).containsExactly(IntPair.of(2, 0));
        });
        assertThat(table("Customers").getStatistic().getReferentialConstraints()).isEmpty();
    }

    @Test
    void compoundKeysStayTogether(@TempDir Path other) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + other.resolve("compound.db"))) {
            c.createStatement().execute("CREATE TABLE Shipments (region TEXT, number INTEGER, carrier TEXT, PRIMARY KEY (region, number))");
            c.createStatement().execute("CREATE TABLE Parcels (id INTEGER PRIMARY KEY, ship_region TEXT, ship_number INTEGER, "
                    + "FOREIGN KEY (ship_region, ship_number) REFERENCES Shipments (region, number))");
            SchemaPlus schema = CatalogReader.read(c, DialectSupport.forId("sqlite"));
            assertThat(schema.tables().get("Shipments").getStatistic().getKeys()).containsExactly(ImmutableBitSet.of(0, 1));
            List<RelReferentialConstraint> fks = schema.tables().get("Parcels").getStatistic().getReferentialConstraints();
            assertThat(fks).singleElement().satisfies(fk ->
                    assertThat(fk.getColumnPairs()).containsExactly(IntPair.of(1, 0), IntPair.of(2, 1)));
        }
    }

    @Test
    void theSchemaIsUsableByCalcitesValidator() throws Exception {
        Planner planner = Frameworks.getPlanner(Frameworks.newConfigBuilder()
                .parserConfig(org.apache.calcite.sql.parser.SqlParser.config()
                        .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED)
                        .withCaseSensitive(true))
                .defaultSchema(northstar).build());
        var validated = planner.validate(planner.parse(
                "SELECT c.\"name\", o.\"order_date\" FROM \"Customers\" c JOIN \"Orders\" o ON o.\"customer_id\" = c.\"customer_id\""));
        assertThat(planner.rel(validated).validatedRowType.getFieldNames()).containsExactly("name", "order_date");
    }
}
