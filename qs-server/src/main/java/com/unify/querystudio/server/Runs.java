package com.unify.querystudio.server;

import com.unify.querystudio.execution.RunHandle;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runs in progress, by run ID, so that the user who started one can cancel it. */
@ApplicationScoped
public class Runs {

    private record Running(String user, RunHandle handle) {
    }

    private final Map<String, Running> running = new ConcurrentHashMap<>();

    /** False when the ID is already in use. */
    boolean register(String runId, String user, RunHandle handle) {
        return running.putIfAbsent(runId, new Running(user, handle)) == null;
    }

    void finished(String runId) {
        running.remove(runId);
    }

    /** Cancels the run if this user started it; false otherwise, without saying whether it exists. */
    boolean cancel(String runId, String user) {
        Running r = running.get(runId);
        if (r == null || !r.user().equals(user)) {
            return false;
        }
        r.handle().cancel();
        return true;
    }
}
