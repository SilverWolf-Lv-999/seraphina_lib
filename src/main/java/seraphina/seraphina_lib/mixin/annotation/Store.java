package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes local variable store points that should be rewritten by the transformer.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Store {
    /**
     * Local variable indexes to modify.
     *
     * @return local variable indexes
     */
    int[] index();

    /**
     * JVM store opcodes matching {@link #index()}.
     *
     * @return store opcodes
     */
    int[] opcode();
}
