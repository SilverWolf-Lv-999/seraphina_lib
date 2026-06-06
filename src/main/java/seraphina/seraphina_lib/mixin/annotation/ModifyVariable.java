package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rewrites matching local variable loads or stores through the annotated
 * handler method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyVariable {
    /**
     * Target method names in the transformed class.
     *
     * @return method names to scan
     */
    String[] methodName();

    /**
     * Target method descriptor.
     *
     * @return JVM method descriptor
     */
    String desc();

    /**
     * Local variable index to match. Use {@code -1} to match all compatible
     * indexes.
     *
     * @return local variable index
     */
    int index() default -1;

    /**
     * Variable type filter. Leave as {@code void.class} to infer from the
     * handler argument.
     *
     * @return variable type
     */
    Class<?> type() default void.class;

    /**
     * Zero-based matched variable instruction ordinal. Use {@code -1} to match
     * all compatible instructions.
     *
     * @return matched variable ordinal
     */
    int ordinal() default -1;

    /**
     * ASM variable opcode filter. Use {@code -1} to omit opcode filtering.
     *
     * @return ASM opcode filter
     */
    int opcode() default -1;

    /**
     * Whether variable load instructions should be rewritten.
     *
     * @return {@code true} to rewrite loads
     */
    boolean load() default true;

    /**
     * Whether variable store instructions should be rewritten.
     *
     * @return {@code true} to rewrite stores
     */
    boolean store() default true;
}
