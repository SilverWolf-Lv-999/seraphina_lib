package seraphina.seraphina_lib.mixin.annotation;

import seraphina.seraphina_lib.mixin.util.InsertPosition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
    String[] methodName();

    String desc();

    InsertPosition at() default InsertPosition.CUSTOM;

    /**
     * 返回信息
     *
     * @return 返回值类型
     */
    CallbackInfo callback() default @CallbackInfo();
}
