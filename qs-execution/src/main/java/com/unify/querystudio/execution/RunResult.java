package com.unify.querystudio.execution;

import com.unify.querystudio.compiler.ResultColumn;

import java.time.Duration;
import java.util.List;

/**
 * The rows a run returned, typed by the validated column types ({@code Long}, {@code BigDecimal},
 * {@code Double}, {@code Boolean}, {@code String}, {@code LocalDate}, {@code LocalDateTime},
 * {@code LocalTime}, {@code byte[]}; {@code null} for SQL NULL).
 *
 * @param truncated whether the query had more rows than the run's limit
 */
public record RunResult(List<ResultColumn> columns, List<List<Object>> rows, boolean truncated, Duration elapsed) {

    public RunResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
