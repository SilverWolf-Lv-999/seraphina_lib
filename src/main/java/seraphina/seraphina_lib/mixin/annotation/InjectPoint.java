package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that provides an instruction position for custom injection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InjectPoint {

    /**
     * Target method names used to resolve the injection point.
     *
     * @return target method names
     */
    String[] methodName();

    /**
     * Instruction index used by the point resolver.
     *
     * @return instruction index
     */
    int index() default 1;

    /**
     * Target method descriptor.
     *
     * @return JVM method descriptor, or an empty string to omit descriptor matching
     */
    String desc() default "";
}
