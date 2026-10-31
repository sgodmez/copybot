package com.copybot.engine;

import com.copybot.config.SecretReferences;

import java.util.concurrent.RejectedExecutionException;

/**
 * The failures of a step's action without the secret values it was configured with (spec remote §1), whatever their
 * kind: a runtime exception, an error, a checked exception thrown sneakily. The engine's control exceptions, an
 * interrupt and the JVM failing are left as they are: the callers tell them apart by their type.
 */
public final class Redaction {

    private Redaction() {
    }

    /** The failure without the values of the redactor, or the very same failure when there is nothing to hide. */
    public static Throwable hidden(SecretReferences.Redactor redactor, Throwable failure) {
        if (failure instanceof PhaseStopped || failure instanceof RejectedExecutionException
                || failure instanceof InterruptedException || failure instanceof VirtualMachineError) {
            return failure;
        }
        Throwable hidden = redactor.redact(failure);
        return hidden == null ? failure : hidden;
    }

    /**
     * {@link #hidden} of the failure, to throw ({@code throw Redaction.rethrow(...)}): returned when it is a runtime
     * exception, thrown from here otherwise (an error, or a checked exception thrown on sneakily, as it came).
     */
    public static RuntimeException rethrow(SecretReferences.Redactor redactor, Throwable failure) {
        return unchecked(hidden(redactor, failure));
    }

    /** The failure to throw as it is: returned when it is a runtime exception, thrown from here otherwise. */
    static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException e) {
            return e;
        }
        throw Redaction.<RuntimeException>sneaky(failure);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable failure) throws E {
        throw (E) failure;
    }
}
