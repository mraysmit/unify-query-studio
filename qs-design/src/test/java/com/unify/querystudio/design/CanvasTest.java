package com.unify.querystudio.design;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.compiler.QueryCompiler;
import com.unify.querystudio.compiler.SavedQuerySchema;
import com.unify.querystudio.dialects.DialectSupport;
import com.unify.querystudio.testkit.NorthstarDatabase;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.JoinType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adding a source to the canvas, as Access does: a second copy is named {@code Customers_1}, each
 * foreign key draws an inner join, and without one Access's AutoJoin rule joins a field of the same
 * name and type when one side is a primary key.
 */
class CanvasTest {

    @TempDir
    static Path dir;
    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");
    static SchemaPlus northstar;
    static SchemaPlus noForeignKeys;
    static QueryCompiler compiler;
    static final String BIG_ORDERS =
            "SELECT \"Orders\".\"customer_id\", \"Orders\".\"total\" FROM \"Orders\" WHERE \"Orders\".\"total\" >= 100";

    @BeforeAll
    static void setUp() throws Exception {
        Path db = NorthstarDatabase.create(dir.resolve("northstar.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            northstar = CatalogReader.read(c, SQLITE);
        }
        SavedQuerySchema.attach(northstar, SQLITE, new SavedQuerySchema.Source() {
            @Override
            public Set<String> ids() {
                return Set.of("q1");
            }

            @Override
            public Optional<String> sql(String id) {
                return id.equals("q1") ? Optional.of(BIG_ORDERS) : Optional.empty();
            }
        });
        compiler = new QueryCompiler(northstar, SQLITE);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("plain.db"));
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE Region (region_id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("CREATE TABLE Store (store_id INTEGER PRIMARY KEY, region_id INTEGER, name TEXT)");
            s.execute("CREATE TABLE Note (note_id INTEGER PRIMARY KEY, region_id TEXT)");
            s.execute("CREATE TABLE Visit (visit_id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("CREATE TABLE Linked (linked_id INTEGER PRIMARY KEY, region_id INTEGER, store_id INTEGER, "
                    + "FOREIGN KEY (store_id) REFERENCES Store (store_id))");
            s.execute("CREATE VIEW RegionView AS SELECT region_id, name FROM Region");
            noForeignKeys = CatalogReader.read(c, SQLITE);
        }
    }

    static final DesignView EMPTY = DesignView.of(List.of(), List.of(), List.of());

    static DesignView add(SchemaPlus schema, String... tables) {
        DesignView design = EMPTY;
        for (String table : tables) {
            design = Canvas.addSource(design, schema, table);
        }
        return design;
    }

    @Test
    void theFirstCopyIsNamedAfterItsTable() {
        DesignView design = add(northstar, "Customers");
        assertThat(design.sources()).containsExactly(Source.of("Customers"));
        assertThat(design.joins()).isEmpty();
    }

    @Test
    void aForeignKeyDrawsAnInnerJoinFromTheReferencedTable() {
        assertThat(add(northstar, "Orders", "Customers").joins()).containsExactly(
                new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.INNER));
    }

    @Test
    void aTableRelatedToSeveralSourcesIsJoinedToEach() {
        assertThat(add(northstar, "Orders", "Products", "OrderItems").joins()).containsExactly(
                new JoinLine("Orders", "order_id", "OrderItems", "order_id", JoinType.INNER),
                new JoinLine("Products", "product_id", "OrderItems", "product_id", JoinType.INNER));
    }

    @Test
    void furtherCopiesAreNamedTable_1_Table_2_andDrawNoJoins() {
        DesignView design = add(northstar, "Orders", "Customers", "Customers", "Customers");
        assertThat(design.sources()).extracting(Source::alias).containsExactly("Orders", "Customers", "Customers_1", "Customers_2");
        assertThat(design.sources().get(3).table()).containsExactly("Customers");
        assertThat(design.joins()).hasSize(1);
    }

    @Test
    void aCopyTakesTheFirstFreeName() {
        DesignView design = new DesignView(List.of(new Source("Customers_1", List.of("Customers"))), List.of(), List.of(),
                false, false, null);
        assertThat(Canvas.addSource(design, northstar, "Customers").sources()).extracting(Source::alias)
                .containsExactly("Customers_1", "Customers");
        assertThat(Canvas.addSource(Canvas.addSource(design, northstar, "Customers"), northstar, "Customers").sources())
                .extracting(Source::alias).containsExactly("Customers_1", "Customers", "Customers_2");
    }

    @Test
    void autoJoin_aSameNamedFieldOfTheSameTypeJoinsWhenOneSideIsTheKey() {
        assertThat(add(noForeignKeys, "Store", "Region").joins()).containsExactly(
                new JoinLine("Region", "region_id", "Store", "region_id", JoinType.INNER));
    }

    @Test
    void autoJoin_needsTheSameTypeAndAKeyOnOneSide() {
        assertThat(add(noForeignKeys, "Region", "Note").joins()).as("region_id is TEXT in Note").isEmpty();
        assertThat(add(noForeignKeys, "Store", "Visit").joins()).as("name is a key on neither side").isEmpty();
    }

    @Test
    void autoJoin_isNotUsedWhenTheTablesHaveAForeignKey() {
        // Linked has a foreign key to Store, so Store.region_id = Linked.region_id is not guessed.
        assertThat(add(noForeignKeys, "Store", "Linked").joins()).containsExactly(
                new JoinLine("Store", "store_id", "Linked", "store_id", JoinType.INNER));
    }

    @Test
    void autoJoin_isNeverUsedWithAView() {
        assertThat(add(noForeignKeys, "Store", "RegionView").joins()).isEmpty();
    }

    @Test
    void theRestOfTheDesignIsKept() {
        DesignView design = add(northstar, "Orders").withTotals(true).withTopValues(5);
        design = new DesignView(design.sources(), design.joins(), List.of(GridColumn.field("Orders", "total")),
                true, false, 5);
        DesignView added = Canvas.addSource(design, northstar, "Customers");
        assertThat(added.columns()).isEqualTo(design.columns());
        assertThat(added.totals()).isTrue();
        assertThat(added.topValues()).isEqualTo(5);
    }

    @Test
    void anUnknownTableIsRefused() {
        assertThatThrownBy(() -> add(northstar, "Nope")).isInstanceOf(DesignException.class).hasMessageContaining("Nope");
    }

    @Test
    void aSavedQueryIsAddedUnderItsName_withNoAutomaticJoins() {
        DesignView design = Canvas.addSavedQuery(add(northstar, "Customers"), northstar, "q1", "Big orders");
        assertThat(design.sources()).containsExactly(Source.of("Customers"), new Source("Big orders", List.of("queries", "q1")));
        assertThat(design.joins()).isEmpty();
        DesignView twice = Canvas.addSavedQuery(design, northstar, "q1", "Big orders");
        assertThat(twice.sources().getLast().alias()).isEqualTo("Big orders_1");
        assertThat(Canvas.addSource(twice, northstar, "Orders").joins()).as("Orders joins Customers only")
                .containsExactly(new JoinLine("Customers", "customer_id", "Orders", "customer_id", JoinType.INNER));
    }

    @Test
    void anUnknownSavedQueryIsRefused() {
        assertThatThrownBy(() -> Canvas.addSavedQuery(EMPTY, northstar, "nope", "Nope"))
                .isInstanceOf(DesignException.class).hasMessageContaining("Nope");
    }

    @Test
    void aDesignOnASavedQueryCompilesAndReopensAsTheSameDesign() {
        DesignView added = Canvas.addSavedQuery(add(northstar, "Customers"), northstar, "q1", "Big orders");
        DesignView design = new DesignView(added.sources(),
                List.of(new JoinLine("Customers", "customer_id", "Big orders", "customer_id", JoinType.INNER)),
                List.of(GridColumn.field("Customers", "name"), GridColumn.field("Big orders", "total")), false, false, null);
        DesignCompiler.Result r = DesignCompiler.compile(design, compiler);
        assertThat(r.canonicalSql()).contains("\"queries\".\"q1\" AS \"Big orders\"");
        assertThat(r.query().sql()).doesNotContain("queries").contains(">= 100");
        assertThat(SqlToDesign.toDesign(r.canonicalSql())).isInstanceOfSatisfying(SqlToDesign.Representable.class,
                back -> assertThat(back.design().sources()).containsExactlyInAnyOrderElementsOf(design.sources()));
    }
}
