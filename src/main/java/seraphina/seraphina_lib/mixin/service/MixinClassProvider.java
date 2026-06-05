package seraphina.seraphina_lib.mixin.service;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService.ITransformerLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class MixinClassProvider {
    private final Class<?> ownerClass;
    private final List<SecureJar> resourceJars = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, byte[]> classBytesCache = new ConcurrentHashMap<>();
    private volatile ITransformerLoader transformerLoader;

    MixinClassProvider(Class<?> ownerClass) {
        this.ownerClass = ownerClass;
    }

    void setTransformerLoader(ITransformerLoader transformerLoader) {
        this.transformerLoader = transformerLoader;
    }

    void addResourceJar(SecureJar resource) {
        if (resource != null) {
            this.resourceJars.add(resource);
        }
    }

    List<String> findClassNamesInPackage(String packageName, ClassLoader preferredLoader) {
        if (packageName == null || packageName.isBlank()) {
            return List.of();
        }

        String packagePath = MixinNameUtils.normalizePackageName(packageName).replace('.', '/');
        LinkedHashSet<String> classNames = new LinkedHashSet<>();
        this.findClassNamesFromClassLoaders(packagePath, preferredLoader, classNames);
        this.findClassNamesFromSecureJars(packagePath, classNames);

        ArrayList<String> result = new ArrayList<>(classNames);
        result.sort(String::compareTo);
        return result;
    }

    byte[] loadMixinBytes(String mixinClassName, ClassLoader preferredLoader) throws IOException {
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

    byte[] loadClassBytesRelaxed(String internalName, ClassLoader loader) {
        if (MixinConstants.isPlatformOrLoaderClass(internalName)) {
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

    void preloadClassBytes(Class<?> clazz) {
        String internalName = org.objectweb.asm.Type.getInternalName(clazz);
        if (this.classBytesCache.containsKey(internalName)) {
            return;
        }
        byte[] bytes = this.loadClassBytesRelaxed(internalName, clazz.getClassLoader());
        if (bytes != null && bytes.length > 0) {
            this.classBytesCache.putIfAbsent(internalName, bytes);
        }
    }

    ClassLoader getRuntimeClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : this.ownerClass.getClassLoader();
    }

    List<ClassLoader> classLoaderCandidates(ClassLoader preferred) {
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>();
        includeClassLoader(candidates, preferred);
        includeClassLoader(candidates, Thread.currentThread().getContextClassLoader());
        includeClassLoader(candidates, this.ownerClass.getClassLoader());
        includeClassLoader(candidates, ClassLoader.getSystemClassLoader());
        return new ArrayList<>(candidates);
    }

    private static void includeClassLoader(Set<ClassLoader> candidates, ClassLoader classLoader) {
        if (classLoader != null) {
            candidates.add(classLoader);
        }
    }

    Class<?> resolveClass(String className, ClassLoader preferredLoader) throws ClassNotFoundException {
        String normalized = MixinNameUtils.normalizeClassName(className);
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

    private void findClassNamesFromClassLoaders(String packagePath, ClassLoader preferredLoader, Set<String> classNames) {
        for (ClassLoader candidate : this.classLoaderCandidates(preferredLoader)) {
            try {
                Enumeration<URL> resources = candidate.getResources(packagePath);
                while (resources.hasMoreElements()) {
                    this.scanPackageResource(resources.nextElement(), packagePath, classNames);
                }
            } catch (IOException exception) {
                System.err.println("[SeraMixin] Failed to scan package " + packagePath + ": " + exception.getMessage());
            }
        }
    }

    private void findClassNamesFromSecureJars(String packagePath, Set<String> classNames) {
        String[] pathParts = packagePath.split("/");
        String firstPart = pathParts[0];
        String[] remainingParts = java.util.Arrays.copyOfRange(pathParts, 1, pathParts.length);
        for (SecureJar jar : this.resourceJars) {
            try {
                this.scanPackageDirectory(jar.getPath(firstPart, remainingParts), packagePath, classNames);
            } catch (Throwable ignored) {
            }
        }
    }

    private void scanPackageResource(URL resource, String packagePath, Set<String> classNames) {
        if (resource == null) {
            return;
        }
        if ("jar".equals(resource.getProtocol())) {
            try {
                this.scanJarPackageResource(resource, packagePath, classNames);
                return;
            } catch (IOException exception) {
                System.err.println("[SeraMixin] Failed to scan jar package " + packagePath + ": " + exception.getMessage());
            }
        }
        try {
            this.scanPackageDirectory(Path.of(resource.toURI()), packagePath, classNames);
        } catch (Exception ignored) {
        }
    }

    private void scanJarPackageResource(URL resource, String packagePath, Set<String> classNames) throws IOException {
        var connection = resource.openConnection();
        if (!(connection instanceof JarURLConnection jarConnection)) {
            return;
        }
        jarConnection.setUseCaches(false);
        try (JarFile jarFile = jarConnection.getJarFile()) {
            this.scanJarFile(jarFile, packagePath, classNames);
        }
    }

    private void scanJarFile(JarFile jarFile, String packagePath, Set<String> classNames) {
        String packagePrefix = packagePath.endsWith("/") ? packagePath : packagePath + "/";
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(packagePrefix)) {
                continue;
            }
            this.addClassNameFromJarEntry(jarFile, entry, classNames);
        }
    }

    private void scanPackageDirectory(Path packageRoot, String packagePath, Set<String> classNames) throws IOException {
        if (packageRoot == null || !Files.isDirectory(packageRoot)) {
            return;
        }
        try (var paths = Files.walk(packageRoot)) {
            paths.filter(Files::isRegularFile)
                    .forEach(classFile -> this.addClassNameFromPackageFile(packageRoot, packagePath, classFile, classNames));
        }
    }

    private void addClassNameFromPackageFile(Path packageRoot, String packagePath, Path classFile, Set<String> classNames) {
        String relativeName = packageRoot.relativize(classFile).toString().replace('\\', '/');
        String className = classNameFromResourceName(packagePath + "/" + relativeName);
        if (className == null) {
            return;
        }
        classNames.add(className);
        this.cacheClassBytes(className, classFile);
    }

    private void addClassNameFromJarEntry(JarFile jarFile, JarEntry entry, Set<String> classNames) {
        String className = classNameFromResourceName(entry.getName());
        if (className == null) {
            return;
        }
        classNames.add(className);
        if (this.classBytesCache.containsKey(className.replace('.', '/'))) {
            return;
        }
        try (InputStream inputStream = jarFile.getInputStream(entry)) {
            this.cacheClassBytes(className, inputStream.readAllBytes());
        } catch (IOException ignored) {
        }
    }

    private void cacheClassBytes(String className, Path classFile) {
        String internalName = className.replace('.', '/');
        if (this.classBytesCache.containsKey(internalName)) {
            return;
        }
        try {
            this.cacheClassBytes(className, Files.readAllBytes(classFile));
        } catch (IOException ignored) {
        }
    }

    private void cacheClassBytes(String className, byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            this.classBytesCache.putIfAbsent(className.replace('.', '/'), bytes);
        }
    }

    private static String classNameFromResourceName(String resourceName) {
        String normalized = resourceName.replace('\\', '/');
        if (!normalized.endsWith(".class")
                || normalized.endsWith("/package-info.class")
                || normalized.endsWith("/module-info.class")) {
            return null;
        }
        String className = normalized.substring(0, normalized.length() - ".class".length()).replace('/', '.');
        return className.isBlank() ? null : className;
    }
}
