package seraphina.seraphina_lib.service;

import org.objectweb.asm.tree.ClassNode;

/**
 * Service provider contract for registering SeraMixin packages and mapping data.
 * <p>
 * Implementations are discovered by the mixin service and can decide which
 * target classes should receive transformations.
 */
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

    /**
     * Returns the load priority for this provider.
     * <p>
     * Higher priority providers are processed before lower priority providers.
     *
     * @return provider priority, where {@code 0} is the default
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Called after the provider is loaded by the mixin discovery service.
     */
    default void onLoad() {}

    /**
     * Decides whether a discovered mixin should be applied to a target class.
     *
     * @param targetClassNod ASM node for the class being transformed
     * @param mixinClassNode ASM node for the mixin class
     * @return {@code true} to apply the mixin
     */
    default boolean shouldApplyMixin(ClassNode targetClassNod, ClassNode mixinClassNode) {
        return true;
    }
}
