package seraphina.seraphina_lib.mixin.service;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import seraphina.seraphina_lib.mixin.annotation.ASM;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.Overwrite;
import seraphina.seraphina_lib.mixin.annotation.Redirect;
import seraphina.seraphina_lib.mixin.annotation.ReturnField;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.annotation.Shadow;
import seraphina.seraphina_lib.mixin.util.CallBackInfo;
import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.service.ISeraMixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Seraphina
 * @version 2.6
 * */
public class SeraMixinLaunchPluginService implements ILaunchPluginService {
    private static final String SERVICE_FILE = "META-INF/services/" + ISeraMixin.class.getName();
    private static final String SHADOW_CLASS = Type.getDescriptor(Shadow.class);
    private static final String INJECT_CLASS = Type.getDescriptor(Inject.class);
    private static final String ASM_CLASS = Type.getDescriptor(ASM.class);
    private static final String REDIRECT_CLASS = Type.getDescriptor(Redirect.class);
    private static final String OVERWRITE_CLASS = Type.getDescriptor(Overwrite.class);
    private static final String RETURN_FIELD_CLASS = Type.getDescriptor(ReturnField.class);
    private static final String SERA_MIXIN_CLASS = Type.getDescriptor(SeraMixin.class);
    private static final String CALL_BACK_INFO = Type.getInternalName(CallBackInfo.class);
    private static final String NO_PARENT_TARGET = "<NO_PARENT>";
    private static final List<PendingMixin> PENDING_MIXINS = new CopyOnWriteArrayList<>();
    private static volatile SeraMixinLaunchPluginService currentService;

    private final Map<String, List<ClassInfo>> registeredMixins = new ConcurrentHashMap<>();
    private final Map<String, TransformerHolder> activeTransformers = new ConcurrentHashMap<>();
    private final Map<String, String> subclassCache = new ConcurrentHashMap<>();
    private final Map<String, ClassHierarchyInfo> hierarchyCache = new ConcurrentHashMap<>();
    private final Map<String, byte[]> classBytesCache = new ConcurrentHashMap<>();
    private final Set<String> queuedServiceProviders = ConcurrentHashMap.newKeySet();
    private final Set<String> loadedServiceProviders = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<String> currentTargetClass = new ThreadLocal<>();
    private final ThreadLocal<Boolean> isSubclassMode = new ThreadLocal<>();
    private final ThreadLocal<Boolean> hasPrintedShadowHeader = new ThreadLocal<>();
    private volatile boolean serviceDiscoveryDone;
    private volatile boolean returnFieldHoldersInitialized;
    private volatile ITransformerLoader transformerLoader;

    public SeraMixinLaunchPluginService() {
        currentService = this;
        this.drainPendingMixins();
    }

    @Override
    public String name() {
        return "seraphina_mixin_plugin_service";
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        return this.handlesClass(classType, isEmpty, null);
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty, String reason) {
        if (isEmpty || classType == null || "computing_frames".equals(reason) || this.name().equals(reason)) {
            return EnumSet.noneOf(Phase.class);
        }
        String internalName = classType.getInternalName();
        if (isPlatformOrLoaderClass(internalName)) {
            return EnumSet.noneOf(Phase.class);
        }
        this.ensureMixinServicesLoaded();
        return this.hasRegisteredMixins() ? EnumSet.of(Phase.BEFORE) : EnumSet.noneOf(Phase.class);
    }

    @Override
    public int processClassWithFlags(Phase phase, ClassNode classNode, Type classType, String reason) {
        if (phase != Phase.BEFORE || classNode == null || classType == null) {
            return ComputeFlags.NO_REWRITE;
        }
        if ("computing_frames".equals(reason) || this.name().equals(reason)) {
            return ComputeFlags.NO_REWRITE;
        }
        String internalName = classNode.name != null ? classNode.name : classType.getInternalName();
        if (isPlatformOrLoaderClass(internalName)) {
            return ComputeFlags.NO_REWRITE;
        }
        this.ensureMixinServicesLoaded();
        if (!this.hasRegisteredMixins()) {
            return ComputeFlags.NO_REWRITE;
        }

        this.currentTargetClass.set(internalName.replace('/', '.'));
        this.isSubclassMode.set(false);
        this.hasPrintedShadowHeader.set(false);
        try {
            this.hierarchyCache.put(internalName, new ClassHierarchyInfo(
                    internalName,
                    classNode.superName,
                    (classNode.access & Opcodes.ACC_INTERFACE) != 0));
            boolean changed = this.applyClassNodeTransform(classNode, internalName, this.getRuntimeClassLoader());
            if (!changed) {
                return ComputeFlags.NO_REWRITE;
            }
            this.fixMethodBounds(classNode);
            return ComputeFlags.COMPUTE_FRAMES;
        } catch (Throwable throwable) {
            System.err.println("[SeraMixin] Failed to transform " + internalName + ": " + throwable.getMessage());
            throwable.printStackTrace(System.err);
            return ComputeFlags.NO_REWRITE;
        } finally {
            this.currentTargetClass.remove();
            this.isSubclassMode.remove();
            this.hasPrintedShadowHeader.remove();
        }
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType) {
        return this.processClassWithFlags(phase, classNode, classType, null) != ComputeFlags.NO_REWRITE;
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType, String reason) {
        return this.processClassWithFlags(phase, classNode, classType, reason) != ComputeFlags.NO_REWRITE;
    }

    @Override
    public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
        this.transformerLoader = transformerLoader;
    }

    @Override
    public void addResources(List<SecureJar> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        for (SecureJar resource : resources) {
            this.readServiceProviderFile(resource);
        }
        this.serviceDiscoveryDone = false;
    }

    @Override
    public void offerResource(Path resource, String name) {
        if (resource == null) {
            return;
        }
        String normalizedName = name == null ? resource.toString().replace('\\', '/') : name.replace('\\', '/');
        if (normalizedName.endsWith(SERVICE_FILE)) {
            this.readServiceProviderFile(resource);
            this.serviceDiscoveryDone = false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getExtension() {
        return (T) this;
    }

    public static void registerMixin(Class<?> mixinClass) {
        Objects.requireNonNull(mixinClass, "mixinClass");
        SeraMixinLaunchPluginService service = currentService;
        if (service != null) {
            service.register(mixinClass);
        } else {
            PENDING_MIXINS.add(PendingMixin.ofClass(mixinClass));
        }
    }

    public static void registerMixin(String mixinClassName, ClassLoader mixinClassLoader) {
        Objects.requireNonNull(mixinClassName, "mixinClassName");
        SeraMixinLaunchPluginService service = currentService;
        if (service != null) {
            service.register(mixinClassName, mixinClassLoader);
        } else {
            PENDING_MIXINS.add(PendingMixin.ofMixinName(mixinClassName, mixinClassLoader));
        }
    }

    public static void registerMixin(String mixinClassName, String targetClassName, ClassLoader mixinClassLoader) {
        Objects.requireNonNull(mixinClassName, "mixinClassName");
        Objects.requireNonNull(targetClassName, "targetClassName");
        SeraMixinLaunchPluginService service = currentService;
        if (service != null) {
            service.register(mixinClassName, targetClassName, mixinClassLoader);
        } else {
            PENDING_MIXINS.add(PendingMixin.ofNames(mixinClassName, targetClassName, mixinClassLoader));
        }
    }

    public void register(Class<?> mixinClass) {
        this.register(mixinClass, null);
    }

    public void register(String mixinClassName, ClassLoader mixinClassLoader) {
        this.registerMixinFromASM(mixinClassName, mixinClassLoader, null);
    }

    public void register(String mixinClassName, String targetClassName, ClassLoader mixinClassLoader) {
        this.register(mixinClassName, targetClassName, mixinClassLoader, null, 0);
    }

    public String[] getTarget() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (List<ClassInfo> infos : this.registeredMixins.values()) {
            for (ClassInfo info : infos) {
                targets.add(info.targetInternalName);
            }
        }
        return targets.toArray(new String[0]);
    }

    public String[] getReturnFieldTargets() {
        this.ensureReturnFieldHoldersInitialized(this.getRuntimeClassLoader());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (TransformerHolder holder : this.activeTransformers.values()) {
            if (holder == null || holder.returnFieldPoints.isEmpty()) {
                continue;
            }
            for (ReturnFieldPoint point : holder.returnFieldPoints) {
                targets.add(point.ownerInternalName + "/" + point.fieldName + " " + point.fieldDesc);
            }
        }
        return targets.toArray(new String[0]);
    }

    private static boolean isPlatformOrLoaderClass(String internalName) {
        if (internalName == null) {
            return true;
        }
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/")
                || internalName.startsWith("org/w3c/")
                || internalName.startsWith("org/xml/")
                || internalName.startsWith("org/objectweb/asm/")
                || internalName.startsWith("cpw/mods/securejarhandler/")
                || internalName.startsWith("cpw/mods/modlauncher/")
                || internalName.startsWith("seraphina/seraphina_lib/mixin/service/");
    }

    private void ensureMixinServicesLoaded() {
        this.drainPendingMixins();
        if (this.serviceDiscoveryDone) {
            return;
        }
        synchronized (this) {
            this.drainPendingMixins();
            if (this.serviceDiscoveryDone) {
                return;
            }
            this.loadQueuedProviders();
            this.loadServiceLoaderProviders(Thread.currentThread().getContextClassLoader());
            this.loadServiceLoaderProviders(this.getClass().getClassLoader());
            this.serviceDiscoveryDone = true;
        }
    }

    private void drainPendingMixins() {
        for (PendingMixin pending : PENDING_MIXINS) {
            if (PENDING_MIXINS.remove(pending)) {
                pending.apply(this);
            }
        }
    }

    private void loadQueuedProviders() {
        for (String providerClassName : new ArrayList<>(this.queuedServiceProviders)) {
            if (!this.loadedServiceProviders.add(providerClassName)) {
                continue;
            }
            try {
                Class<?> providerClass = this.resolveClass(providerClassName, this.getRuntimeClassLoader());
                if (!ISeraMixin.class.isAssignableFrom(providerClass)) {
                    System.err.println("[SeraMixin] Service provider does not implement ISeraMixin: " + providerClassName);
                    continue;
                }
                ISeraMixin provider = (ISeraMixin) providerClass.getDeclaredConstructor().newInstance();
                this.registerProvider(provider);
            } catch (Throwable throwable) {
                System.err.println("[SeraMixin] Failed to load service provider " + providerClassName + ": " + throwable.getMessage());
            }
        }
    }

    private void loadServiceLoaderProviders(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            ServiceLoader<ISeraMixin> loader = ServiceLoader.load(ISeraMixin.class, classLoader);
            for (ISeraMixin provider : loader) {
                if (provider == null || !this.loadedServiceProviders.add(provider.getClass().getName())) {
                    continue;
                }
                this.registerProvider(provider);
            }
        } catch (ServiceConfigurationError error) {
            System.err.println("[SeraMixin] Failed to load ISeraMixin services: " + error.getMessage());
        }
    }

    private void registerProvider(ISeraMixin provider) {
        try {
            provider.onLoad();
        } catch (Throwable throwable) {
            System.err.println("[SeraMixin] ISeraMixin.onLoad failed for " + provider.getClass().getName() + ": " + throwable.getMessage());
        }

        String mixinPath = null;
        try {
            mixinPath = provider.getMixinPath();
        } catch (Throwable throwable) {
            System.err.println("[SeraMixin] ISeraMixin.getMixinPath failed for " + provider.getClass().getName() + ": " + throwable.getMessage());
        }

        if (mixinPath != null && !mixinPath.isBlank()) {
            try {
                this.registerMixinFromASM(mixinPath, provider.getClass().getClassLoader(), provider);
                return;
            } catch (Throwable throwable) {
                System.err.println("[SeraMixin] Failed to register mixin " + mixinPath + ": " + throwable.getMessage());
            }
        }

        if (this.hasSeraMixinAnnotation(provider.getClass().getName(), provider.getClass().getClassLoader())) {
            this.registerMixinFromASM(provider.getClass().getName(), provider.getClass().getClassLoader(), provider);
        }
    }

    private void register(Class<?> mixinClass, ISeraMixin hook) {
        if (mixinClass == null) {
            return;
        }
        this.registerMixinFromASM(mixinClass.getName(), mixinClass.getClassLoader(), hook);
    }

    private void registerMixinFromASM(String mixinClassName, ClassLoader mixinClassLoader, ISeraMixin hook) {
        String normalizedMixin = normalizeClassName(mixinClassName);
        String targetInternalName = this.readSeraMixinTargetInternalName(normalizedMixin, mixinClassLoader);
        if (targetInternalName == null || targetInternalName.isBlank()) {
            System.err.println("[SeraMixin] Missing @SeraMixin target on " + normalizedMixin);
            return;
        }
        int priority = hook == null ? 0 : safePriority(hook);
        this.register(normalizedMixin, targetInternalName, mixinClassLoader, hook, priority);
    }

    private boolean hasSeraMixinAnnotation(String mixinClassName, ClassLoader mixinClassLoader) {
        return this.readSeraMixinTargetInternalName(mixinClassName, mixinClassLoader) != null;
    }

    private String readSeraMixinTargetInternalName(String mixinClassName, ClassLoader mixinClassLoader) {
        String normalizedMixin = normalizeClassName(mixinClassName);
        byte[] mixinBytes;
        try {
            mixinBytes = this.loadMixinBytes(normalizedMixin, mixinClassLoader);
        } catch (IOException exception) {
            System.err.println("[SeraMixin] Failed to read mixin bytes for " + normalizedMixin + ": " + exception.getMessage());
            return null;
        }

        ClassNode classNode = new ClassNode();
        try {
            new ClassReader(mixinBytes).accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable throwable) {
            System.err.println("[SeraMixin] Failed to parse mixin annotations for " + normalizedMixin + ": " + throwable.getMessage());
            return null;
        }

        AnnotationNode annotation = findAnnotation(classNode.visibleAnnotations, classNode.invisibleAnnotations, SERA_MIXIN_CLASS);
        if (annotation == null) {
            return null;
        }
        return annotationClassInternalName(annotationNodeValue(annotation, "value"));
    }

    private void register(String mixinClassName, String targetClassName, ClassLoader mixinClassLoader, ISeraMixin hook, int priority) {
        String normalizedMixin = normalizeClassName(mixinClassName);
        String targetInternal = toInternalName(targetClassName);
        ClassLoader loader = mixinClassLoader != null ? mixinClassLoader : this.getRuntimeClassLoader();
        ClassInfo info = new ClassInfo(normalizedMixin, targetInternal, loader, hook, priority);
        this.registeredMixins.compute(targetInternal, (target, oldValue) -> {
            ArrayList<ClassInfo> next = oldValue == null ? new ArrayList<>() : new ArrayList<>(oldValue);
            next.removeIf(existing -> existing.mixinClassName.equals(info.mixinClassName));
            next.add(info);
            next.sort(Comparator
                    .comparingInt(ClassInfo::priority).reversed()
                    .thenComparing(ClassInfo::mixinClassName));
            return List.copyOf(next);
        });
        this.returnFieldHoldersInitialized = false;
    }

    private static int safePriority(ISeraMixin hook) {
        try {
            return hook.getPriority();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean hasRegisteredMixins() {
        for (List<ClassInfo> infos : this.registeredMixins.values()) {
            if (infos != null && !infos.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean applyClassNodeTransform(ClassNode classNode, String internalName, ClassLoader loader) {
        boolean changed = false;
        List<ClassInfo> directInfos = this.registeredMixins.get(internalName);
        if (directInfos != null && !directInfos.isEmpty()) {
            this.isSubclassMode.set(false);
            changed |= this.applyMixins(classNode, directInfos, loader, internalName, false);
        } else {
            String parentTarget = this.findParentTarget(internalName, classNode, loader);
            if (parentTarget != null) {
                List<ClassInfo> parentInfos = this.registeredMixins.get(parentTarget);
                if (parentInfos != null && !parentInfos.isEmpty()) {
                    this.isSubclassMode.set(true);
                    changed |= this.applyMixins(classNode, parentInfos, loader, internalName, true);
                }
            }
        }

        if (this.applyReturnFields(classNode, loader, this.collectReturnFieldPoints(loader))) {
            changed = true;
        }
        return changed;
    }

    private boolean applyMixins(ClassNode classNode, List<ClassInfo> infos, ClassLoader loader, String actualClassName, boolean isSubclass) {
        boolean changed = false;
        for (ClassInfo info : infos) {
            try {
                TransformerHolder holder = this.getOrCreateTransformerHolder(info, loader, "Failed to scan mixin: ");
                if (info.hook != null && !info.hook.shouldApplyMixin(classNode, holder.mixinClassNode)) {
                    continue;
                }
                changed |= this.applyTransform(classNode, holder, loader, actualClassName, isSubclass);
            } catch (Throwable throwable) {
                System.err.println("[SeraMixin] Failed to apply " + info.mixinClassName + " to " + actualClassName + ": " + throwable.getMessage());
                throwable.printStackTrace(System.err);
            }
        }
        return changed;
    }

    private String findParentTarget(String internalName, ClassNode classNode, ClassLoader loader) {
        String cached = this.subclassCache.get(internalName);
        if (cached != null) {
            return cached.equals(NO_PARENT_TARGET) ? null : cached;
        }

        String superName = classNode.superName;
        if (superName == null || superName.equals("java/lang/Object")) {
            this.subclassCache.put(internalName, NO_PARENT_TARGET);
            return null;
        }
        if (this.registeredMixins.containsKey(superName)) {
            this.subclassCache.put(internalName, superName);
            return superName;
        }

        HashSet<String> visited = new HashSet<>();
        visited.add(internalName);
        String result = this.checkInheritanceForTarget(superName, loader, visited, 0);
        this.subclassCache.put(internalName, result != null ? result : NO_PARENT_TARGET);
        return result;
    }

    private String checkInheritanceForTarget(String currentInternal, ClassLoader loader, Set<String> visited, int depth) {
        if (this.registeredMixins.containsKey(currentInternal)) {
            return currentInternal;
        }
        if (currentInternal == null || currentInternal.equals("java/lang/Object")) {
            return null;
        }
        if (isPlatformOrLoaderClass(currentInternal)) {
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
        if (isPlatformOrLoaderClass(internalName)) {
            return null;
        }
        String binary = internalName.replace('/', '.');
        for (ClassLoader candidate : this.classLoaderCandidates(loader)) {
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
        if (isPlatformOrLoaderClass(internalName)) {
            return null;
        }
        ClassHierarchyInfo cached = this.hierarchyCache.get(internalName);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = this.loadClassBytesRelaxed(internalName, loader);
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

    private boolean isSubclassASM(String childInternal, String parentInternal, ClassLoader loader) {
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

    private TransformerHolder getOrCreateTransformerHolder(ClassInfo info, ClassLoader loader, String errorPrefix) {
        TransformerHolder holder = this.activeTransformers.get(info.key());
        if (holder != null) {
            return holder;
        }
        try {
            TransformerHolder created = new TransformerHolder(this, info.mixinClassName, info.targetInternalName,
                    loader, this.hasPrintedShadowHeader, this.currentTargetClass, info.mixinClassLoader);
            TransformerHolder existing = this.activeTransformers.putIfAbsent(info.key(), created);
            return existing == null ? created : existing;
        } catch (Throwable throwable) {
            throw new RuntimeException(errorPrefix + info.mixinClassName, throwable);
        }
    }

    private void ensureReturnFieldHoldersInitialized(ClassLoader loader) {
        if (this.returnFieldHoldersInitialized) {
            return;
        }
        synchronized (this.activeTransformers) {
            if (this.returnFieldHoldersInitialized) {
                return;
            }
            for (ClassInfo info : this.allClassInfos()) {
                this.getOrCreateTransformerHolder(info, loader, "ReturnField scan failed: ");
            }
            this.returnFieldHoldersInitialized = true;
        }
    }

    private List<ReturnFieldPoint> collectReturnFieldPoints(ClassLoader loader) {
        this.ensureReturnFieldHoldersInitialized(loader);
        ArrayList<ReturnFieldPoint> points = new ArrayList<>();
        for (TransformerHolder holder : this.activeTransformers.values()) {
            if (holder != null && !holder.returnFieldPoints.isEmpty()) {
                points.addAll(holder.returnFieldPoints);
            }
        }
        return points;
    }

    private List<ClassInfo> allClassInfos() {
        ArrayList<ClassInfo> result = new ArrayList<>();
        for (List<ClassInfo> infos : this.registeredMixins.values()) {
            result.addAll(infos);
        }
        result.sort(Comparator
                .comparingInt(ClassInfo::priority).reversed()
                .thenComparing(ClassInfo::mixinClassName));
        return result;
    }

    private boolean applyTransform(ClassNode classNode, TransformerHolder holder, ClassLoader loader, String actualClassName, boolean isSubclass) throws Exception {
        boolean anyMatched = false;
        String mixinInternal = holder.mixinClassName.replace('.', '/');
        String targetInternal = holder.targetInternalName;
        ArrayList<MethodNode> lambdaMethodsToAdd = new ArrayList<>();
        ArrayList<MethodNode> injectHandlerMethodsToAdd = new ArrayList<>();
        HashMap<String, InjectHandlerCall> injectHandlerCalls = new HashMap<>();
        boolean hasPrintedMethodHeader = false;

        for (MethodNode method : classNode.methods) {
            for (OverwritePoint point : holder.overwritePoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                if (!hasPrintedMethodHeader && !isSubclass) {
                    hasPrintedMethodHeader = true;
                }
                this.applyOverwrite(method, point, holder, targetInternal, mixinInternal, classNode, lambdaMethodsToAdd);
                anyMatched = true;
            }
        }
        if (!lambdaMethodsToAdd.isEmpty()) {
            classNode.methods.addAll(lambdaMethodsToAdd);
        }

        for (MethodNode method : classNode.methods) {
            for (InjectPoint point : holder.injectPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                if (!hasPrintedMethodHeader && !isSubclass) {
                    hasPrintedMethodHeader = true;
                }
                anyMatched = true;
                this.applyMixinInject(method, point, holder, loader, actualClassName, classNode,
                        injectHandlerMethodsToAdd, injectHandlerCalls);
            }
        }
        if (!injectHandlerMethodsToAdd.isEmpty()) {
            classNode.methods.addAll(injectHandlerMethodsToAdd);
        }

        for (MethodNode method : classNode.methods) {
            for (RedirectPoint point : holder.redirectPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                if (!hasPrintedMethodHeader && !isSubclass) {
                    hasPrintedMethodHeader = true;
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (!(insn instanceof MethodInsnNode methodInsn)) {
                        continue;
                    }
                    for (TargetCall targetCall : point.targetCalls) {
                        if (!targetCall.matches(methodInsn.owner, methodInsn.name, methodInsn.desc)) {
                            continue;
                        }
                        methodInsn.setOpcode(Opcodes.INVOKESTATIC);
                        methodInsn.owner = mixinInternal;
                        methodInsn.name = point.mixinMethodName;
                        methodInsn.desc = point.mixinMethodDesc;
                        methodInsn.itf = false;
                        anyMatched = true;
                    }
                }
            }
        }
        return anyMatched;
    }

    private void applyOverwrite(MethodNode target, OverwritePoint point, TransformerHolder holder, String targetInternal,
                                String mixinInternal, ClassNode targetClass, List<MethodNode> lambdaMethodsToAdd) throws IOException, NoSuchMethodException {
        MethodNode mixinMethod = holder.rewrittenMethodCache.get(point.mixinMethodName + point.mixinMethodDesc);
        if (mixinMethod == null) {
            throw new NoSuchMethodException("Cannot find overwrite method: " + point.mixinMethodName + point.mixinMethodDesc);
        }

        MethodNode cloned = holder.cloneMethod(mixinMethod);
        this.rewriteSelfReferences(cloned, mixinInternal, targetInternal, holder.shadowFields, holder.shadowMethods);
        target.instructions = cloned.instructions;
        target.tryCatchBlocks = cloned.tryCatchBlocks;
        target.localVariables = cloned.localVariables;
        target.maxStack = Math.max(cloned.maxStack, 0);
        target.maxLocals = Math.max(cloned.maxLocals, 0);
        this.copyLambdaMethods(point.mixinClassName, point.mixinMethodName, targetClass, holder, targetInternal, mixinInternal, lambdaMethodsToAdd);
    }

    private void copyLambdaMethods(String mixinClassName, String methodName, ClassNode targetClass, TransformerHolder holder,
                                   String targetInternal, String mixinInternal, List<MethodNode> lambdaMethodsToAdd) throws IOException {
        byte[] mixinBytes = this.loadMixinBytes(mixinClassName, holder.mixinClassLoader);
        ClassNode mixinClass = new ClassNode();
        new ClassReader(mixinBytes).accept(mixinClass, ClassReader.EXPAND_FRAMES);
        String dotPrefix = "lambda." + methodName + ".";
        String dollarPrefix = "lambda$" + methodName + "$";

        for (MethodNode mixinMethod : mixinClass.methods) {
            boolean lambdaName = mixinMethod.name.startsWith(dotPrefix) || mixinMethod.name.startsWith(dollarPrefix);
            boolean exists = this.hasMethod(targetClass, lambdaMethodsToAdd, mixinMethod.name, mixinMethod.desc);
            if (!lambdaName || exists) {
                continue;
            }
            MethodNode clonedLambda = holder.cloneMethod(mixinMethod);
            this.rewriteSelfReferences(clonedLambda, mixinInternal, targetInternal, holder.shadowFields, holder.shadowMethods);
            clonedLambda.desc = clonedLambda.desc.replace(mixinInternal, targetInternal);
            clonedLambda.access = (clonedLambda.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;
            lambdaMethodsToAdd.add(clonedLambda);
        }
    }

    private void rewriteSelfReferences(MethodNode method, String mixinInternal, String targetInternal,
                                       Map<String, ShadowFieldInfo> shadowFields,
                                       Map<String, ShadowMethodInfo> shadowMethods) {
        if (method.desc.contains(mixinInternal)) {
            method.desc = method.desc.replace(mixinInternal, targetInternal);
        }
        InsnList instructions = method.instructions;
        if (instructions == null) {
            return;
        }

        for (AbstractInsnNode node : instructions.toArray()) {
            if (node instanceof FieldInsnNode field) {
                if (field.owner.equals(mixinInternal)) {
                    field.owner = targetInternal;
                }
                ShadowFieldInfo shadowField = shadowFields.get(field.name);
                if (shadowField != null) {
                    field.name = shadowField.targetFieldName;
                    field.owner = targetInternal;
                }
                continue;
            }
            if (node instanceof MethodInsnNode methodCall) {
                if (methodCall.owner.equals(mixinInternal)) {
                    ShadowMethodInfo shadowMethod = shadowMethods.get(methodCall.name + methodCall.desc);
                    if (shadowMethod != null) {
                        methodCall.name = shadowMethod.targetMethodName;
                        if (methodCall.getOpcode() == Opcodes.INVOKESTATIC) {
                            methodCall.setOpcode(Opcodes.INVOKEVIRTUAL);
                            instructions.insertBefore(methodCall, new VarInsnNode(Opcodes.ALOAD, 0));
                        }
                    } else if (methodCall.name.startsWith("lambda." ) || methodCall.name.startsWith("lambda$")) {
                        methodCall.setOpcode(Opcodes.INVOKEVIRTUAL);
                    }
                    methodCall.owner = targetInternal;
                }
                continue;
            }
            if (node instanceof TypeInsnNode typeInsn) {
                if (typeInsn.desc.equals(mixinInternal)) {
                    typeInsn.desc = targetInternal;
                }
                continue;
            }
            if (node instanceof InvokeDynamicInsnNode indy) {
                this.rewriteInvokeDynamic(instructions, indy, mixinInternal, targetInternal);
            }
        }

        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                if (local.desc != null && local.desc.contains(mixinInternal)) {
                    local.desc = local.desc.replace(mixinInternal, targetInternal);
                }
                if (local.signature != null) {
                    local.signature = local.signature.replace(mixinInternal, targetInternal);
                }
            }
        }
    }

    private void rewriteInvokeDynamic(InsnList instructions, InvokeDynamicInsnNode indy, String mixinInternal, String targetInternal) {
        String newDesc = indy.desc.replace(mixinInternal, targetInternal);
        Object[] newBsmArgs = new Object[indy.bsmArgs.length];
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            Object arg = indy.bsmArgs[i];
            if (arg instanceof Type type) {
                String oldDesc = type.getDescriptor();
                String rewritten = oldDesc.replace(mixinInternal, targetInternal);
                if (!oldDesc.equals(rewritten)) {
                    newBsmArgs[i] = type.getSort() == Type.METHOD ? Type.getMethodType(rewritten) : Type.getType(rewritten);
                } else {
                    newBsmArgs[i] = arg;
                }
            } else if (arg instanceof Handle handle && handle.getOwner().equals(mixinInternal)) {
                int newTag = handle.getTag();
                if ((newTag == Opcodes.H_INVOKESTATIC || newTag == Opcodes.H_NEWINVOKESPECIAL)
                        && (handle.getName().startsWith("lambda.") || handle.getName().startsWith("lambda$"))) {
                    newTag = Opcodes.H_INVOKEVIRTUAL;
                }
                newBsmArgs[i] = new Handle(newTag, targetInternal, handle.getName(),
                        handle.getDesc().replace(mixinInternal, targetInternal), handle.isInterface());
            } else {
                newBsmArgs[i] = arg;
            }
        }
        instructions.set(indy, new InvokeDynamicInsnNode(indy.name, newDesc, indy.bsm, newBsmArgs));
    }

    private void applyMixinInject(MethodNode target, InjectPoint point, TransformerHolder holder, ClassLoader loader,
                                  String actualClassName, ClassNode classNode, List<MethodNode> injectHandlerMethodsToAdd,
                                  Map<String, InjectHandlerCall> injectHandlerCalls) throws NoSuchMethodException, IOException {
        Type targetType = Type.getMethodType(target.desc);
        Type[] targetArgs = targetType.getArgumentTypes();
        boolean targetIsInstance = (target.access & Opcodes.ACC_STATIC) == 0;
        String mixinInternal = point.mixinClassName.replace('.', '/');
        InjectHandlerCall handlerCall = point.mixinMethodStatic
                ? new InjectHandlerCall(Opcodes.INVOKESTATIC, mixinInternal, point.mixinMethodName, point.mixinMethodDesc, false, false)
                : this.ensureInjectHandlerMethod(point, holder, classNode, actualClassName, mixinInternal,
                injectHandlerMethodsToAdd, injectHandlerCalls, targetIsInstance);
        Type mixinMethodType = Type.getMethodType(handlerCall.desc);
        Type[] mixinArgTypes = mixinMethodType.getArgumentTypes();
        boolean injectThis = false;
        if (targetIsInstance && mixinArgTypes.length > 0 && mixinArgTypes[0].getSort() == Type.OBJECT) {
            String firstArg = mixinArgTypes[0].getInternalName();
            injectThis = firstArg.equals(holder.targetInternalName) || this.isSubclassASM(actualClassName, firstArg, loader);
        }

        InsnList inject = new InsnList();
        int baseLocalSlots = Type.getArgumentsAndReturnSizes(target.desc) >> 2;
        int localIndex = targetIsInstance ? 1 : 0;
        int callbackVar = -1;
        boolean hasCallback = false;
        int targetArgIndex = 0;
        int mixinArgIndex = 0;

        if (handlerCall.needsReceiver) {
            inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        if (injectThis) {
            inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
            mixinArgIndex++;
        }

        while (mixinArgIndex < mixinArgTypes.length) {
            Type mixinArg = mixinArgTypes[mixinArgIndex];
            String argInternal = mixinArg.getSort() == Type.OBJECT ? mixinArg.getInternalName() : "";
            if (CALL_BACK_INFO.equals(argInternal)) {
                inject.add(new TypeInsnNode(Opcodes.NEW, CALL_BACK_INFO));
                inject.add(new InsnNode(Opcodes.DUP));
                inject.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CALL_BACK_INFO, "<init>", "()V", false));
                callbackVar = baseLocalSlots;
                baseLocalSlots++;
                inject.add(new VarInsnNode(Opcodes.ASTORE, callbackVar));
                inject.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
                hasCallback = true;
            } else if (targetArgIndex < targetArgs.length) {
                inject.add(this.getLoadInsn(targetArgs[targetArgIndex], localIndex));
                localIndex += targetArgs[targetArgIndex].getSize();
                targetArgIndex++;
            } else {
                inject.add(this.getDefaultInsn(mixinArg));
            }
            mixinArgIndex++;
        }

        inject.add(new MethodInsnNode(handlerCall.opcode, handlerCall.owner, handlerCall.name, handlerCall.desc, handlerCall.isInterface));

        if (hasCallback) {
            LabelNode continueLabel = new LabelNode();
            inject.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
            inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALL_BACK_INFO, "isBack", "()Z", false));
            inject.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
            Type returnType = targetType.getReturnType();
            if (returnType.getSort() != Type.VOID) {
                inject.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
                inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALL_BACK_INFO, "getBackValue", "()Ljava/lang/Object;", false));
                inject.add(this.unboxAndReturn(returnType));
            } else {
                inject.add(new InsnNode(Opcodes.RETURN));
            }
            inject.add(continueLabel);
        }

        switch (point.position) {
            case HEAD -> {
                AbstractInsnNode first = target.instructions.getFirst();
                if (first != null) {
                    target.instructions.insertBefore(first, inject);
                } else {
                    target.instructions.add(inject);
                }
            }
            case LAST -> this.insertBeforeReturns(target, inject);
            default -> {
                AbstractInsnNode first = target.instructions.getFirst();
                if (first != null) {
                    target.instructions.insertBefore(first, inject);
                } else {
                    target.instructions.add(inject);
                }
            }
        }

        target.maxLocals = Math.max(target.maxLocals, baseLocalSlots);
        target.maxStack = Math.max(target.maxStack, 0);
    }

    private InjectHandlerCall ensureInjectHandlerMethod(InjectPoint point, TransformerHolder holder, ClassNode targetClass,
                                                        String actualClassName, String mixinInternal,
                                                        List<MethodNode> methodsToAdd,
                                                        Map<String, InjectHandlerCall> handlerCalls,
                                                        boolean targetIsInstance) throws NoSuchMethodException, IOException {
        if (!targetIsInstance) {
            throw new IllegalStateException("@Inject handler must be static when target method is static: "
                    + point.mixinClassName + "." + point.mixinMethodName + point.mixinMethodDesc);
        }

        String key = point.mixinClassName + '\n' + point.mixinMethodName + point.mixinMethodDesc;
        InjectHandlerCall existing = handlerCalls.get(key);
        if (existing != null) {
            return existing;
        }

        MethodNode mixinMethod = holder.rewrittenMethodCache.get(point.mixinMethodName + point.mixinMethodDesc);
        if (mixinMethod == null) {
            throw new NoSuchMethodException("Cannot find inject method: " + point.mixinMethodName + point.mixinMethodDesc);
        }

        MethodNode handler = holder.cloneMethod(mixinMethod);
        this.rewriteSelfReferences(handler, mixinInternal, actualClassName, holder.shadowFields, holder.shadowMethods);
        handler.name = this.uniqueInjectHandlerName(point, handler.desc, targetClass, methodsToAdd);
        handler.access = (handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
                | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        handler.visibleAnnotations = null;
        handler.invisibleAnnotations = null;
        handler.visibleParameterAnnotations = null;
        handler.invisibleParameterAnnotations = null;
        methodsToAdd.add(handler);
        this.copyLambdaMethods(point.mixinClassName, point.mixinMethodName, targetClass, holder,
                actualClassName, mixinInternal, methodsToAdd);

        InjectHandlerCall call = new InjectHandlerCall(Opcodes.INVOKESPECIAL, actualClassName, handler.name,
                handler.desc, (targetClass.access & Opcodes.ACC_INTERFACE) != 0, true);
        handlerCalls.put(key, call);
        return call;
    }

    private String uniqueInjectHandlerName(InjectPoint point, String methodDesc, ClassNode targetClass, List<MethodNode> methodsToAdd) {
        String baseName = "sera$inject$" + sanitizeMethodName(point.mixinMethodName) + "$"
                + Integer.toHexString(Objects.hash(point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc));
        String candidate = baseName;
        int suffix = 0;
        while (this.hasMethod(targetClass, methodsToAdd, candidate, methodDesc)) {
            suffix++;
            candidate = baseName + "$" + suffix;
        }
        return candidate;
    }

    private boolean hasMethod(ClassNode classNode, List<MethodNode> pendingMethods, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        for (MethodNode method : pendingMethods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeMethodName(String methodName) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '_' || c == '$' ? c : '$');
        }
        return builder.length() == 0 || builder.charAt(0) == '<' ? "handler" : builder.toString();
    }

    private boolean applyReturnFields(ClassNode classNode, ClassLoader loader, List<ReturnFieldPoint> points) {
        if (points.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode fieldInsn)) {
                    continue;
                }
                ReturnFieldPoint point = this.matchReturnField(fieldInsn, loader, points);
                if (point == null) {
                    continue;
                }

                int opcode = fieldInsn.getOpcode();
                if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                    if (!point.isStatic) {
                        method.instructions.insertBefore(fieldInsn, new InsnNode(Opcodes.DUP));
                    }
                    method.instructions.insert(fieldInsn, this.invokeReturnFieldHandler(point));
                } else if (opcode == Opcodes.PUTFIELD) {
                    Type fieldType = Type.getType(fieldInsn.desc);
                    int tempLocal = Math.max(method.maxLocals, 0);
                    method.maxLocals = tempLocal + fieldType.getSize();

                    InsnList replacement = new InsnList();
                    replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ISTORE), tempLocal));
                    replacement.add(new InsnNode(Opcodes.DUP));
                    replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ILOAD), tempLocal));
                    replacement.add(this.newReturnFieldHandlerCall(point));
                    if (point.returnCastType != null) {
                        replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, point.returnCastType));
                    }
                    method.instructions.insertBefore(fieldInsn, replacement);
                } else if (opcode == Opcodes.PUTSTATIC) {
                    method.instructions.insertBefore(fieldInsn, this.invokeReturnFieldHandler(point));
                }
                changed = true;
            }
        }
        return changed;
    }

    private InsnList invokeReturnFieldHandler(ReturnFieldPoint point) {
        InsnList replacement = new InsnList();
        replacement.add(this.newReturnFieldHandlerCall(point));
        if (point.returnCastType != null) {
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, point.returnCastType));
        }
        return replacement;
    }

    private MethodInsnNode newReturnFieldHandlerCall(ReturnFieldPoint point) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC,
                point.handlerOwnerInternalName,
                point.handlerMethodName,
                point.handlerMethodDesc,
                false);
    }

    private ReturnFieldPoint matchReturnField(FieldInsnNode fieldInsn, ClassLoader loader, List<ReturnFieldPoint> points) {
        int opcode = fieldInsn.getOpcode();
        if (opcode != Opcodes.GETFIELD && opcode != Opcodes.GETSTATIC
                && opcode != Opcodes.PUTFIELD && opcode != Opcodes.PUTSTATIC) {
            return null;
        }
        boolean isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        boolean isRead = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;

        for (ReturnFieldPoint point : points) {
            if (point.isStatic != isStatic || (isRead && !point.read) || (!isRead && !point.write)) {
                continue;
            }
            if (!point.fieldName.equals(fieldInsn.name) || !point.fieldDesc.equals(fieldInsn.desc)) {
                continue;
            }
            if (point.ownerInternalName.equals(fieldInsn.owner)
                    || (!isStatic && this.isSubclassASM(fieldInsn.owner, point.ownerInternalName, loader))) {
                return point;
            }
        }
        return null;
    }

    private AbstractInsnNode getLoadInsn(Type type, int index) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new VarInsnNode(Opcodes.ILOAD, index);
            case Type.LONG -> new VarInsnNode(Opcodes.LLOAD, index);
            case Type.FLOAT -> new VarInsnNode(Opcodes.FLOAD, index);
            case Type.DOUBLE -> new VarInsnNode(Opcodes.DLOAD, index);
            default -> new VarInsnNode(Opcodes.ALOAD, index);
        };
    }

    private AbstractInsnNode getDefaultInsn(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new InsnNode(Opcodes.ICONST_0);
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            default -> new InsnNode(Opcodes.ACONST_NULL);
        };
    }

    private InsnList unboxAndReturn(Type type) {
        InsnList list = new InsnList();
        switch (type.getSort()) {
            case Type.VOID -> {
                list.add(new InsnNode(Opcodes.POP));
                list.add(new InsnNode(Opcodes.RETURN));
            }
            case Type.BOOLEAN -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.CHAR -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.BYTE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.SHORT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.INT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
                list.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
                list.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
                list.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                list.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        return list;
    }

    private void insertBeforeReturns(MethodNode method, InsnList inject) {
        for (AbstractInsnNode node : method.instructions.toArray()) {
            int opcode = node.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                method.instructions.insertBefore(node, cloneInsnList(inject));
            }
        }
    }

    private static InsnList cloneInsnList(InsnList source) {
        InsnList copy = new InsnList();
        HashMap<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            copy.add(insn.clone(labels));
        }
        return copy;
    }

    private void fixMethodBounds(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            method.maxStack = Math.max(method.maxStack, 0);
            method.maxLocals = Math.max(method.maxLocals, 0);
            if (method.localVariables == null) {
                continue;
            }
            for (LocalVariableNode local : method.localVariables) {
                int requiredSize = local.index + 1;
                if ("J".equals(local.desc) || "D".equals(local.desc)) {
                    requiredSize = local.index + 2;
                }
                method.maxLocals = Math.max(method.maxLocals, requiredSize);
            }
        }
    }

    private byte[] loadMixinBytes(String mixinClassName, ClassLoader preferredLoader) throws IOException {
        String internalName = mixinClassName.replace('.', '/');
        byte[] cached = this.classBytesCache.get(internalName);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] bytes = this.loadClassBytesRelaxed(internalName, preferredLoader);
        if (bytes != null && bytes.length > 0) {
            this.classBytesCache.putIfAbsent(internalName, bytes);
            return bytes;
        }
        throw new IOException("Cannot find mixin class: " + mixinClassName);
    }

    private byte[] loadClassBytesRelaxed(String internalName, ClassLoader loader) {
        if (isPlatformOrLoaderClass(internalName)) {
            return null;
        }
        byte[] cached = this.classBytesCache.get(internalName);
        if (cached != null && cached.length > 0) {
            return cached;
        }

        String resourceName = internalName + ".class";
        for (ClassLoader candidate : this.classLoaderCandidates(loader)) {
            if (candidate == null) {
                continue;
            }
            try (InputStream inputStream = candidate.getResourceAsStream(resourceName)) {
                if (inputStream == null) {
                    continue;
                }
                byte[] bytes = inputStream.readAllBytes();
                if (bytes.length > 0) {
                    this.classBytesCache.putIfAbsent(internalName, bytes);
                }
                return bytes;
            } catch (Throwable throwable) {
                System.err.println("[SeraMixin] Failed to read class bytes for " + internalName + ": " + throwable.getMessage());
            }
        }

        if (this.transformerLoader != null) {
            try {
                byte[] bytes = this.transformerLoader.buildTransformedClassNodeFor(internalName.replace('/', '.'));
                if (bytes != null && bytes.length > 0) {
                    this.classBytesCache.putIfAbsent(internalName, bytes);
                    return bytes;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void preloadClassBytes(Class<?> clazz) {
        String internalName = Type.getInternalName(clazz);
        if (this.classBytesCache.containsKey(internalName)) {
            return;
        }
        byte[] bytes = this.loadClassBytesRelaxed(internalName, clazz.getClassLoader());
        if (bytes != null && bytes.length > 0) {
            this.classBytesCache.putIfAbsent(internalName, bytes);
        }
    }

    private ClassLoader getRuntimeClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : this.getClass().getClassLoader();
    }

    private List<ClassLoader> classLoaderCandidates(ClassLoader preferred) {
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>();
        candidates.add(preferred);
        candidates.add(Thread.currentThread().getContextClassLoader());
        candidates.add(this.getClass().getClassLoader());
        candidates.add(ClassLoader.getSystemClassLoader());
        candidates.remove(null);
        return new ArrayList<>(candidates);
    }

    private Class<?> resolveClass(String className, ClassLoader preferredLoader) throws ClassNotFoundException {
        String normalized = normalizeClassName(className);
        ClassNotFoundException failure = null;
        for (ClassLoader candidate : this.classLoaderCandidates(preferredLoader)) {
            try {
                return Class.forName(normalized, false, candidate);
            } catch (ClassNotFoundException exception) {
                failure = exception;
            }
        }
        throw failure == null ? new ClassNotFoundException(normalized) : failure;
    }

    private void readServiceProviderFile(SecureJar jar) {
        if (jar == null) {
            return;
        }
        try {
            Path providerPath = jar.getPath("META-INF", "services", ISeraMixin.class.getName());
            this.readServiceProviderFile(providerPath);
        } catch (Throwable ignored) {
        }
    }

    private void readServiceProviderFile(Path providerPath) {
        if (providerPath == null || !Files.exists(providerPath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(providerPath)) {
                String provider = stripServiceComment(line).trim();
                if (!provider.isEmpty()) {
                    this.queuedServiceProviders.add(provider);
                }
            }
        } catch (IOException exception) {
            System.err.println("[SeraMixin] Failed to read " + providerPath + ": " + exception.getMessage());
        }
    }

    private static String stripServiceComment(String line) {
        int commentIndex = line.indexOf('#');
        return commentIndex >= 0 ? line.substring(0, commentIndex) : line;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> visibleAnnotations,
                                                 List<AnnotationNode> invisibleAnnotations,
                                                 String desc) {
        for (AnnotationNode annotation : annotationNodes(visibleAnnotations, invisibleAnnotations)) {
            if (desc.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static List<AnnotationNode> annotationNodes(List<AnnotationNode> visibleAnnotations,
                                                       List<AnnotationNode> invisibleAnnotations) {
        boolean hasVisible = visibleAnnotations != null && !visibleAnnotations.isEmpty();
        boolean hasInvisible = invisibleAnnotations != null && !invisibleAnnotations.isEmpty();
        if (!hasVisible && !hasInvisible) {
            return List.of();
        }
        if (!hasVisible) {
            return invisibleAnnotations;
        }
        if (!hasInvisible) {
            return visibleAnnotations;
        }
        ArrayList<AnnotationNode> annotations = new ArrayList<>(visibleAnnotations);
        annotations.addAll(invisibleAnnotations);
        return annotations;
    }

    private static Object annotationNodeValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static String annotationClassInternalName(Object value) {
        if (value instanceof Type type && type.getSort() == Type.OBJECT) {
            return type.getInternalName();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return toInternalName(stringValue);
        }
        return null;
    }

    private static String normalizeClassName(String className) {
        String normalized = className.trim();
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replace('/', '.').replace('\\', '.');
    }

    private static String toInternalName(String className) {
        String normalized = className.trim();
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replace('.', '/').replace('\\', '/');
    }

    private record PendingMixin(Class<?> mixinClass, String mixinClassName, String targetClassName, ClassLoader classLoader) {
        static PendingMixin ofClass(Class<?> mixinClass) {
            return new PendingMixin(mixinClass, null, null, mixinClass.getClassLoader());
        }

        static PendingMixin ofMixinName(String mixinClassName, ClassLoader classLoader) {
            return new PendingMixin(null, mixinClassName, null, classLoader);
        }

        static PendingMixin ofNames(String mixinClassName, String targetClassName, ClassLoader classLoader) {
            return new PendingMixin(null, mixinClassName, targetClassName, classLoader);
        }

        void apply(SeraMixinLaunchPluginService service) {
            if (this.mixinClass != null) {
                service.register(this.mixinClass);
            } else if (this.targetClassName == null) {
                service.register(this.mixinClassName, this.classLoader);
            } else {
                service.register(this.mixinClassName, this.targetClassName, this.classLoader);
            }
        }
    }

    private record ClassInfo(String mixinClassName, String targetInternalName, ClassLoader mixinClassLoader,
                             ISeraMixin hook, int priority) {
        String key() {
            return this.targetInternalName + '\n' + this.mixinClassName;
        }
    }

    private record ClassHierarchyInfo(String internalName, String superName, boolean isInterface) {
    }

    private record OverwritePoint(String targetMethodName, String targetDesc,
                                  String mixinClassName, String mixinMethodName, String mixinMethodDesc) {
        boolean matches(String name, String desc) {
            return this.targetMethodName.equals(name) && this.targetDesc.equals(desc);
        }
    }

    private record InjectPoint(String targetMethodName, String targetDesc, InsertPosition position,
                               String mixinClassName, String mixinMethodName, String mixinMethodDesc,
                               boolean mixinMethodStatic, InjectMode mode) {
        boolean matches(String name, String desc) {
            return this.targetMethodName.equals(name) && this.targetDesc.equals(desc);
        }
    }

    private record InjectHandlerCall(int opcode, String owner, String name, String desc, boolean isInterface,
                                     boolean needsReceiver) {
    }

    private enum InjectMode {
        MIXIN
    }

    private record ShadowFieldInfo(String mixinFieldName, String targetFieldName, String desc) {
    }

    private record ShadowMethodInfo(String mixinMethodName, String targetMethodName, String desc) {
    }

    private record RedirectPoint(String targetMethodName, String targetMethodDesc,
                                 String mixinClassName, String mixinMethodName, String mixinMethodDesc,
                                 List<TargetCall> targetCalls) {
        boolean matches(String name, String desc) {
            return this.targetMethodName.equals(name) && this.targetMethodDesc.equals(desc);
        }
    }

    private record TargetCall(String owner, String name, String desc) {
        boolean matches(String owner, String name, String desc) {
            return this.owner.equals(owner) && this.name.equals(name) && this.desc.equals(desc);
        }
    }

    private static final class ReturnFieldPoint {
        static final String INCOMPATIBLE_CAST = "<INCOMPATIBLE_RETURN_FIELD>";

        final String ownerInternalName;
        final String fieldName;
        final String fieldDesc;
        final boolean isStatic;
        final String handlerOwnerInternalName;
        final String handlerMethodName;
        final String handlerMethodDesc;
        final String returnCastType;
        final boolean read;
        final boolean write;

        ReturnFieldPoint(String ownerInternalName, String fieldName, String fieldDesc, boolean isStatic,
                         String handlerOwnerInternalName, String handlerMethodName, String handlerMethodDesc,
                         String returnCastType, boolean read, boolean write) {
            this.ownerInternalName = ownerInternalName;
            this.fieldName = fieldName;
            this.fieldDesc = fieldDesc;
            this.isStatic = isStatic;
            this.handlerOwnerInternalName = handlerOwnerInternalName;
            this.handlerMethodName = handlerMethodName;
            this.handlerMethodDesc = handlerMethodDesc;
            this.returnCastType = returnCastType;
            this.read = read;
            this.write = write;
        }
    }

    private static final class TransformerHolder {
        final SeraMixinLaunchPluginService owner;
        final String mixinClassName;
        final String targetInternalName;
        final List<InjectPoint> injectPoints = new ArrayList<>();
        final List<OverwritePoint> overwritePoints = new ArrayList<>();
        final List<RedirectPoint> redirectPoints = new ArrayList<>();
        final List<ReturnFieldPoint> returnFieldPoints = new ArrayList<>();
        final Map<String, ShadowFieldInfo> shadowFields = new HashMap<>();
        final Map<String, ShadowMethodInfo> shadowMethods = new HashMap<>();
        final Map<String, MethodNode> rewrittenMethodCache = new HashMap<>();
        final ClassLoader loader;
        final ClassLoader mixinClassLoader;
        final ThreadLocal<Boolean> hasPrintedShadowHeader;
        final ThreadLocal<String> currentTargetClass;
        final ClassNode mixinClassNode;

        TransformerHolder(SeraMixinLaunchPluginService owner, String mixinClassName, String targetInternalName,
                          ClassLoader loader, ThreadLocal<Boolean> hasPrintedShadowHeader,
                          ThreadLocal<String> currentTargetClass, ClassLoader mixinClassLoader) throws IOException {
            this.owner = owner;
            this.mixinClassName = mixinClassName;
            this.targetInternalName = targetInternalName;
            this.loader = loader != null ? loader : owner.getRuntimeClassLoader();
            this.mixinClassLoader = mixinClassLoader != null ? mixinClassLoader : this.loader;
            this.hasPrintedShadowHeader = hasPrintedShadowHeader;
            this.currentTargetClass = currentTargetClass;
            this.mixinClassNode = this.scanWithASM();
        }

        private ClassNode scanWithASM() throws IOException {
            byte[] mixinBytes = this.owner.loadMixinBytes(this.mixinClassName, this.mixinClassLoader);
            ClassNode classNode = new ClassNode();
            new ClassReader(mixinBytes).accept(classNode, ClassReader.EXPAND_FRAMES);
            String mixinInternal = this.mixinClassName.replace('.', '/');

            this.scanShadowFields(classNode);
            for (MethodNode method : classNode.methods) {
                this.scanMethod(method, mixinInternal);
            }
            return classNode;
        }

        private void scanShadowFields(ClassNode classNode) {
            for (FieldNode field : classNode.fields) {
                for (AnnotationNode annotation : annotationNodes(field.visibleAnnotations, field.invisibleAnnotations)) {
                    if (!SHADOW_CLASS.equals(annotation.desc)) {
                        continue;
                    }
                    String targetFieldName = annotationStringValue(annotation, "value", field.name);
                    this.shadowFields.put(field.name, new ShadowFieldInfo(field.name, targetFieldName, field.desc));
                    this.ensureShadowHeader();
                    break;
                }
            }
        }

        private void scanMethod(MethodNode method, String mixinInternal) {
            ArrayList<InjectInfo> injects = new ArrayList<>();
            ArrayList<OverwriteInfo> overwrites = new ArrayList<>();

            for (AnnotationNode annotation : annotationNodes(method.visibleAnnotations, method.invisibleAnnotations)) {
                String desc = annotation.desc;
                if (SHADOW_CLASS.equals(desc)) {
                    ShadowMethodInfo shadowMethodInfo = this.getShadowMethodInfo(method, annotation);
                    this.shadowMethods.put(method.name + method.desc, shadowMethodInfo);
                    this.ensureShadowHeader();
                } else if (RETURN_FIELD_CLASS.equals(desc)) {
                    this.addReturnFieldPoints(method, annotation, mixinInternal);
                } else if (INJECT_CLASS.equals(desc)) {
                    injects.addAll(this.readInjectPoints(annotation, InsertPosition.HEAD));
                } else if (ASM_CLASS.equals(desc)) {
                    this.reportUnsupportedASMHandler(method);
                } else if (REDIRECT_CLASS.equals(desc)) {
                    this.redirectPoints.addAll(this.readRedirectPoints(method, annotation));
                } else if (OVERWRITE_CLASS.equals(desc)) {
                    overwrites.addAll(this.readOverwritePoints(annotation));
                }
            }

            for (InjectInfo injectInfo : injects) {
                this.injectPoints.add(new InjectPoint(injectInfo.methodName, injectInfo.desc, injectInfo.at,
                        this.mixinClassName, method.name, method.desc, (method.access & Opcodes.ACC_STATIC) != 0,
                        InjectMode.MIXIN));
            }
            for (OverwriteInfo overwriteInfo : overwrites) {
                this.overwritePoints.add(new OverwritePoint(overwriteInfo.methodName, overwriteInfo.desc,
                        this.mixinClassName, method.name, method.desc));
            }

            MethodNode clonedMethod = this.cloneMethod(method);
            this.rewriteShadowReferences(clonedMethod, mixinInternal);
            this.rewrittenMethodCache.put(method.name + method.desc, clonedMethod);
        }

        private List<InjectInfo> readInjectPoints(AnnotationNode annotation, InsertPosition defaultPosition) {
            List<String> methodNames = annotationStringListValue(annotation, "methodName");
            String methodDesc = annotationStringValue(annotation, "desc", "");
            InsertPosition position = annotationEnumValue(annotation, "at", defaultPosition);
            ArrayList<InjectInfo> points = new ArrayList<>();
            for (String methodName : methodNames) {
                points.add(new InjectInfo(methodName, methodDesc, position));
            }
            return points;
        }

        private List<OverwriteInfo> readOverwritePoints(AnnotationNode annotation) {
            List<String> methodNames = annotationStringListValue(annotation, "methodName");
            String methodDesc = annotationStringValue(annotation, "desc", "");
            ArrayList<OverwriteInfo> points = new ArrayList<>();
            for (String methodName : methodNames) {
                points.add(new OverwriteInfo(methodName, methodDesc));
            }
            return points;
        }

        private void reportUnsupportedASMHandler(MethodNode method) {
            System.err.println("[SeraMixin] @ASM handler is ignored because invoking it would load the mixin class: "
                    + this.mixinClassName + "." + method.name + method.desc);
        }

        private List<RedirectPoint> readRedirectPoints(MethodNode method, AnnotationNode annotation) {
            List<String> methodNames = annotationStringListValue(annotation, "methodName");
            String methodDesc = annotationStringValue(annotation, "methodDesc", "");
            List<String> targetMethods = annotationStringListValue(annotation, "targetMethod");
            String targetMethodDesc = annotationStringValue(annotation, "targetMethodDesc", "");
            List<TargetCall> targetCalls = this.getTargetCalls(targetMethods, targetMethodDesc);
            ArrayList<RedirectPoint> points = new ArrayList<>();
            for (String methodName : methodNames) {
                points.add(new RedirectPoint(methodName, methodDesc, this.mixinClassName, method.name, method.desc, targetCalls));
            }
            return points;
        }

        private List<TargetCall> getTargetCalls(List<String> targetMethods, String targetMethodDesc) {
            ArrayList<TargetCall> targetCalls = new ArrayList<>();
            for (String targetMethod : targetMethods) {
                int lastSlash = targetMethod.lastIndexOf('/');
                if (lastSlash < 0) {
                    lastSlash = targetMethod.lastIndexOf('.');
                }
                if (lastSlash <= 0) {
                    continue;
                }
                String owner = targetMethod.substring(0, lastSlash).replace('.', '/');
                String name = targetMethod.substring(lastSlash + 1);
                targetCalls.add(new TargetCall(owner, name, targetMethodDesc));
            }
            return targetCalls;
        }

        private ShadowMethodInfo getShadowMethodInfo(MethodNode method, AnnotationNode annotation) {
            String targetMethodName = annotationStringValue(annotation, "value", method.name);
            return new ShadowMethodInfo(method.name, targetMethodName, method.desc);
        }

        private void addReturnFieldPoints(MethodNode method, AnnotationNode annotation, String mixinInternal) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || (method.access & Opcodes.ACC_PUBLIC) == 0) {
                System.err.println("[SeraMixin] @ReturnField handler must be public static: "
                        + this.mixinClassName + "." + method.name + method.desc);
                return;
            }

            List<String> fields = annotationStringListValue(annotation, "field");
            String fieldDesc = annotationTypeDescriptorValue(annotation, "type");
            boolean isStatic = annotationBooleanValue(annotation, "isStatic", false);
            boolean read = annotationBooleanValue(annotation, "read", true);
            boolean write = annotationBooleanValue(annotation, "write", true);

            if (fields.isEmpty()) {
                System.err.println("[SeraMixin] @ReturnField has no field names: " + this.mixinClassName + "." + method.name + method.desc);
                return;
            }
            if (!read && !write) {
                System.err.println("[SeraMixin] @ReturnField must handle read or write: " + this.mixinClassName + "." + method.name + method.desc);
                return;
            }

            Type[] args = Type.getMethodType(method.desc).getArgumentTypes();
            int expectedArgCount = isStatic ? 1 : 2;
            if (args.length != expectedArgCount) {
                System.err.println("[SeraMixin] @ReturnField handler must accept exactly "
                        + expectedArgCount + " argument(s): " + this.mixinClassName + "." + method.name + method.desc);
                return;
            }
            if (!isStatic && !this.isReturnFieldSelfArgCompatible(args[0])) {
                System.err.println("[SeraMixin] @ReturnField handler first argument must be "
                        + this.targetInternalName.replace('/', '.') + " or its supertype: "
                        + this.mixinClassName + "." + method.name + method.desc);
                return;
            }
            if (fieldDesc == null || fieldDesc.isEmpty()) {
                fieldDesc = args[isStatic ? 0 : 1].getDescriptor();
            }

            String returnCastType = this.resolveReturnCastType(fieldDesc, method.desc, isStatic);
            if (ReturnFieldPoint.INCOMPATIBLE_CAST.equals(returnCastType)) {
                System.err.println("[SeraMixin] @ReturnField handler desc mismatch for "
                        + this.targetInternalName + " fields " + fields + ": fieldDesc=" + fieldDesc
                        + " handler=" + method.desc);
                return;
            }

            for (String fieldName : new LinkedHashSet<>(fields)) {
                this.returnFieldPoints.add(new ReturnFieldPoint(
                        this.targetInternalName,
                        fieldName,
                        fieldDesc,
                        isStatic,
                        mixinInternal,
                        method.name,
                        method.desc,
                        returnCastType,
                        read,
                        write));
            }
        }

        private boolean isReturnFieldSelfArgCompatible(Type argType) {
            if (argType.getSort() != Type.OBJECT) {
                return false;
            }
            String argInternal = argType.getInternalName();
            return "java/lang/Object".equals(argInternal)
                    || this.targetInternalName.equals(argInternal)
                    || this.owner.isSubclassASM(this.targetInternalName, argInternal, this.loader);
        }

        private String resolveReturnCastType(String fieldDesc, String handlerDesc, boolean isStatic) {
            Type fieldType = Type.getType(fieldDesc);
            Type handlerType = Type.getMethodType(handlerDesc);
            Type[] args = handlerType.getArgumentTypes();
            Type returnType = handlerType.getReturnType();
            int fieldArgIndex = isStatic ? 0 : 1;
            if (args.length <= fieldArgIndex || returnType.getSort() == Type.VOID) {
                return ReturnFieldPoint.INCOMPATIBLE_CAST;
            }

            String argDesc = args[fieldArgIndex].getDescriptor();
            String returnDesc = returnType.getDescriptor();
            boolean referenceField = fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY;
            boolean argOk = argDesc.equals(fieldDesc) || (referenceField && "Ljava/lang/Object;".equals(argDesc));
            boolean returnOk = returnDesc.equals(fieldDesc) || (referenceField && "Ljava/lang/Object;".equals(returnDesc));
            if (!argOk || !returnOk) {
                return ReturnFieldPoint.INCOMPATIBLE_CAST;
            }
            if (referenceField && !returnDesc.equals(fieldDesc)) {
                return fieldType.getSort() == Type.ARRAY ? fieldDesc : fieldType.getInternalName();
            }
            return null;
        }

        private void rewriteShadowReferences(MethodNode method, String mixinInternal) {
            if (this.shadowFields.isEmpty() && this.shadowMethods.isEmpty()) {
                return;
            }
            InsnList instructions = method.instructions;
            if (instructions == null) {
                return;
            }
            for (AbstractInsnNode node : instructions.toArray()) {
                if (node instanceof FieldInsnNode fieldNode) {
                    ShadowFieldInfo shadowField = this.shadowFields.get(fieldNode.name);
                    if (!fieldNode.owner.equals(mixinInternal) || shadowField == null) {
                        continue;
                    }
                    fieldNode.owner = this.targetInternalName;
                    fieldNode.name = shadowField.targetFieldName;
                    int opcode = fieldNode.getOpcode();
                    if (opcode == Opcodes.GETSTATIC) {
                        fieldNode.setOpcode(Opcodes.GETFIELD);
                        instructions.insertBefore(fieldNode, new VarInsnNode(Opcodes.ALOAD, 0));
                    } else if (opcode == Opcodes.PUTSTATIC) {
                        fieldNode.setOpcode(Opcodes.PUTFIELD);
                        Type type = Type.getType(shadowField.desc);
                        instructions.insertBefore(fieldNode, new VarInsnNode(Opcodes.ALOAD, 0));
                        instructions.insertBefore(fieldNode, type.getSize() == 1 ? new InsnNode(Opcodes.SWAP) : new InsnNode(Opcodes.DUP_X2));
                        if (type.getSize() == 2) {
                            instructions.insertBefore(fieldNode, new InsnNode(Opcodes.POP));
                        }
                    }
                    continue;
                }
                if (!(node instanceof MethodInsnNode methodNode) || !methodNode.owner.equals(mixinInternal)) {
                    continue;
                }

                ShadowMethodInfo shadowMethod = this.shadowMethods.get(methodNode.name + methodNode.desc);
                if (shadowMethod == null) {
                    for (ShadowMethodInfo info : this.shadowMethods.values()) {
                        if (info.mixinMethodName.equals(methodNode.name)) {
                            shadowMethod = info;
                            break;
                        }
                    }
                }
                if (shadowMethod == null) {
                    continue;
                }

                methodNode.owner = this.targetInternalName;
                methodNode.name = shadowMethod.targetMethodName;
                if (methodNode.getOpcode() == Opcodes.INVOKESTATIC) {
                    methodNode.setOpcode(Opcodes.INVOKEVIRTUAL);
                    instructions.insertBefore(methodNode, new VarInsnNode(Opcodes.ALOAD, 0));
                }
            }
        }

        private MethodNode cloneMethod(MethodNode original) {
            MethodNode copy = new MethodNode(original.access, original.name, original.desc, original.signature,
                    original.exceptions != null ? original.exceptions.toArray(new String[0]) : null);
            HashMap<LabelNode, LabelNode> labelMap = new HashMap<>();
            for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode label) {
                    labelMap.put(label, new LabelNode());
                }
            }
            for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                copy.instructions.add(insn.clone(labelMap));
            }
            if (original.tryCatchBlocks != null) {
                for (TryCatchBlockNode block : original.tryCatchBlocks) {
                    copy.tryCatchBlocks.add(new TryCatchBlockNode(
                            labelMap.get(block.start),
                            labelMap.get(block.end),
                            labelMap.get(block.handler),
                            block.type));
                }
            }
            if (original.localVariables != null) {
                for (LocalVariableNode local : original.localVariables) {
                    copy.localVariables.add(new LocalVariableNode(local.name, local.desc, local.signature,
                            labelMap.get(local.start), labelMap.get(local.end), local.index));
                }
            }
            copy.maxStack = Math.max(original.maxStack, 0);
            copy.maxLocals = Math.max(original.maxLocals, 0);
            return copy;
        }

        private void ensureShadowHeader() {
            if (!Boolean.TRUE.equals(this.hasPrintedShadowHeader.get())) {
                this.hasPrintedShadowHeader.set(true);
            }
        }

        private static String annotationStringValue(AnnotationNode annotation, String key, String defaultValue) {
            Object value = annotationValue(annotation, key);
            return value instanceof String stringValue && !stringValue.isEmpty() ? stringValue : defaultValue;
        }

        private static boolean annotationBooleanValue(AnnotationNode annotation, String key, boolean defaultValue) {
            Object value = annotationValue(annotation, key);
            return value instanceof Boolean booleanValue ? booleanValue : defaultValue;
        }

        private static String annotationTypeDescriptorValue(AnnotationNode annotation, String key) {
            Object value = annotationValue(annotation, key);
            if (value instanceof Type type) {
                return type.getDescriptor();
            }
            return value instanceof String stringValue ? stringValue : null;
        }

        @SuppressWarnings("unchecked")
        private static List<String> annotationStringListValue(AnnotationNode annotation, String key) {
            Object value = annotationValue(annotation, key);
            if (!(value instanceof List<?> rawList)) {
                return List.of();
            }
            ArrayList<String> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof String stringValue && !stringValue.isEmpty()) {
                    result.add(stringValue);
                }
            }
            return result;
        }

        private static InsertPosition annotationEnumValue(AnnotationNode annotation, String key, InsertPosition defaultValue) {
            Object value = annotationValue(annotation, key);
            if (value instanceof String[] enumValue && enumValue.length > 1) {
                try {
                    return InsertPosition.valueOf(enumValue[1]);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return defaultValue;
        }

        private static Object annotationValue(AnnotationNode annotation, String key) {
            if (annotation.values == null) {
                return null;
            }
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (key.equals(annotation.values.get(i))) {
                    return annotation.values.get(i + 1);
                }
            }
            return null;
        }

        private record InjectInfo(String methodName, String desc, InsertPosition at) {
        }

        private record OverwriteInfo(String methodName, String desc) {
        }
    }
}
