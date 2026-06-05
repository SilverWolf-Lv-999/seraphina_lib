package seraphina.seraphina_lib.mixin.annotation;

import seraphina.seraphina_lib.mixin.util.InsertPosition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a custom ASM injection handler.
 * <p>
 * The current transformer discovers this annotation but ignores the handler to
 * avoid loading the mixin class during transformation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ASM {
    /**
     * Desired insertion position.
     *
     * @return insertion position for the handler
     */
    InsertPosition at() default InsertPosition.CUSTOM;

    /**
     * Target method names in the transformed class.
     *
     * @return method names to match
     */
    String[] methodName();

    /**
     * Target method descriptor.
     *
     * @return JVM method descriptor
     */
    String desc();
}
