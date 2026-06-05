package seraphina.seraphina_lib.mixin.service;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import seraphina.seraphina_lib.service.ISeraMixin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Seraphina
 * @version 2.6
 * */
public class SeraMixinLaunchPluginService implements ILaunchPluginService {
    private static final List<PendingMixin> PENDING_MIXINS = new CopyOnWriteArrayList<>();
    private static volatile SeraMixinLaunchPluginService currentService;

    private final Map<String, List<ClassInfo>> registeredMixins = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentTargetClass = new ThreadLocal<>();
    private final ThreadLocal<Boolean> isSubclassMode = new ThreadLocal<>();
    private final ThreadLocal<Boolean> hasPrintedShadowHeader = new ThreadLocal<>();
    private final MixinClassProvider classProvider;
    private final MixinHierarchyResolver hierarchyResolver;
    private final MixinTransformerEngine transformerEngine;
    private final MixinServiceDiscovery serviceDiscovery;
    private final MixinMappingManager mappingManager;

    public SeraMixinLaunchPluginService() {
        this.classProvider = new MixinClassProvider(this.getClass());
        this.hierarchyResolver = new MixinHierarchyResolver(this.registeredMixins, this.classProvider);
        this.mappingManager = new MixinMappingManager();
        this.transformerEngine = new MixinTransformerEngine(
                this.registeredMixins,
                this.classProvider,
                this.hierarchyResolver,
                this.isSubclassMode,
                this.hasPrintedShadowHeader,
                this.currentTargetClass);
        this.serviceDiscovery = new MixinServiceDiscovery(this);
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
        if (MixinConstants.isPlatformOrLoaderClass(internalName)) {
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
        if (MixinConstants.isPlatformOrLoaderClass(internalName)) {
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
            this.hierarchyResolver.cacheLoadedClass(internalName, classNode);
            boolean changed = this.transformerEngine.applyClassNodeTransform(classNode, internalName, this.getRuntimeClassLoader());
            if (!changed) {
                return ComputeFlags.NO_REWRITE;
            }
            MixinTransformerEngine.fixMethodBounds(classNode);
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
        this.classProvider.setTransformerLoader(transformerLoader);
    }

    @Override
    public void addResources(List<SecureJar> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        boolean addedResource = false;
        for (SecureJar resource : resources) {
            if (resource == null) {
                continue;
            }
            this.classProvider.addResourceJar(resource);
            this.serviceDiscovery.readServiceProviderFile(resource);
            addedResource = true;
        }
        if (addedResource) {
            this.serviceDiscovery.markDirty();
        }
    }

    @Override
    public void offerResource(Path resource, String name) {
        if (resource == null) {
            return;
        }
        String normalizedName = name == null ? resource.toString().replace('\\', '/') : name.replace('\\', '/');
        if (normalizedName.endsWith(MixinConstants.SERVICE_FILE)) {
            this.serviceDiscovery.readServiceProviderFile(resource);
            this.serviceDiscovery.markDirty();
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
        return this.transformerEngine.getReturnFieldTargets(this.getRuntimeClassLoader());
    }

    void drainPendingMixins() {
        for (PendingMixin pending : PENDING_MIXINS) {
            if (PENDING_MIXINS.remove(pending)) {
                pending.apply(this);
            }
        }
    }

    ClassLoader getRuntimeClassLoader() {
        return this.classProvider.getRuntimeClassLoader();
    }

    Class<?> resolveClass(String className, ClassLoader preferredLoader) throws ClassNotFoundException {
        return this.classProvider.resolveClass(className, preferredLoader);
    }

    void prepareProviderMapping(ISeraMixin provider) {
        this.mappingManager.prepare(provider);
    }

    private void ensureMixinServicesLoaded() {
        this.serviceDiscovery.ensureLoaded();
    }

    private void register(Class<?> mixinClass, ISeraMixin hook) {
        if (mixinClass == null) {
            return;
        }
        this.registerMixinFromASM(mixinClass.getName(), mixinClass.getClassLoader(), hook);
    }

    void registerMixinFromASM(String mixinClassName, ClassLoader mixinClassLoader, ISeraMixin hook) {
        if (this.registerMixinIfAnnotatedFromASM(mixinClassName, mixinClassLoader, hook)) {
            return;
        }
        String normalizedMixin = MixinNameUtils.normalizeClassName(mixinClassName);
        System.err.println("[SeraMixin] Missing @SeraMixin target on " + normalizedMixin);
    }

    private boolean registerMixinIfAnnotatedFromASM(String mixinClassName, ClassLoader mixinClassLoader, ISeraMixin hook) {
        String normalizedMixin = MixinNameUtils.normalizeClassName(mixinClassName);
        String targetInternalName = this.readSeraMixinTargetInternalName(normalizedMixin, mixinClassLoader);
        if (targetInternalName == null || targetInternalName.isBlank()) {
            return false;
        }
        int priority = hook == null ? 0 : safePriority(hook);
        this.register(normalizedMixin, targetInternalName, mixinClassLoader, hook, priority);
        return true;
    }

    int registerMixinPackage(String mixinPackageName, ClassLoader mixinClassLoader, ISeraMixin hook) {
        String normalizedPackage = MixinNameUtils.normalizePackageName(mixinPackageName);
        List<String> mixinClassNames = this.classProvider.findClassNamesInPackage(normalizedPackage, mixinClassLoader);
        if (mixinClassNames.isEmpty()) {
            return -1;
        }

        int registered = 0;
        for (String mixinClassName : mixinClassNames) {
            if (this.registerMixinIfAnnotatedFromASM(mixinClassName, mixinClassLoader, hook)) {
                registered++;
            }
        }
        if (registered == 0) {
            System.err.println("[SeraMixin] No @SeraMixin classes found in package " + normalizedPackage);
        }
        return registered;
    }

    boolean hasSeraMixinAnnotation(String mixinClassName, ClassLoader mixinClassLoader) {
        return this.readSeraMixinTargetInternalName(mixinClassName, mixinClassLoader) != null;
    }

    private String readSeraMixinTargetInternalName(String mixinClassName, ClassLoader mixinClassLoader) {
        String normalizedMixin = MixinNameUtils.normalizeClassName(mixinClassName);
        byte[] mixinBytes;
        try {
            mixinBytes = this.classProvider.loadMixinBytes(normalizedMixin, mixinClassLoader);
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

        AnnotationNode annotation = MixinAnnotationUtils.findAnnotation(
                classNode.visibleAnnotations,
                classNode.invisibleAnnotations,
                MixinConstants.SERA_MIXIN_CLASS);
        if (annotation == null) {
            return null;
        }
        return MixinAnnotationUtils.annotationClassInternalName(MixinAnnotationUtils.annotationValue(annotation, "value"));
    }

    private void register(String mixinClassName, String targetClassName, ClassLoader mixinClassLoader, ISeraMixin hook, int priority) {
        String normalizedMixin = MixinNameUtils.normalizeClassName(mixinClassName);
        MixinMappingResolver mappingResolver = this.mappingManager.mappingFor(hook);
        String targetInternal = mappingResolver.mapClassName(MixinNameUtils.toInternalName(targetClassName));
        ClassLoader loader = mixinClassLoader != null ? mixinClassLoader : this.getRuntimeClassLoader();
        ClassInfo info = new ClassInfo(normalizedMixin, targetInternal, loader, hook, priority, mappingResolver);
        this.registeredMixins.compute(targetInternal, (target, oldValue) -> {
            ArrayList<ClassInfo> next = oldValue == null ? new ArrayList<>() : new ArrayList<>(oldValue);
            next.removeIf(existing -> existing.mixinClassName.equals(info.mixinClassName));
            next.add(info);
            next.sort(Comparator
                    .comparingInt(ClassInfo::priority).reversed()
                    .thenComparing(ClassInfo::mixinClassName));
            return List.copyOf(next);
        });
        this.transformerEngine.invalidateReturnFieldHolders();
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
}
