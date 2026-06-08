package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated class is a SeraMixin for a target class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SeraMixin {
    /**
     * Target class to transform.
     *
     * @return target class
     */
    Class<?> value();

    /**
     * Physical Forge distribution where this mixin should be registered.
     * <p>
     * Use {@link DIST#BOTH} for common classes that should be transformed in
     * both client and dedicated server launches.
     *
     * @return distribution where this mixin is active
     */
    DIST shouldRun() default DIST.BOTH;

    /**
     * Mixin registration distribution filter.
     */
    enum DIST {
        /**
         * Register the mixin on all Forge distributions.
         */
        BOTH,
        /**
         * Register the mixin only on the physical client.
         */
        CLIENT,
        /**
         * Register the mixin only on the dedicated server.
         */
        SERVER
    }
}
