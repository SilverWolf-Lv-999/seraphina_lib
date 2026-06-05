package seraphina.seraphina_lib.mixin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redirects selected method invocations inside target methods to the annotated
 * mixin handler method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {
    /**
     * Methods in the transformed class that should be scanned for redirect targets.
     *
     * @return target method names
     */
    String[] methodName();

    /**
     * Descriptor for methods listed in {@link #methodName()}.
     *
     * @return JVM method descriptor
     */
    String methodDesc();

    /**
     * Invoked methods that should be redirected.
     * <p>
     * Values may use either internal-name slash syntax or dot syntax.
     *
     * @return invoked methods to match
     */
    String[] targetMethod();

    /**
     * Descriptor for methods listed in {@link #targetMethod()}.
     *
     * @return JVM method descriptor
     */
    String targetMethodDesc();
}
