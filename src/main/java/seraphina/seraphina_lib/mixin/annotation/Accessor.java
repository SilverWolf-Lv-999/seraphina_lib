package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an accessor method for a field on the target class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Accessor {
    /**
     * Target field name.
     * <p>
     * When empty, the field name is inferred from the accessor method name.
     *
     * @return target field name override
     */
    String value() default "";

    /**
     * Whether the target field is static.
     *
     * @return {@code true} for static fields
     */
    boolean isStatic() default false;
}
