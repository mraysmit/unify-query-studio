package com.unify.querystudio.execution;

/** The run was cancelled before it finished. */
public final class QueryCancelledException extends RuntimeException {

    public QueryCancelledException(Throwable cause) {
        super("The query was cancelled.", cause);
    }
}
