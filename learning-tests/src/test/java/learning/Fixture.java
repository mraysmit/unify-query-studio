package learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test infrastructure for the learning tests: the Northstar sample as a SQLite file,
 * a Calcite schema over it, and helpers to compile and run SQL. Uses Calcite's stock
 * classes only; nothing here is Query Studio production code.
 */
final class Fixture {

    static final Path DB = build();

    private Fixture() {
    }

    static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + DB);
    }

    /** A Calcite root schema over the sample database; SQLite DATE columns typed as Calcite DATE. */
    static SchemaPlus schema() throws Exception {
        SchemaPlus root = Frameworks.createRootSchema(false);
        try (Connection c = connect()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet t = md.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (t.next()) {
                    String name = t.getString("TABLE_NAME");
                    if (name.startsWith("sqlite_")) continue;
                    List<String[]> cols = new ArrayList<>();
                    try (ResultSet col = md.getColumns(null, null, name, "%")) {
                        while (col.next()) cols.add(new String[] {col.getString("COLUMN_NAME"), col.getString("TYPE_NAME")});
                    }
                    root.add(name, new AbstractTable() {
                        @Override
                        public RelDataType getRowType(RelDataTypeFactory f) {
                            RelDataTypeFactory.Builder b = f.builder();
                            for (String[] col : cols) {
                                b.add(col[0], f.createTypeWithNullability(f.createSqlType(type(col[1])), true));
                            }
                            return b.build();
                        }
                    });
                }
            }
        }
        return root;
    }

    private static SqlTypeName type(String declared) {
        String t = declared.toUpperCase(Locale.ROOT);
        if (t.contains("BOOL")) return SqlTypeName.BOOLEAN;
        if (t.contains("DATE")) return SqlTypeName.DATE;
        if (t.contains("INT")) return SqlTypeName.BIGINT;
        return t.contains("CHAR") || t.contains("TEXT") ? SqlTypeName.VARCHAR : SqlTypeName.DOUBLE;
    }

    static SqlParser.Config canonicalParser() {
        return SqlParser.config()
                .withQuoting(Quoting.DOUBLE_QUOTE)
                .withUnquotedCasing(Casing.UNCHANGED)
                .withQuotedCasing(Casing.UNCHANGED)
                .withCaseSensitive(true);
    }

    static Planner planner(Context context) throws Exception {
        return Frameworks.getPlanner(Frameworks.newConfigBuilder()
                .parserConfig(canonicalParser())
                .defaultSchema(schema())
                .context(context)
                .build());
    }

    /** Parse, validate and convert canonical SQL to its relational form. */
    static RelNode rel(String sql) throws Exception {
        return rel(sql, Contexts.empty());
    }

    static RelNode rel(String sql, Context context) throws Exception {
        Planner p = planner(context);
        return p.rel(p.validate(p.parse(sql))).project();
    }

    static SqlString toSql(RelNode rel, SqlDialect dialect) {
        return new RelToSqlConverter(dialect).visitRoot(rel).asStatement()
                .toSqlString(c -> c.withDialect(dialect).withClauseStartsLine(true).withIndentation(2));
    }

    /** Calcite writes the platform line separator; tests compare with \n. */
    static String text(SqlString s) {
        return s.getSql().replace("\r\n", "\n").strip();
    }

    static List<List<Object>> run(String sql, Object... params) throws Exception {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) p.setObject(i + 1, params[i]);
            List<List<Object>> rows = new ArrayList<>();
            try (ResultSet rs = p.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static Path build() {
        try {
            Path file = Files.createTempDirectory("learning").resolve("northstar.db");
            JsonNode data;
            try (InputStream in = Fixture.class.getResourceAsStream("/northstar.json")) {
                data = new ObjectMapper().readTree(in);
            }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
                for (JsonNode t : data.get("tables")) {
                    String table = t.get("name").asText();
                    List<String> cols = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    for (JsonNode f : t.get("fields")) {
                        String type = switch (f.get("type").asText()) {
                            case "number" -> "INTEGER";
                            case "date" -> "DATE";
                            case "boolean" -> "BOOLEAN";
                            default -> "TEXT";
                        };
                        cols.add("\"" + f.get("name").asText() + "\" " + type + (f.path("primaryKey").asBoolean() ? " PRIMARY KEY" : ""));
                        names.add(f.get("name").asText());
                    }
                    c.createStatement().execute("CREATE TABLE \"" + table + "\" (" + String.join(", ", cols) + ")");
                    String insert = "INSERT INTO \"" + table + "\" VALUES (" + "?, ".repeat(names.size() - 1) + "?)";
                    try (PreparedStatement p = c.prepareStatement(insert)) {
                        for (JsonNode row : t.get("rows")) {
                            for (int i = 0; i < names.size(); i++) {
                                JsonNode v = row.get(names.get(i));
                                p.setObject(i + 1, v == null || v.isNull() ? null : v.isBoolean() ? (v.asBoolean() ? 1 : 0)
                                        : v.isIntegralNumber() ? (Object) v.asLong() : v.isNumber() ? v.asDouble() : v.asText());
                            }
                            p.execute();
                        }
                    }
                }
            }
            return file;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
