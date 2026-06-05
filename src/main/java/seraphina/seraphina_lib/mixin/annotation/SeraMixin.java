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
}
