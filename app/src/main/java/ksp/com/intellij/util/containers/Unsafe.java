package ksp.com.intellij.util.containers;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Replacement for IntelliJ's own Unsafe helper (shadows the copy inside
 * ksp-isolated.jar via parent-first classloading). The original looks up
 * sun.misc.Unsafe.getAndAddInt via MethodHandles, which Android's Unsafe
 * does not expose, so its static initializer throws. Here every method is
 * resolved lazily and getAndAddInt is built from a CAS loop.
 */
public final class Unsafe {
    private static Object unsafe;
    private static Method getObjectVolatile, putObjectVolatile, casObject, casInt, casLong;
    private static Method getIntVolatile, objectFieldOffset, arrayIndexScale, arrayBaseOffset, copyMemory;

    static {
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            getObjectVolatile = find(c, "getObjectVolatile", Object.class, long.class);
            putObjectVolatile = find(c, "putObjectVolatile", Object.class, long.class, Object.class);
            casObject = find(c, "compareAndSwapObject", Object.class, long.class, Object.class, Object.class);
            casInt = find(c, "compareAndSwapInt", Object.class, long.class, int.class, int.class);
            casLong = find(c, "compareAndSwapLong", Object.class, long.class, long.class, long.class);
            getIntVolatile = find(c, "getIntVolatile", Object.class, long.class);
            objectFieldOffset = find(c, "objectFieldOffset", Field.class);
            arrayIndexScale = find(c, "arrayIndexScale", Class.class);
            arrayBaseOffset = find(c, "arrayBaseOffset", Class.class);
            copyMemory = find(c, "copyMemory", Object.class, long.class, Object.class, long.class, long.class);
        } catch (Throwable t) {
            throw new Error(t);
        }
    }

    private Unsafe() {}

    private static Method find(Class<?> c, String name, Class<?>... params) {
        try {
            return c.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call(Method m, Object... args) {
        if (m == null) throw new UnsupportedOperationException("sun.misc.Unsafe method unavailable on this device");
        try {
            return m.invoke(unsafe, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean compareAndSwapInt(Object o, long offset, int expected, int x) {
        return (Boolean) call(casInt, o, offset, expected, x);
    }

    public static boolean compareAndSwapLong(Object o, long offset, long expected, long x) {
        return (Boolean) call(casLong, o, offset, expected, x);
    }

    public static int getAndAddInt(Object o, long offset, int delta) {
        int v;
        do {
            v = (Integer) call(getIntVolatile, o, offset);
        } while (!compareAndSwapInt(o, offset, v, v + delta));
        return v;
    }

    public static Object getObjectVolatile(Object o, long offset) {
        return call(getObjectVolatile, o, offset);
    }

    public static boolean compareAndSwapObject(Object o, long offset, Object expected, Object x) {
        return (Boolean) call(casObject, o, offset, expected, x);
    }

    public static void putObjectVolatile(Object o, long offset, Object x) {
        call(putObjectVolatile, o, offset, x);
    }

    public static long objectFieldOffset(Field field) {
        return (Long) call(objectFieldOffset, field);
    }

    public static int arrayIndexScale(Class<?> arrayClass) {
        return (Integer) call(arrayIndexScale, arrayClass);
    }

    public static int arrayBaseOffset(Class<?> arrayClass) {
        return (Integer) call(arrayBaseOffset, arrayClass);
    }

    public static void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
        call(copyMemory, srcBase, srcOffset, destBase, destOffset, bytes);
    }
}
