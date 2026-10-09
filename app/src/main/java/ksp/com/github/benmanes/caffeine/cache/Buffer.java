package ksp.com.github.benmanes.caffeine.cache;

import java.util.function.Consumer;

/**
 * Public replacement for Caffeine's package-private Buffer (shadows the copy
 * in ksp-isolated.jar via parent-first classloading). Must be public: a
 * package-private class in the app's classloader cannot be accessed from the
 * KSP bundle's classloader even with an identical package name.
 */
public interface Buffer<E> {
    int FULL = 1;
    int SUCCESS = 0;
    int FAILED = -1;

    @SuppressWarnings("unchecked")
    static <E> Buffer<E> disabled() {
        return (Buffer<E>) new Buffer<Object>() {
            @Override public int offer(Object e) { return SUCCESS; }
            @Override public void drainTo(Consumer<Object> consumer) {}
            @Override public int reads() { return 0; }
            @Override public int writes() { return 0; }
        };
    }

    int offer(E e);

    void drainTo(Consumer<E> consumer);

    default int size() { return writes() - reads(); }

    int reads();

    int writes();
}
