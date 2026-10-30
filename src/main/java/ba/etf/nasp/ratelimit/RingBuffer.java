package ba.etf.nasp.ratelimit;

import java.util.NoSuchElementException;

/**
 * Bounded FIFO queue backed by a fixed array used as a circle. {@code head} is the index of the
 * oldest element; the next free slot is {@code (head + size) % capacity}. Both indexes wrap
 * around, so offering and polling are O(1) and never move elements or allocate.
 *
 * <p>Not thread-safe: callers synchronize access.
 */
public final class RingBuffer<T> {

    private final Object[] slots;
    private int head;
    private int size;

    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.slots = new Object[capacity];
    }

    /** Appends an element at the tail; returns false, leaving the buffer unchanged, when full. */
    public boolean offer(T element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        if (size == slots.length) {
            return false;
        }
        slots[(head + size) % slots.length] = element;
        size++;
        return true;
    }

    /** Removes and returns the oldest element. */
    @SuppressWarnings("unchecked")
    public T poll() {
        if (size == 0) {
            throw new NoSuchElementException("Ring buffer is empty");
        }
        T element = (T) slots[head];
        slots[head] = null;   // let the element be garbage collected
        head = (head + 1) % slots.length;
        size--;
        return element;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return slots.length;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isFull() {
        return size == slots.length;
    }
}
