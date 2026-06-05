package seraphina.seraphina_lib.mixin.service;

import cpw.mods.jarhandling.SecureJar;
import seraphina.seraphina_lib.service.ISeraMixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class MixinServiceDiscovery {
    private final SeraMixinLaunchPluginService service;
    private final Set<String> queuedServiceProviders = ConcurrentHashMap.newKeySet();
    private final Set<String> loadedServiceProviders = ConcurrentHashMap.newKeySet();
    private volatile boolean serviceDiscoveryDone;

    MixinServiceDiscovery(SeraMixinLaunchPluginService service) {
        this.service = service;
    }

    void ensureLoaded() {
        this.service.drainPendingMixins();
        if (this.serviceDiscoveryDone) {
            return;
        }
        synchronized (this) {
            this.service.drainPendingMixins();
            if (this.serviceDiscoveryDone) {
                return;
            }
            this.loadQueuedProviders();
            this.loadServiceLoaderProviders(Thread.currentThread().getContextClassLoader());
            this.loadServiceLoaderProviders(this.service.getClass().getClassLoader());
            this.serviceDiscoveryDone = true;
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
            Path providerPath = jar.getPath("META-INF", "services", ISeraMixin.class.getName());
            this.readServiceProviderFile(providerPath);
        } catch (Throwable ignored) {
        }
    }

    void readServiceProviderFile(Path providerPath) {
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
            this.markDirty();
        } catch (IOException exception) {
            System.err.println("[SeraMixin] Failed to read " + providerPath + ": " + exception.getMessage());
        }
    }

    private void loadQueuedProviders() {
        for (String providerClassName : new ArrayList<>(this.queuedServiceProviders)) {
            if (!this.loadedServiceProviders.add(providerClassName)) {
                continue;
            }
            try {
                Class<?> providerClass = this.service.resolveClass(providerClassName, this.service.getRuntimeClassLoader());
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
        this.service.prepareProviderMapping(provider);

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
            int registered = this.service.registerMixinPackage(mixinPath, provider.getClass().getClassLoader(), provider);
            if (registered >= 0) {
                return;
            }
            try {
                this.service.registerMixinFromASM(mixinPath, provider.getClass().getClassLoader(), provider);
                return;
            } catch (Throwable throwable) {
                System.err.println("[SeraMixin] Failed to register mixin " + mixinPath + ": " + throwable.getMessage());
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
