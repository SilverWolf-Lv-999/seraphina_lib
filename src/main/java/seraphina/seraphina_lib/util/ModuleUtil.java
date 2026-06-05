package seraphina.seraphina_lib.util;

import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;
import seraphina.seraphina_lib.util.clazz.ClassUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public class ModuleUtil {
    public static final Logger LOGGER = LoggerFactory.getLogger(ModuleUtil.class);

    public static final ModuleUtil INSTANCE = new ModuleUtil();

    private static final String[] CURRENT_MODULE_READER_MODULE_PREFIXES = {
            "org.lwjgl"
    };

    private static final String[] CURRENT_MODULE_READER_MARKER_CLASSES = {
            "org.lwjgl.BufferUtils",
            "org.lwjgl.system.MemoryUtil",
            "org.lwjgl.glfw.GLFW",
            "org.lwjgl.opengl.GL",
            "org.lwjgl.stb.STBImage"
    };

    private static final String[][] REQUIRED_MODULE_PACKAGES = {
            {"java.base", "java.lang.invoke"},
            {"java.base", "jdk.internal.misc"},
            {"java.base", "jdk.internal.reflect"},
            {"java.base", "jdk.internal.loader"},
            {"java.base", "jdk.internal.module"},
            {"jdk.attach", "sun.tools.attach"},
            {"jdk.attach", "com.sun.tools.attach"},
            {"jdk.attach", "com.sun.tools.attach.spi"},
            {"java.instrument", "sun.instrument"},
            {"java.base", "java.lang.reflect"},
            {"java.base", "java.lang"}
    };

    public void openAllRequiredModules(Class<?> clazz) {
        Module currentModule = clazz.getModule();
        ModuleLayer bootLayer = ModuleLayer.boot();
        for (String[] modulePackage : REQUIRED_MODULE_PACKAGES) {
            String moduleName = modulePackage[0];
            String packageName = modulePackage[1];
            Optional<Module> optModule = bootLayer.findModule(moduleName);
            if (optModule.isEmpty()) continue;
            Module module = optModule.get();
            try {
                exportAndOpen(module, packageName, currentModule);
                LOGGER.info(String.format("Exporting/opening module '%s' in package '%s'", moduleName, packageName));
            } catch (Throwable e) {
                LOGGER.warn("Failed to export/open {} / {} using reflection", moduleName, packageName);
            }
        }
    }

    public void openCurrentModuleToAllRequiredModules(Class<?> clazz) {
        Module currentModule = clazz.getModule();
        ModuleLayer bootLayer = ModuleLayer.boot();
        Set<Module> targetModules = collectCurrentModuleReaderModules(currentModule, bootLayer);
        Set<String> currentPackages = currentModule.getPackages();
        for (Module targetModule : targetModules) {
            String moduleName = moduleName(targetModule);
            try {
                addRead(targetModule, currentModule);
                for (String packageName : currentPackages) {
                    exportAndOpen(currentModule, packageName, targetModule);
                }
                LOGGER.info(String.format("Exporting/opening current module packages to module '%s'", moduleName));
            } catch (Throwable e) {
                LOGGER.warn("Failed to export/open current module packages to {} using reflection", moduleName);
            }
        }
    }

    private Set<Module> collectCurrentModuleReaderModules(Module currentModule, ModuleLayer bootLayer) {
        Set<Module> targetModules = new LinkedHashSet<>();
        Set<String> openedModules = new LinkedHashSet<>();
        for (String[] modulePackage : REQUIRED_MODULE_PACKAGES) {
            String moduleName = modulePackage[0];
            if (!openedModules.add(moduleName)) continue;
            Optional<Module> optModule = bootLayer.findModule(moduleName);
            optModule.ifPresent(module -> addTargetModule(targetModules, currentModule, module));
        }

        Set<ModuleLayer> visitedLayers = new LinkedHashSet<>();
        addModulesByPrefixes(targetModules, currentModule, currentModule.getLayer(), visitedLayers);
        addModulesByPrefixes(targetModules, currentModule, bootLayer, visitedLayers);
        addModulesByMarkerClasses(targetModules, currentModule);
        return targetModules;
    }

    private void addModulesByPrefixes(Set<Module> targetModules, Module currentModule, ModuleLayer layer, Set<ModuleLayer> visitedLayers) {
        if (layer == null || !visitedLayers.add(layer)) return;
        layer.modules().stream()
                .filter(module -> matchesCurrentModuleReaderPrefix(module.getName()))
                .sorted(Comparator.comparing(Module::getName))
                .forEach(module -> addTargetModule(targetModules, currentModule, module));
        for (ModuleLayer parent : layer.parents()) {
            addModulesByPrefixes(targetModules, currentModule, parent, visitedLayers);
        }
    }

    private void addModulesByMarkerClasses(Set<Module> targetModules, Module currentModule) {
        for (String markerClassName : CURRENT_MODULE_READER_MARKER_CLASSES) {
            try {
                Module module = loadClassWithoutInitialization(markerClassName).getModule();
                if (matchesCurrentModuleReaderPrefix(module.getName())) {
                    addTargetModule(targetModules, currentModule, module);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private Class<?> loadClassWithoutInitialization(String className) throws ClassNotFoundException {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            try {
                return Class.forName(className, false, contextClassLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(className, false, ModuleUtil.class.getClassLoader());
    }

    private void addTargetModule(Set<Module> targetModules, Module currentModule, Module targetModule) {
        if (targetModule != currentModule) {
            targetModules.add(targetModule);
        }
    }

    private boolean matchesCurrentModuleReaderPrefix(String moduleName) {
        if (moduleName == null) return false;
        for (String prefix : CURRENT_MODULE_READER_MODULE_PREFIXES) {
            if (moduleName.equals(prefix) || moduleName.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private String moduleName(Module module) {
        String moduleName = module.getName();
        return moduleName == null ? "<unnamed>" : moduleName;
    }

    private void addRead(Module module, Module readModule) throws Throwable {
        MethodType moduleReadType = MethodType.methodType(Void.TYPE, Module.class);
        MethodHandle implAddReads = ClassUtils.LOOKUP.findVirtual(Module.class, "implAddReads", moduleReadType);
        implAddReads.invoke(module, readModule);
    }

    private void exportAndOpen(Module module, String packageName, Module targetModule) throws Throwable {
        MethodType moduleAccessType = MethodType.methodType(Void.TYPE, String.class, Module.class);
        MethodHandle implAddExports = ClassUtils.LOOKUP.findVirtual(Module.class, "implAddExports", moduleAccessType);
        MethodHandle implAddOpens = ClassUtils.LOOKUP.findVirtual(Module.class, "implAddOpens", moduleAccessType);
        implAddExports.invoke(module, packageName, targetModule);
        implAddOpens.invoke(module, packageName, targetModule);
    }
}
