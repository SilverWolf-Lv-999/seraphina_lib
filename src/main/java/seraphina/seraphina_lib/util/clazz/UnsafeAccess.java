package seraphina.seraphina_lib.util.clazz;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

public final class UnsafeAccess {
    private static final Unsafe UNSAFE = findUnsafe();

    private UnsafeAccess() {
    }

    public static Object staticFieldBase(Field field) {
        return UNSAFE.staticFieldBase(field);
    }

    public static long staticFieldOffset(Field field) {
        return UNSAFE.staticFieldOffset(field);
    }

    public static long objectFieldOffset(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }

    public static Object getObject(Object target, long offset) {
        return UNSAFE.getObject(target, offset);
    }

    public static Object getObjectVolatile(Object target, long offset) {
        return UNSAFE.getObjectVolatile(target, offset);
    }

    public static void putObject(Object target, long offset, Object value) {
        UNSAFE.putObject(target, offset, value);
    }

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
