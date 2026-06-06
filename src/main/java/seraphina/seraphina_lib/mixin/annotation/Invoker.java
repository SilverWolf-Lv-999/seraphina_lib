package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an invoker method for a method on the target class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Invoker {
    /**
     * Target method name.
     * <p>
     * When empty, the method name is inferred from the invoker method name.
     *
     * @return target method name override
     */
    String value() default "";

    /**
     * Target method descriptor. When empty, the invoker method descriptor is used.
     *
     * @return JVM method descriptor override
     */
    String desc() default "";

    /**
     * Whether the target method is static.
     *
     * @return {@code true} for static target methods
     */
    boolean isStatic() default false;
}
