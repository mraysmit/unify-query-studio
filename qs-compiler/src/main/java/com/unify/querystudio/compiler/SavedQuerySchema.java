package com.unify.querystudio.compiler;

import com.unify.querystudio.dialects.DialectSupport;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.ViewTable;
import org.apache.calcite.schema.lookup.LikePattern;
import org.apache.calcite.schema.lookup.Lookup;
import org.apache.calcite.schema.lookup.Named;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Saved queries as Calcite views, so a query can use a saved query as a source, as Access does. The
 * views live in the {@value #NAME} schema, named by the saved query's ID, so canonical SQL refers to
 * {@code "queries"."<id>"} and survives renames. Each view is Calcite's own {@link ViewTable}: the
 * validator reads its row type, and conversion to relational algebra expands its SQL, so the target
 * SQL is complete without the view.
 *
 * <p>A saved query is compiled only when Calcite needs its row type, so only the saved queries a
 * query uses are compiled.
 */
public final class SavedQuerySchema extends AbstractSchema {

    public static final String NAME = "queries";

    /** A saved query used as a source cannot be used: it is invalid now, or uses itself. */
    public static final class InvalidSavedQueryException extends RuntimeException {
        InvalidSavedQueryException(String message) {
            super(message);
        }
    }

    /** The saved queries a user may use on one connection. */
    public interface Source {
        /** Their IDs; Calcite looks names up in this set before asking for a table. */
        Set<String> ids();

        /** The canonical SQL of one, if the user may use it. */
        Optional<String> sql(String id);
    }

    private final SchemaPlus root;
    private final DialectSupport dialect;
    private final Source saved;
    private final Map<String, Table> loaded = new HashMap<>();
    private final Map<String, List<ResultColumn>> columns = new HashMap<>();
    private final Set<String> loading = new HashSet<>();

    private SavedQuerySchema(SchemaPlus root, DialectSupport dialect, Source saved) {
        this.root = root;
        this.dialect = dialect;
        this.saved = saved;
    }

    /** Adds the {@value #NAME} schema to a connection's schema. */
    public static void attach(SchemaPlus root, DialectSupport dialect, Source saved) {
        root.add(NAME, new SavedQuerySchema(root, dialect, saved));
    }

    @Override
    public Lookup<Table> tables() {
        return new Lookup<>() {
            @Override
            public Table get(String id) {
                return load(id);
            }

            @Override
            public Named<Table> getIgnoreCase(String id) {
                Table table = load(id);
                return table == null ? null : new Named<>(id, table);
            }

            @Override
            public Set<String> getNames(LikePattern pattern) {
                return saved.ids().stream().filter(id -> pattern.matcher().apply(id))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
        };
    }

    /** A view over the saved query; its SQL is compiled only when Calcite asks for its row type. */
    private synchronized Table load(String id) {
        if (loaded.containsKey(id)) {
            return loaded.get(id);
        }
        Optional<String> sql = saved.sql(id);
        if (sql.isEmpty()) {
            return null;
        }
        Table view = new ViewTable(Object[].class, types -> {
            RelDataTypeFactory.Builder row = types.builder();
            columns(id, sql.get()).forEach(c -> row.add(c.name(), types.copyType(c.type())));
            return row.build();
        }, sql.get(), List.of(), List.of(NAME, id));
        loaded.put(id, view);
        return view;
    }

    private synchronized List<ResultColumn> columns(String id, String sql) {
        if (columns.containsKey(id)) {
            return columns.get(id);
        }
        if (!NamedParameters.mark(sql).names().isEmpty()) {
            // Calcite expands a view's SQL with its own parser, which has no named parameters.
            throw new InvalidSavedQueryException("The saved query " + id + " asks for parameters, so it can't be "
                    + "used as a source yet.");
        }
        if (!loading.add(id)) {
            throw new InvalidSavedQueryException("The saved query " + id + " uses itself, directly or through another query.");
        }
        try {
            List<ResultColumn> result = new QueryCompiler(root, dialect).compile(sql).columns();
            columns.put(id, result);
            return result;
        } catch (CompilationException e) {
            InvalidSavedQueryException inner = find(e);
            throw inner != null ? inner
                    : new InvalidSavedQueryException("The saved query " + id + " is no longer valid: " + e.getMessage());
        } finally {
            loading.remove(id);
        }
    }

    /** The saved-query failure behind an exception, if there is one. */
    public static InvalidSavedQueryException find(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InvalidSavedQueryException invalid) {
                return invalid;
            }
        }
        return null;
    }
}
