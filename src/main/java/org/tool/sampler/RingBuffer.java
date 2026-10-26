package org.tool.sampler;

/**
 * Fixed-capacity circular buffer of doubles. When full, the oldest value is overwritten,
 * so memory use stays constant no matter how long the app runs. Thread-safe.
 */
public class RingBuffer {
    private final double[] data;
    private int head = 0;      // index of the next write
    private int size = 0;

    public RingBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1");
        data = new double[capacity];
    }

    public synchronized void add(double value) {
        data[head] = value;
        head = (head + 1) % data.length;
        if (size < data.length) size++;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return data.length;
    }

    /** A copy of the contents, oldest value first. */
    public synchronized double[] toArray() {
        double[] out = new double[size];
        int start = (head - size + data.length) % data.length;
        for (int i = 0; i < size; i++) out[i] = data[(start + i) % data.length];
        return out;
    }
}