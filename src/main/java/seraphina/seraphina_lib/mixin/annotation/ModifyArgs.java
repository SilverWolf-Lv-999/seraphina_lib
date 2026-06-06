package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets a handler mutate arguments immediately before a matched method
 * invocation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyArgs {
    /**
     * Methods in the transformed class that should be scanned.
     *
     * @return target method names
     */
    String[] methodName();

    /**
     * Descriptor for methods listed in {@link #methodName()}.
     *
     * @return JVM method descriptor
     */
    String methodDesc();

    /**
     * Invoked methods whose arguments should be exposed to the handler.
     *
     * @return invoked methods to match
     */
    String[] targetMethod();

    /**
     * Descriptor for methods listed in {@link #targetMethod()}.
     *
     * @return JVM method descriptor
     */
    String targetMethodDesc();

    /**
     * Zero-based matched invocation ordinal. Use {@code -1} to match all
     * compatible invocations.
     *
     * @return matched invocation ordinal
     */
    int ordinal() default -1;

    /**
     * ASM invoke opcode filter. Use {@code -1} to omit opcode filtering.
     *
     * @return ASM opcode filter
     */
    int opcode() default -1;
}
