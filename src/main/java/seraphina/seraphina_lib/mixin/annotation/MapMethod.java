package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Associates a field with one or more target methods and their descriptor.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MapMethod {
    /**
     * Target method names.
     *
     * @return method names to map
     */
    String[] methodName();

    /**
     * Target method descriptor.
     *
     * @return JVM method descriptor
     */
    String desc();
}
