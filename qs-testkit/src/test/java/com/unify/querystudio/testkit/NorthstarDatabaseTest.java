package com.unify.querystudio.testkit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NorthstarDatabaseTest {

    @TempDir
    static Path dir;
    static Path db;

    @BeforeAll
    static void create() {
        db = NorthstarDatabase.create(dir.resolve("northstar.db"));
    }

    static List<List<Object>> query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            List<List<Object>> rows = new ArrayList<>();
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
            return rows;
        }
    }

    @Test
    void createsTheFourPrototypeTablesWithTheirRows() throws Exception {
        assertThat(query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"))
                .extracting(r -> r.getFirst())
                .containsExactly("Customers", "OrderItems", "Orders", "Products");
        assertThat(query("SELECT (SELECT count(*) FROM Customers), (SELECT count(*) FROM Orders), "
                + "(SELECT count(*) FROM OrderItems), (SELECT count(*) FROM Products)").getFirst())
                .containsExactly(12, 20, 30, 10);
    }

    @Test
    void declaresPrimaryAndForeignKeys() throws Exception {
        assertThat(query("SELECT pk FROM pragma_table_info('Orders') WHERE name = 'order_id'").getFirst())
                .containsExactly(1);
        assertThat(query("SELECT \"table\", \"from\", \"to\" FROM pragma_foreign_key_list('OrderItems') ORDER BY \"table\""))
                .containsExactly(List.of("Orders", "order_id", "order_id"), List.of("Products", "product_id", "product_id"));
    }

    @Test
    void storesDatesAsIsoTextAndBooleansAsIntegers() throws Exception {
        assertThat(query("SELECT typeof(order_date), order_date FROM Orders WHERE order_id = 1001").getFirst())
                .containsExactly("text", "2026-10-03");
        assertThat(query("SELECT DISTINCT typeof(in_stock) FROM Products")).containsExactly(List.of("integer"));
        assertThat(query("SELECT type FROM pragma_table_info('Orders') WHERE name = 'order_date'").getFirst())
                .containsExactly("DATE");
    }

    @Test
    void createIfMissingLeavesAnExistingFileAlone() throws Exception {
        long before = db.toFile().lastModified();
        assertThat(NorthstarDatabase.createIfMissing(db)).isEqualTo(db);
        assertThat(db.toFile().lastModified()).isEqualTo(before);
    }
}
