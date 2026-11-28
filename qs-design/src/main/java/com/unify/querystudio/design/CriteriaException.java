package com.unify.querystudio.design;

/** A criteria cell that cannot be read; the message is shown against the cell. */
public final class CriteriaException extends RuntimeException {

    public CriteriaException(String message) {
        super(message);
    }

    public CriteriaException(String message, Throwable cause) {
        super(message, cause);
    }
}
