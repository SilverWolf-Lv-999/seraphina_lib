package seraphina.seraphina_lib.mixin.util;

/**
 * Direction used after an injection point has resolved a matching bytecode
 * instruction.
 */
public enum InsertShift {
    /**
     * Uses the default direction defined by the selected {@link InsertPosition}.
     */
    DEFAULT,

    /**
     * Inserts before the resolved instruction.
     */
    BEFORE,

    /**
     * Inserts after the resolved instruction.
     */
    AFTER,

    /**
     * Inserts before the instruction selected by {@code by}.
     */
    BY
}
