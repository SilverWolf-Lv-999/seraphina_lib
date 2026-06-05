package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Declares that a mixin member represents a member on the target class.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Shadow {
    /**
     * Target member name.
     * <p>
     * When empty, the mixin member name is used.
     *
     * @return target member name override
     */
    String value() default "" ;
}
