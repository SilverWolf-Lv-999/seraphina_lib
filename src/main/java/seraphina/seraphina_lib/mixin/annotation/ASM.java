package seraphina.seraphina_lib.mixin.annotation;

import seraphina.seraphina_lib.mixin.util.InsertPosition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ASM {
    InsertPosition at() default InsertPosition.CUSTOM;

    String[] methodName();

    String desc();
}
