package com.unify.querystudio.compiler;

/**
 * A problem found while compiling, with its position in the canonical SQL (1-based; 0 when the
 * problem concerns the whole statement). The design mapper translates positions back to design elements.
 */
public record Diagnostic(int line, int column, int endLine, int endColumn, String message) {

    public static Diagnostic whole(String message) {
        return new Diagnostic(0, 0, 0, 0, message);
    }
}
