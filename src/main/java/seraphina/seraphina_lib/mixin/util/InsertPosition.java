package seraphina.seraphina_lib.mixin.util;

import lombok.Getter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

@Getter
public enum InsertPosition {
    STR(true),
    CUSTOM(true),
    NONE(true),
    HEAD(false),
    LAST(true);
    private MethodNode methodNode;
    private boolean isBefore;

    InsertPosition(boolean isBefore) {
        this.isBefore = isBefore;
    }

    public void setMethodNode(MethodNode methodNode) {
        this.methodNode = methodNode;
    }

    public void setBefore(boolean before) {
        isBefore = before;
    }

    public void insert(AbstractInsnNode point, InsnList list) {
        if (isBefore()) {
            getMethodNode().instructions.insertBefore(point, list);
        } else {
            getMethodNode().instructions.insert(point, list);
        }
    }

    public AbstractInsnNode getPosition(InsertPosition insertPosition) {
        return switch (insertPosition) {
            case HEAD -> getMethodNode().instructions.getFirst();
            case LAST -> getMethodNode().instructions.get(getMethodNode().instructions.size() - 2);
            default -> null;
        };
    }
}
