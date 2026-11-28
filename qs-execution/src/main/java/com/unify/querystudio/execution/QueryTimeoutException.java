package com.unify.querystudio.execution;

import java.time.Duration;

/** The run took longer than its timeout and was stopped. */
public final class QueryTimeoutException extends RuntimeException {

    public QueryTimeoutException(Duration timeout, Throwable cause) {
        super("The query ran for longer than " + timeout.toSeconds() + " seconds and was stopped.", cause);
    }
}
