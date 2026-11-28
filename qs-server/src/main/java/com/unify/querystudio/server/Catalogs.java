package com.unify.querystudio.server;

import com.unify.querystudio.catalog.CatalogReader;
import com.unify.querystudio.compiler.SavedQuerySchema;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.calcite.schema.SchemaPlus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reads a target's catalog into a Calcite schema. Read on every request for now; no cache yet. */
@ApplicationScoped
public class Catalogs {

    @Inject
    TargetDatabases databases;

    @Inject
    SavedQueryStore savedQueries;

    @Inject
    SecurityIdentity identity;

    /** The database's tables and views only, as the table list shows them. */
    public SchemaPlus schema(TargetDatabases.Target target) throws SQLException {
        try (Connection connection = databases.open(target)) {
            return CatalogReader.read(connection, target.dialect());
        }
    }

    /**
     * The tables and views, plus the signed-in user's saved queries on this connection as views in
     * the {@code queries} schema, so a query can use a saved query as a source.
     */
    public SchemaPlus withSavedQueries(TargetDatabases.Target target) throws SQLException {
        SchemaPlus schema = schema(target);
        String user = identity.getPrincipal().getName();
        SavedQuerySchema.attach(schema, target.dialect(), new SavedQuerySchema.Source() {
            @Override
            public Set<String> ids() {
                try {
                    return savedQueries.ids(user, target.id());
                } catch (SQLException e) {
                    throw new IllegalStateException("Saved queries could not be read", e);
                }
            }

            @Override
            public Optional<String> sql(String id) {
                try {
                    return savedQueries.version(UUID.fromString(id), user, null)
                            .filter(q -> q.connectionId().equals(target.id()))
                            .map(SavedQueriesResource.QueryView::sql);
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                } catch (SQLException e) {
                    throw new IllegalStateException("Saved queries could not be read", e);
                }
            }
        });
        return schema;
    }
}
