package com.unify.querystudio.compiler;

import java.util.List;

/** The canonical SQL could not be compiled; {@link #diagnostics()} says where and why. */
public final class CompilationException extends RuntimeException {

    private final List<Diagnostic> diagnostics;

    public CompilationException(List<Diagnostic> diagnostics, Throwable cause) {
        super(diagnostics.getFirst().message(), cause);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
