package seraphina.seraphina_lib.service;

import org.objectweb.asm.tree.ClassNode;

public interface ISeraMixin {
    /**
     * Returns the root package that contains Sera mixin classes.
     * Classes under this package and its subpackages are scanned recursively.
     */
    String getMixinPath();

    /**
     * Returns the mapping resource path inside the jar.
     * Empty means this provider does not use SeraMixin remapping.
     */
    default String getMappingPath() {
        return "assets/seraphina_lib/srg/minecraft.srg";
    }

    /**
     * Returns the mapping type used both as the output file extension and parser hint.
     * Returning "srg" with minecraft.srg writes <gamepath>/seraphina_mixin/srg/minecraft.srg.
     */
    default String getMappingType() {
        return "srg";
    }

    default int getPriority() {
        return 0;
    }

    default void onLoad() {}

    default boolean shouldApplyMixin(ClassNode targetClassNod, ClassNode mixinClassNode) {
        return true;
    }
}
