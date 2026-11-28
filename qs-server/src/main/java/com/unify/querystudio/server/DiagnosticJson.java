package com.unify.querystudio.server;

import com.unify.querystudio.compiler.Diagnostic;

/**
 * A problem located on a design element (technical design, diagnostics): {@code element} is
 * {@code cell} or {@code column} (with the grid column ID and, for a cell, the criteria row),
 * {@code source} (with the source alias), {@code join} (with the join line's position in the design),
 * {@code sql} (with a 1-based range in the canonical SQL; 0 for the whole statement), or
 * {@code design} for the design as a whole.
 */
public record DiagnosticJson(String element, String column, Integer row, String source, Integer join, Integer line,
                             Integer startColumn, Integer endLine, Integer endColumn, String severity, String message) {

    static DiagnosticJson grid(String columnId, Integer row, String message) {
        return new DiagnosticJson(row == null ? "column" : "cell", columnId, row, null, null, null, null, null, null,
                "error", message);
    }

    static DiagnosticJson source(String alias, String message) {
        return new DiagnosticJson("source", null, null, alias, null, null, null, null, null, "error", message);
    }

    static DiagnosticJson join(int join, String message) {
        return new DiagnosticJson("join", null, null, null, join, null, null, null, null, "error", message);
    }

    static DiagnosticJson design(String message) {
        return new DiagnosticJson("design", null, null, null, null, null, null, null, null, "error", message);
    }

    static DiagnosticJson sql(Diagnostic d) {
        return new DiagnosticJson("sql", null, null, null, null, d.line(), d.column(), d.endLine(), d.endColumn(),
                "error", d.message());
    }
}
