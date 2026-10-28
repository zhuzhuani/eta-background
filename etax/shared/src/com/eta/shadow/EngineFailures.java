package com.eta.shadow;

/** Reflection and Binder wrappers must not hide a dead Android system service. */
public final class EngineFailures {
    private EngineFailures() { }
    public static boolean systemUnavailable(Throwable error) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable value = error; value != null && seen.add(value); value = value.getCause()) {
            String name = value.getClass().getName();
            if (name.equals("android.os.DeadObjectException") || name.equals("android.os.DeadSystemException")
                    || name.equals("android.os.DeadSystemRuntimeException")) return true;
        }
        return false;
    }
}
