package seraphina.seraphina_lib.mixin.annotation;

import seraphina.seraphina_lib.mixin.util.InsertPosition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the annotated mixin method into one or more target methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
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

    /**
     * Position where the handler should be inserted.
     *
     * @return insertion position
     */
    InsertPosition at() default InsertPosition.CUSTOM;

    /**
     * 返回信息
     *
     * @return 返回值类型
     */
    CallbackInfo callback() default @CallbackInfo();
}
