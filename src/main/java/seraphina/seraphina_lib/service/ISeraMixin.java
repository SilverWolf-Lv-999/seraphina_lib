package seraphina.seraphina_lib.service;

import org.objectweb.asm.tree.ClassNode;

public interface ISeraMixin {
    /**
     * Returns the root package that contains Sera mixin classes.
     * Classes under this package and its subpackages are scanned recursively.
     */
    String getMixinPath();

    /**
     * Returns the mapping resource path inside this provider jar.
     * Example: assets/example/mappings/mapping.txt.
     * Empty means this provider does not use SeraMixin remapping.
     */
    default String getMappingPath() {
        return "";
    }

    /**
     * Returns the mapping type used both as the output file extension and parser hint.
     * Returning "srg" with mapping.txt writes <gamepath>/seraphina_mixin/srg/mapping.srg.
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
