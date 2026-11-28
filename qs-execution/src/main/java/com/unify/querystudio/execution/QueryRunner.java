package com.unify.querystudio.execution;

import com.unify.querystudio.compiler.BoundValue;
import com.unify.querystudio.compiler.CompiledQuery;
import com.unify.querystudio.dialects.DialectSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Runs compiled queries over JDBC: parameters bound by the dialect's binder, at most {@code maxRows}
 * rows read (one more is fetched to know whether the result was cut off), stopped at the timeout.
 */
public final class QueryRunner {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "query-timeouts");
        t.setDaemon(true);
        return t;
    });

    private final DialectSupport dialect;

    public QueryRunner(DialectSupport dialect) {
        this.dialect = dialect;
    }

    public RunResult run(Connection connection, CompiledQuery query, int maxRows, Duration timeout) {
        return start(connection, query, maxRows, timeout).await();
    }

    /** Prepares the run without executing it, so it can be cancelled from another thread. */
    public RunHandle start(Connection connection, CompiledQuery query, int maxRows, Duration timeout) {
        try {
            PreparedStatement statement = connection.prepareStatement(query.targetSql());
            statement.setMaxRows(maxRows + 1);
            for (int i = 0; i < query.parameters().size(); i++) {
                BoundValue p = query.parameters().get(i);
                dialect.binder().bind(statement, i + 1, p.value(), p.type());
            }
            return new RunHandle(statement, query.columns(), maxRows, timeout, TIMER);
        } catch (SQLException e) {
            throw new QueryFailedException(e);
        }
    }
}
