package seraphina.seraphina_lib.mixin.annotation;

import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.mixin.util.InsertShift;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that provides an instruction position for custom injection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InjectPoint {

    /**
     * Target method names used to resolve the injection point.
     *
     * @return target method names
     */
    String[] methodName() default {};

    /**
     * Injection point type used by this custom point.
     *
     * @return insertion point type
     */
    InsertPosition at() default InsertPosition.CUSTOM;

    /**
     * Instruction index used by the point resolver.
     *
     * @return instruction index
     */
    int index() default 1;

    /**
     * Compact instruction target string. See {@link Inject#target()} for the
     * supported forms.
     *
     * @return instruction target string
     */
    String target() default "";

    /**
     * Owner class for instruction-level injection points.
     *
     * @return internal or dot class name
     */
    String owner() default "";

    /**
     * Member name for invocation or field injection points.
     *
     * @return target member name
     */
    String name() default "";

    /**
     * Descriptor for the matched instruction target.
     *
     * @return method or field descriptor
     */
    String targetDesc() default "";

    /**
     * Zero-based matched-instruction ordinal. Use {@code -1} to match all
     * compatible instructions.
     *
     * @return matched instruction ordinal
     */
    int ordinal() default -1;

    /**
     * ASM opcode filter. Use {@code -1} to omit opcode filtering.
     *
     * @return ASM opcode filter
     */
    int opcode() default -1;

    /**
     * Insertion direction relative to the resolved instruction.
     *
     * @return shift mode
     */
    InsertShift shift() default InsertShift.DEFAULT;

    /**
     * Number of real bytecode instructions to move after resolving the base
     * injection point.
     *
     * @return instruction offset
     */
    int by() default 0;

    /**
     * Target method descriptor.
     *
     * @return JVM method descriptor, or an empty string to omit descriptor matching
     */
    String desc() default "";
}
