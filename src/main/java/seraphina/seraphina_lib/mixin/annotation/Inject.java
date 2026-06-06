package seraphina.seraphina_lib.mixin.annotation;

import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.mixin.util.InsertShift;

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
     * Compact target string for instruction-level injection points.
     * <p>
     * For method calls this may be {@code owner/name(desc)ret} or
     * {@code Lowner;name(desc)ret}; for fields it may be
     * {@code owner/name:desc}; for {@link InsertPosition#NEW} it may be the
     * allocated class name.
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
     * Member name for {@link InsertPosition#INVOKE} or
     * {@link InsertPosition#FIELD}.
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
     * ASM opcode filter, for example {@code Opcodes.INVOKEVIRTUAL} or
     * {@code Opcodes.GETFIELD}. Use {@code -1} to omit opcode filtering.
     *
     * @return ASM opcode filter
     */
    int opcode() default -1;

    /**
     * Zero-based raw instruction index used by {@link InsertPosition#CUSTOM}.
     *
     * @return target instruction index
     */
    int index() default -1;

    /**
     * Insertion direction relative to the resolved instruction.
     *
     * @return shift mode
     */
    InsertShift shift() default InsertShift.DEFAULT;

    /**
     * Number of real bytecode instructions to move after resolving the base
     * injection point. Positive values move forward and negative values move
     * backward.
     *
     * @return instruction offset
     */
    int by() default 0;

    /**
     * 返回信息
     *
     * @return 返回值类型
     */
    CallbackInfo callback() default @CallbackInfo();
}
