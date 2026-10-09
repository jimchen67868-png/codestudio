package ksp.com.github.benmanes.caffeine.cache;

import java.util.function.Consumer;

/**
 * Replacement read buffer: the original extends StripedBuffer, whose static
 * initializer reads Thread.threadLocalRandomProbe through Unsafe, a field
 * Android's Thread does not have. This one is a small lock-protected ring.
 */
public final class BoundedBuffer<E> implements Buffer<E> {
    static final int BUFFER_SIZE = 16;
    static final int MASK = BUFFER_SIZE - 1;

    private final Object lock = new Object();
    private final Object[] ring = new Object[BUFFER_SIZE];
    private long head;
    private long tail;

    public BoundedBuffer() {}

    @Override
    public int offer(E e) {
        synchronized (lock) {
            if (tail - head >= BUFFER_SIZE) {
                return Buffer.FULL;
            }
            ring[(int) (tail & MASK)] = e;
            tail++;
            return Buffer.SUCCESS;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void drainTo(Consumer<E> consumer) {
        Object[] drained;
        int n;
        synchronized (lock) {
            n = (int) (tail - head);
            if (n == 0) return;
            drained = new Object[n];
            for (int i = 0; i < n; i++) {
                int idx = (int) ((head + i) & MASK);
                drained[i] = ring[idx];
                ring[idx] = null;
            }
            head = tail;
        }
        for (int i = 0; i < n; i++) {
            consumer.accept((E) drained[i]);
        }
    }

    @Override
    public int reads() {
        synchronized (lock) { return (int) head; }
    }

    @Override
    public int writes() {
        synchronized (lock) { return (int) tail; }
    }
}
