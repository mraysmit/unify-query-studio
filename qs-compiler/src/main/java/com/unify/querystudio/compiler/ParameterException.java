package com.unify.querystudio.compiler;

/** A named parameter is written wrongly, or has no value or a value of the wrong type. */
public final class ParameterException extends RuntimeException {

    public ParameterException(String message) {
        super(message);
    }
}
