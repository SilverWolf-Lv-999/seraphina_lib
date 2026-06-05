package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rewrites access to the selected field through the annotated public static handler.
 * Instance field handler: (Target self, T value)T.
 * Static field handler: (T value)T.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReturnField {
    String[] field();

    Class<?> type();

    boolean isStatic() default false;

    boolean read() default true;

    boolean write() default true;
}
