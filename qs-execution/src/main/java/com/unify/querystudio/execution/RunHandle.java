package com.unify.querystudio.execution;

import com.unify.querystudio.compiler.ResultColumn;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A prepared run. {@link #await()} executes it on the calling thread; {@link #cancel()} may be called
 * from any other thread and stops the statement.
 */
public final class RunHandle {

    private final PreparedStatement statement;
    private final List<ResultColumn> columns;
    private final int maxRows;
    private final Duration timeout;
    private final ScheduledExecutorService timer;
    private volatile boolean cancelled;
    private volatile boolean timedOut;

    RunHandle(PreparedStatement statement, List<ResultColumn> columns, int maxRows, Duration timeout,
              ScheduledExecutorService timer) {
        this.statement = statement;
        this.columns = columns;
        this.maxRows = maxRows;
        this.timeout = timeout;
        this.timer = timer;
    }

    public RunResult await() {
        ScheduledFuture<?> deadline = timer.schedule(() -> {
            timedOut = true;
            stop();
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        long start = System.nanoTime();
        try (statement) {
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (rows.size() == maxRows) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(columns.size());
                    for (int i = 0; i < columns.size(); i++) {
                        row.add(Values.typed(rs.getObject(i + 1), columns.get(i).type()));
                    }
                    rows.add(row);
                }
            }
            return new RunResult(columns, rows, truncated, Duration.ofNanos(Math.max(1, System.nanoTime() - start)));
        } catch (SQLException e) {
            if (cancelled) throw new QueryCancelledException(e);
            if (timedOut) throw new QueryTimeoutException(timeout, e);
            throw new QueryFailedException(e);
        } finally {
            deadline.cancel(false);
        }
    }

    public void cancel() {
        cancelled = true;
        stop();
    }

    private void stop() {
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // The statement may already have finished or closed; nothing left to stop.
        }
    }
}
