package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface Shadow {
    String value() default "" ;
}
