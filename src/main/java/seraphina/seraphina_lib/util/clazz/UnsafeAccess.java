package seraphina.seraphina_lib.util.clazz;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Narrow wrapper around {@link Unsafe} operations used by reflection helpers.
 * <p>
 * Keeping direct {@code Unsafe} calls in this class makes the rest of the code
 * easier to audit and allows callers to avoid repeating lookup boilerplate.
 */
public final class UnsafeAccess {
    private static final Unsafe UNSAFE = findUnsafe();

    private UnsafeAccess() {
    }

    /**
     * Returns the base object used for accessing a static field.
     *
     * @param field static field
     * @return base object for {@code Unsafe} static field access
     */
    public static Object staticFieldBase(Field field) {
        return UNSAFE.staticFieldBase(field);
    }

    /**
     * Returns the {@code Unsafe} offset for a static field.
     *
     * @param field static field
     * @return field offset
     */
    public static long staticFieldOffset(Field field) {
        return UNSAFE.staticFieldOffset(field);
    }

    /**
     * Returns the {@code Unsafe} offset for an instance field.
     *
     * @param field instance field
     * @return field offset
     */
    public static long objectFieldOffset(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }

    /**
     * Reads an object reference from the supplied target and offset.
     *
     * @param target object or static base
     * @param offset field offset
     * @return object value at the offset
     */
    public static Object getObject(Object target, long offset) {
        return UNSAFE.getObject(target, offset);
    }

    /**
     * Reads a volatile object reference from the supplied target and offset.
     *
     * @param target object or static base
     * @param offset field offset
     * @return volatile object value at the offset
     */
    public static Object getObjectVolatile(Object target, long offset) {
        return UNSAFE.getObjectVolatile(target, offset);
    }

    /**
     * Writes an object reference at the supplied target and offset.
     *
     * @param target object or static base
     * @param offset field offset
     * @param value value to write
     */
    public static void putObject(Object target, long offset, Object value) {
        UNSAFE.putObject(target, offset, value);
    }

    /**
     * Writes a boolean value at the supplied target and offset.
     *
     * @param target object or static base
     * @param offset field offset
     * @param value value to write
     */
    public static void putBoolean(Object target, long offset, boolean value) {
        UNSAFE.putBoolean(target, offset, value);
    }

    private static Unsafe findUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
