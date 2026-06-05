package seraphina.seraphina_lib.mixin.util;

import lombok.Getter;
import lombok.Setter;
import org.objectweb.asm.tree.AbstractInsnNode;

/**
 * Runtime state passed to injected callback handlers.
 * <p>
 * A callback can mark itself as cancelled and optionally carry a replacement
 * return value for the transformed method.
 */
@Getter
public class CallBackInfo {
    /**
     * Whether the callback has requested cancellation.
     */
    @Setter
    private boolean back = false;
    /**
     * Replacement return value supplied by the callback.
     */
    private Object backValue = null;
    /**
     * Instruction node associated with the callback point.
     */
    @Setter
    private AbstractInsnNode abstractInsnNode = null;

    /**
     * Marks the callback as cancelled.
     */
    public void cancel() {
        this.back = true;
    }

    /**
     * Sets a replacement return value and marks the callback as cancelled.
     *
     * @param backValue value returned to the original caller
     */
    public void setBackValue(Object backValue) {
        this.cancel();
        this.backValue = backValue;
    }
}
