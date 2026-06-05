package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes whether an injection callback can cancel execution and provide a
 * return value.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface CallbackInfo {
    /**
     * Whether the callback should return to the caller instead of continuing.
     *
     * @return {@code true} when the callback is allowed to return
     */
    boolean callback() default false;

    /**
     * Return value type used when {@link #callback()} is enabled.
     *
     * @return return value type
     */
    Class<?> type() default void.class;
}
