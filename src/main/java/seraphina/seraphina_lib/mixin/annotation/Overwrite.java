package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces one or more target methods with the annotated mixin method body.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Overwrite {
    /**
     * Target method names in the transformed class.
     *
     * @return method names to overwrite
     */
    String[] methodName();

    /**
     * Target method descriptor.
     *
     * @return JVM method descriptor
     */
    String desc();
}
