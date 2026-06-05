package seraphina.seraphina_lib.mixin.util;

import lombok.Getter;
import lombok.Setter;
import org.objectweb.asm.tree.AbstractInsnNode;

@Getter
public class CallBackInfo {
    /**
     * is return
     */
    @Setter
    private boolean back = false;
    private Object backValue = null;
    @Setter
    private AbstractInsnNode abstractInsnNode = null;

    public void cancel() {
        this.back = true;
    }

    public void setBackValue(Object backValue) {
        this.cancel();
        this.backValue = backValue;
    }
}
