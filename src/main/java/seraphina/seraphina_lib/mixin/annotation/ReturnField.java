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
    /**
     * Target field names.
     *
     * @return field names to intercept
     */
    String[] field();

    /**
     * Field type used by the generated access hook.
     *
     * @return field type
     */
    Class<?> type();

    /**
     * Whether the target field is static.
     *
     * @return {@code true} for static fields
     */
    boolean isStatic() default false;

    /**
     * Whether field reads should be intercepted.
     *
     * @return {@code true} to intercept read access
     */
    boolean read() default true;

    /**
     * Whether field writes should be intercepted.
     *
     * @return {@code true} to intercept write access
     */
    boolean write() default true;
}
