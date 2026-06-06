package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces matching constant loads with the value returned by the annotated
 * handler method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyConstant {
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
     * Constant value to match. Leave empty to match by handler argument type.
     *
     * @return textual constant value
     */
    String constant() default "";

    /**
     * Constant type filter. Leave as {@code void.class} to infer from the
     * handler argument.
     *
     * @return constant type
     */
    Class<?> type() default void.class;

    /**
     * Zero-based matched constant ordinal. Use {@code -1} to match all
     * compatible constants.
     *
     * @return matched constant ordinal
     */
    int ordinal() default -1;

    /**
     * ASM opcode filter. Use {@code -1} to omit opcode filtering.
     *
     * @return ASM opcode filter
     */
    int opcode() default -1;
}
