package seraphina.seraphina_lib.service;

import org.objectweb.asm.tree.ClassNode;

public interface ISeraMixin {
    String getMixinPath();

    default int getPriority() {
        return 0;
    }

    default void onLoad() {}

    default boolean shouldApplyMixin(ClassNode targetClassNod, ClassNode mixinClassNode) {
        return true;
    }
}
