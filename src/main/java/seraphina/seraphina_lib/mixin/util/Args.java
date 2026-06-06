package seraphina.seraphina_lib.mixin.util;

import java.util.Arrays;

/**
 * Mutable argument container used by {@code @ModifyArgs} handlers.
 */
public final class Args {
    private final Object[] values;

    public Args(Object[] values) {
        this.values = values == null ? new Object[0] : values;
    }

    public int size() {
        return this.values.length;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        return (T) this.values[index];
    }

    public void set(int index, Object value) {
        this.values[index] = value;
    }

    public Object[] values() {
        return Arrays.copyOf(this.values, this.values.length);
    }
}
