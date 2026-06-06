package seraphina.seraphina_lib.mixin.util;

import lombok.Getter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

/**
 * Insertion positions used by SeraMixin when adding instructions to a method.
 */
@Getter
public enum InsertPosition {
    /**
     * Inserts before the matched string or search result position.
     */
    STR(true),

    /**
     * Uses a custom point supplied by an injection point resolver.
     */
    CUSTOM(true),

    /**
     * Does not resolve an automatic insertion point.
     */
    NONE(true),

    /**
     * Inserts at the first instruction of the target method.
     */
    HEAD(false),

    /**
     * Inserts before the last return instruction of the target method.
     */
    TAIL(true),

    /**
     * Inserts before return instructions in the target method.
     */
    RETURN(true),

    /**
     * Inserts around matched method invocation instructions.
     */
    INVOKE(true),

    /**
     * Inserts around matched field access instructions.
     */
    FIELD(true),

    /**
     * Inserts around matched object allocation instructions.
     */
    NEW(true),

    /**
     * Inserts around matched jump instructions.
     */
    JUMP(true),

    /**
     * Inserts before return instructions in the target method.
     */
    LAST(true);

    /**
     * Current ASM method node used for insertion operations.
     */
    private MethodNode methodNode;

    /**
     * Whether insertion occurs before the resolved point.
     */
    private boolean isBefore;

    InsertPosition(boolean isBefore) {
        this.isBefore = isBefore;
    }

    /**
     * Sets the ASM method node used for insertion operations.
     *
     * @param methodNode current target method node
     */
    public void setMethodNode(MethodNode methodNode) {
        this.methodNode = methodNode;
    }

    /**
     * Controls whether insertion occurs before or after the resolved point.
     *
     * @param before {@code true} to insert before the point
     */
    public void setBefore(boolean before) {
        isBefore = before;
    }

    /**
     * Inserts an instruction list around the supplied point according to this position.
     *
     * @param point instruction anchor
     * @param list instructions to insert
     */
    public void insert(AbstractInsnNode point, InsnList list) {
        if (isBefore()) {
            getMethodNode().instructions.insertBefore(point, list);
        } else {
            getMethodNode().instructions.insert(point, list);
        }
    }

    /**
     * Resolves the concrete instruction node for a known automatic position.
     *
     * @param insertPosition position to resolve
     * @return resolved instruction node, or {@code null} for custom/manual positions
     */
    public AbstractInsnNode getPosition(InsertPosition insertPosition) {
        return switch (insertPosition) {
            case HEAD -> getMethodNode().instructions.getFirst();
            case TAIL -> getMethodNode().instructions.get(getMethodNode().instructions.size() - 2);
            case RETURN, LAST -> getMethodNode().instructions.get(getMethodNode().instructions.size() - 2);
            default -> null;
        };
    }
}
