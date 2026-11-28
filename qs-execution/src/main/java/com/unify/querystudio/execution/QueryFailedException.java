package com.unify.querystudio.execution;

import java.sql.SQLException;

/** The database refused or failed the statement; the message is the database's. */
public final class QueryFailedException extends RuntimeException {

    public QueryFailedException(SQLException cause) {
        super(cause.getMessage(), cause);
    }
}
