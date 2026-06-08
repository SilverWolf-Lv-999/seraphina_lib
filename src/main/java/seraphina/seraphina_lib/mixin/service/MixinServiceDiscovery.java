package seraphina.seraphina_lib.mixin.service;

import cpw.mods.jarhandling.SecureJar;
import seraphina.seraphina_lib.service.ISeraMixin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class MixinServiceDiscovery {
    static final String SERVICE_INTERFACE = "seraphina.seraphina_lib.service.ISeraMixin";
    static final String SERVICE_FILE = "META-INF/services/" + SERVICE_INTERFACE;

    private static final String BUILTIN_PROVIDER = "seraphina.seraphina_mod.SeraLibMixin";

    private final SeraMixinLaunchPluginService service;
    private final Set<String> queuedServiceProviders = ConcurrentHashMap.newKeySet();
    private final Set<String> loadedServiceProviders = ConcurrentHashMap.newKeySet();
    private final Set<ClassLoader> scannedServiceLoaders = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Boolean> loadingServices = ThreadLocal.withInitial(() -> false);
    private volatile boolean printedServiceLoaderNotReady;
    private volatile boolean printedServiceLoaderTypeMismatch;
    private volatile boolean serviceDiscoveryDone;

    MixinServiceDiscovery(SeraMixinLaunchPluginService service) {
        this.service = service;
        this.queuedServiceProviders.add(BUILTIN_PROVIDER);
    }

    void ensureLoaded() {
        this.service.drainPendingMixins();
        if (this.serviceDiscoveryDone) {
            return;
        }
        if (Boolean.TRUE.equals(this.loadingServices.get())) {
            return;
        }
        synchronized (this) {
            this.service.drainPendingMixins();
            if (this.serviceDiscoveryDone) {
                return;
            }
            this.loadingServices.set(true);
            try {
                this.readServiceProviderFilesFromClassLoaders();
                boolean queuedProvidersLoaded = this.loadQueuedProviders();
                boolean contextProvidersLoaded = this.loadServiceLoaderProviders(Thread.currentThread().getContextClassLoader());
                boolean serviceProvidersLoaded = this.loadServiceLoaderProviders(this.service.getClass().getClassLoader());
                this.serviceDiscoveryDone = queuedProvidersLoaded && contextProvidersLoaded && serviceProvidersLoaded;
            } finally {
                this.loadingServices.remove();
            }
        }
    }

    void markDirty() {
        this.serviceDiscoveryDone = false;
    }

    void readServiceProviderFile(SecureJar jar) {
        if (jar == null) {
            return;
        }
        try {
            Path providerPath = jar.getPath("META-INF", "services", SERVICE_INTERFACE);
            this.readServiceProviderFile(providerPath);
        } catch (Throwable ignored) {
        }
    }

    void readServiceProviderFile(Path providerPath) {
        if (providerPath == null || !Files.exists(providerPath)) {
            return;
        }
        try {
            this.readServiceProviderLines(Files.readAllLines(providerPath));
            this.markDirty();
        } catch (IOException exception) {
            SeraMixinLogger.error("Failed to read {}: {}", providerPath, exception.getMessage());
        }
    }

    private void readServiceProviderFilesFromClassLoaders() {
        for (ClassLoader classLoader : this.serviceClassLoaderCandidates()) {
            if (!this.scannedServiceLoaders.add(classLoader)) {
                continue;
            }
            try {
                Enumeration<URL> resources = classLoader.getResources(SERVICE_FILE);
                while (resources.hasMoreElements()) {
                    this.readServiceProviderUrl(resources.nextElement());
                }
            } catch (Throwable throwable) {
                if (!isClassLoadingNotReady(throwable)) {
                    SeraMixinLogger.error("Failed to scan {} from {}: {}", SERVICE_FILE, classLoader, throwable);
                }
            }
        }
    }

    private Set<ClassLoader> serviceClassLoaderCandidates() {
        LinkedHashSet<ClassLoader> classLoaders = new LinkedHashSet<>();
        addClassLoader(classLoaders, Thread.currentThread().getContextClassLoader());
        addClassLoader(classLoaders, this.service.getClass().getClassLoader());
        addClassLoader(classLoaders, this.service.getRuntimeClassLoader());
        addClassLoader(classLoaders, ClassLoader.getSystemClassLoader());
        return classLoaders;
    }

    private static void addClassLoader(Set<ClassLoader> classLoaders, ClassLoader classLoader) {
        if (classLoader != null) {
            classLoaders.add(classLoader);
        }
    }

    private void readServiceProviderUrl(URL url) {
        if (url == null) {
            return;
        }
        try (InputStream inputStream = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            this.readServiceProviderLines(reader.lines().toList());
            this.markDirty();
        } catch (IOException exception) {
            SeraMixinLogger.error("Failed to read {}: {}", url, exception.getMessage());
        }
    }

    private void readServiceProviderLines(Iterable<String> lines) {
        for (String line : lines) {
            String provider = stripServiceComment(line).trim();
            if (!provider.isEmpty()) {
                this.queuedServiceProviders.add(provider);
            }
        }
    }

    private boolean loadQueuedProviders() {
        boolean allProvidersLoaded = true;
        for (String providerClassName : new ArrayList<>(this.queuedServiceProviders)) {
            if (this.loadedServiceProviders.contains(providerClassName)) {
                continue;
            }
            try {
                Class<?> providerClass = this.service.resolveClass(providerClassName, this.service.getRuntimeClassLoader());
                if (!ISeraMixin.class.isAssignableFrom(providerClass)) {
                    SeraMixinLogger.error("Service provider does not implement ISeraMixin: {}", providerClassName);
                    this.loadedServiceProviders.add(providerClassName);
                    continue;
                }
                ISeraMixin provider = (ISeraMixin) providerClass.getDeclaredConstructor().newInstance();
                this.registerProviderInstance(provider);
            } catch (Throwable throwable) {
                allProvidersLoaded = false;
                if (isClassLoadingNotReady(throwable)) {
                    this.printServiceLoaderNotReady(throwable);
                } else {
                    SeraMixinLogger.error("Failed to load service provider {}: {}", providerClassName, throwable);
                    SeraMixinLogger.exception(throwable);
                }
            }
        }
        return allProvidersLoaded;
    }

    private boolean loadServiceLoaderProviders(ClassLoader classLoader) {
        if (classLoader == null) {
            return true;
        }
        boolean allProvidersLoaded = true;
        try {
            ServiceLoader<ISeraMixin> loader = ServiceLoader.load(ISeraMixin.class, classLoader);
            for (ISeraMixin provider : loader) {
                if (provider == null || this.loadedServiceProviders.contains(provider.getClass().getName())) {
                    continue;
                }
                try {
                    this.registerProviderInstance(provider);
                } catch (Throwable throwable) {
                    allProvidersLoaded = false;
                    SeraMixinLogger.error("Failed to register ISeraMixin service {}: {}",
                            provider.getClass().getName(), throwable);
                    SeraMixinLogger.exception(throwable);
                }
            }
        } catch (ServiceConfigurationError error) {
            if (isProviderTypeMismatch(error)) {
                this.printServiceLoaderTypeMismatch(error);
                return true;
            }
            allProvidersLoaded = false;
            if (isClassLoadingNotReady(error)) {
                this.printServiceLoaderNotReady(error);
            } else {
                SeraMixinLogger.error("Failed to load ISeraMixin services: {}", error);
                SeraMixinLogger.exception(error);
            }
        } catch (Throwable throwable) {
            allProvidersLoaded = false;
            if (isClassLoadingNotReady(throwable)) {
                this.printServiceLoaderNotReady(throwable);
            } else {
                SeraMixinLogger.error("ISeraMixin services are not ready yet: {}", throwable);
                SeraMixinLogger.exception(throwable);
            }
        }
        return allProvidersLoaded;
    }

    private void printServiceLoaderTypeMismatch(ServiceConfigurationError error) {
        if (this.printedServiceLoaderTypeMismatch) {
            return;
        }
        this.printedServiceLoaderTypeMismatch = true;
        SeraMixinLogger.warn("Ignoring incompatible ISeraMixin ServiceLoader provider: {}", error.getMessage());
    }

    private void printServiceLoaderNotReady(Throwable throwable) {
        if (this.printedServiceLoaderNotReady) {
            return;
        }
        this.printedServiceLoaderNotReady = true;
        SeraMixinLogger.warn("ISeraMixin services are not ready yet, will retry: {}", throwable);
    }

    private static boolean isClassLoadingNotReady(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof NoClassDefFoundError || current instanceof ClassNotFoundException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProviderTypeMismatch(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ServiceConfigurationError
                    && current.getMessage() != null
                    && current.getMessage().contains("not a subtype")) {
                return true;
            }
        }
        return false;
    }

    boolean registerProviderInstance(ISeraMixin provider) {
        if (provider == null) {
            return false;
        }
        String providerClassName = provider.getClass().getName();
        if (!this.loadedServiceProviders.add(providerClassName)) {
            return false;
        }
        try {
            this.registerProvider(provider);
            return true;
        } catch (Throwable throwable) {
            this.loadedServiceProviders.remove(providerClassName);
            throw throwable;
        }
    }

    private void registerProvider(ISeraMixin provider) {
        SeraMixinLogger.info("Loaded ISeraMixin provider: {}", provider.getClass().getName());
        this.service.prepareProviderMapping(provider);

        try {
            provider.onLoad();
        } catch (Throwable throwable) {
            SeraMixinLogger.error("ISeraMixin.onLoad failed for {}: {}",
                    provider.getClass().getName(), throwable.getMessage());
        }

        String mixinPath = null;
        try {
            mixinPath = provider.getMixinPath();
        } catch (Throwable throwable) {
            SeraMixinLogger.error("ISeraMixin.getMixinPath failed for {}: {}",
                    provider.getClass().getName(), throwable.getMessage());
        }

        if (mixinPath != null && !mixinPath.isBlank()) {
            int registered = this.service.registerMixinPackage(mixinPath, provider.getClass().getClassLoader(), provider);
            if (registered >= 0) {
                return;
            }
            try {
                this.service.registerMixinFromASM(mixinPath, provider.getClass().getClassLoader(), provider);
                return;
            } catch (Throwable throwable) {
                SeraMixinLogger.error("Failed to register mixin {}: {}", mixinPath, throwable.getMessage());
            }
        }

        if (this.service.hasSeraMixinAnnotation(provider.getClass().getName(), provider.getClass().getClassLoader())) {
            this.service.registerMixinFromASM(provider.getClass().getName(), provider.getClass().getClassLoader(), provider);
        }
    }

    private static String stripServiceComment(String line) {
        int commentIndex = line.indexOf('#');
        return commentIndex >= 0 ? line.substring(0, commentIndex) : line;
    }
}
