package seraphina.seraphina_lib.mixin.service;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class MixinHierarchyResolver {
    private final Map<String, List<ClassInfo>> registeredMixins;
    private final MixinClassProvider classProvider;
    private final Map<String, String> subclassCache = new ConcurrentHashMap<>();
    private final Map<String, ClassHierarchyInfo> hierarchyCache = new ConcurrentHashMap<>();

    MixinHierarchyResolver(Map<String, List<ClassInfo>> registeredMixins, MixinClassProvider classProvider) {
        this.registeredMixins = registeredMixins;
        this.classProvider = classProvider;
    }

    void cacheLoadedClass(String internalName, ClassNode classNode) {
        this.hierarchyCache.put(internalName, new ClassHierarchyInfo(
                internalName,
                classNode.superName,
                (classNode.access & Opcodes.ACC_INTERFACE) != 0));
    }

    String findParentTarget(String internalName, ClassNode classNode, ClassLoader loader) {
        String cached = this.subclassCache.get(internalName);
        if (cached != null) {
            return cached.equals(MixinConstants.NO_PARENT_TARGET) ? null : cached;
        }

        String superName = classNode.superName;
        if (superName == null || superName.equals("java/lang/Object")) {
            this.subclassCache.put(internalName, MixinConstants.NO_PARENT_TARGET);
            return null;
        }
        if (this.registeredMixins.containsKey(superName)) {
            this.subclassCache.put(internalName, superName);
            return superName;
        }

        HashSet<String> visited = new HashSet<>();
        visited.add(internalName);
        String result = this.checkInheritanceForTarget(superName, loader, visited, 0);
        this.subclassCache.put(internalName, result != null ? result : MixinConstants.NO_PARENT_TARGET);
        return result;
    }

    boolean isSubclassASM(String childInternal, String parentInternal, ClassLoader loader) {
        if (childInternal == null || parentInternal == null) {
            return false;
        }
        if (childInternal.equals(parentInternal)) {
            return true;
        }
        String current = childInternal;
        for (int i = 0; i < 50; i++) {
            ClassHierarchyInfo info = this.getClassHierarchyInfo(current, loader);
            if (info == null || info.superName == null) {
                return false;
            }
            if (info.superName.equals(parentInternal)) {
                return true;
            }
            if (info.superName.equals("java/lang/Object")) {
                return false;
            }
            current = info.superName;
        }
        return false;
    }

    private String checkInheritanceForTarget(String currentInternal, ClassLoader loader, Set<String> visited, int depth) {
        if (this.registeredMixins.containsKey(currentInternal)) {
            return currentInternal;
        }
        if (currentInternal == null || currentInternal.equals("java/lang/Object")) {
            return null;
        }
        if (MixinConstants.isPlatformOrLoaderClass(currentInternal)) {
            return null;
        }
        if (!visited.add(currentInternal) || depth > 50) {
            return null;
        }
        String superName = this.getSuperClassName(currentInternal, loader);
        return superName == null ? null : this.checkInheritanceForTarget(superName, loader, visited, depth + 1);
    }

    private String getSuperClassName(String internalName, ClassLoader loader) {
        ClassHierarchyInfo info = this.getClassHierarchyInfo(internalName, loader);
        if (info != null && info.superName != null) {
            return info.superName;
        }
        return this.resolveSuperByReflect(internalName, loader);
    }

    private String resolveSuperByReflect(String internalName, ClassLoader loader) {
        if (MixinConstants.isPlatformOrLoaderClass(internalName)) {
            return null;
        }
        String binary = internalName.replace('/', '.');
        for (ClassLoader candidate : this.classProvider.classLoaderCandidates(loader)) {
            try {
                Class<?> clazz = Class.forName(binary, false, candidate);
                Class<?> superClass = clazz.getSuperclass();
                String superInternal = superClass == null ? null : Type.getInternalName(superClass);
                this.hierarchyCache.put(internalName, new ClassHierarchyInfo(internalName, superInternal, clazz.isInterface()));
                return superInternal;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private ClassHierarchyInfo getClassHierarchyInfo(String internalName, ClassLoader loader) {
        if (MixinConstants.isPlatformOrLoaderClass(internalName)) {
            return null;
        }
        ClassHierarchyInfo cached = this.hierarchyCache.get(internalName);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = this.classProvider.loadClassBytesRelaxed(internalName, loader);
        if (bytes == null) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassHierarchyInfo info = new ClassHierarchyInfo(
                    internalName,
                    reader.getSuperName(),
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
            this.hierarchyCache.put(internalName, info);
            return info;
        } catch (Throwable throwable) {
            System.err.println("[SeraMixin] Failed to read hierarchy for " + internalName + ": " + throwable.getMessage());
            return null;
        }
    }
}
