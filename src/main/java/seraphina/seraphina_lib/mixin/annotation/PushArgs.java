package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes local variable indexes and opcodes used when pushing handler
 * arguments during bytecode transformation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface PushArgs {
    /**
     * Local variable indexes to read.
     *
     * @return local variable indexes
     */
    int[] index();

    /**
     * JVM variable opcodes matching {@link #index()}.
     *
     * @return variable load opcodes
     */
    int[] opcode();
}
