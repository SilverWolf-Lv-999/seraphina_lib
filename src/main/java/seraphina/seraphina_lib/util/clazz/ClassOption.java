package seraphina.seraphina_lib.util.clazz;


import java.util.Set;

/**
 * Options passed to the hidden-class definition path.
 * <p>
 * Values map directly to the flag bits expected by the JVM internal
 * {@code ClassLoader#defineClass0} entry point.
 */
public enum ClassOption {
    /**
     * Defines the hidden class as a nestmate of the lookup class.
     */
    NESTMATE(1),

    /**
     * Keeps the hidden class strongly reachable from its defining loader.
     */
    STRONG(4);

    private final int flag;

    ClassOption(int flag) {
        this.flag = flag;
    }

    /**
     * Converts a set of options to the bit mask used by the class definition call.
     *
     * @param options options to combine
     * @return combined JVM option flags
     */
    public static int optionsToFlag(Set<ClassOption> options) {
        int flags = 0;
        for (ClassOption cp : options) {
            flags |= cp.flag;
        }
        return flags;
    }
}
