package seraphina.seraphina_lib.mixin.service;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.jetbrains.annotations.NotNull;
import seraphina.seraphina_lib.util.HelperLib;

import java.lang.reflect.Constructor;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class SeraMixinTransformationService implements ITransformationService {
    private static final String TRANSFORMATION_SERVICE_NAME = "seraphina_mixin_transformation_service";
    private static final String LAUNCH_PLUGIN_NAME = "seraphina_mixin_plugin_service";
    private static final long LOCK_THREAD_LIFETIME_MILLIS = 30_000L;
    private static final long LOCK_THREAD_INTERVAL_MILLIS = 25L;
    private static final SeraMixinLaunchPluginService LAUNCH_PLUGIN = new SeraMixinLaunchPluginService();
    private static volatile SeraMixinTransformationService currentService;
    private static volatile boolean lockThreadStarted;
    private static volatile boolean launchPluginGuardFailureLogged;
    private static volatile boolean transformationServiceGuardFailureLogged;

    public SeraMixinTransformationService() {
        currentService = this;
        installServiceLocks();
    }

    @Override
    public @NotNull String name() {
        installServiceLocks();
        return TRANSFORMATION_SERVICE_NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        installServiceLocks();
        HelperLib.coexistenceCoreAndMod();
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        installServiceLocks();
    }

    @Override
    public @NotNull List<ITransformer> transformers() {
        installServiceLocks();
        return List.of();
    }

    static void installServiceLocks() {
        installLaunchPluginLock();
        installTransformationServiceLock();
        startLockThread();
    }

    @SuppressWarnings("unchecked")
    private static void installLaunchPluginLock() {
        try {
            Launcher launcher = Launcher.INSTANCE;
            if (launcher == null) {
                return;
            }
            LaunchPluginHandler handler = HelperLib.getFieldValue(launcher, "launchPlugins", LaunchPluginHandler.class);
            if (handler == null) {
                return;
            }
            Map<String, ILaunchPluginService> plugins =
                    (Map<String, ILaunchPluginService>) HelperLib.getFieldValue(handler, "plugins", Map.class);
            Map<String, ILaunchPluginService> locked = lockMap(
                    plugins,
                    LAUNCH_PLUGIN_NAME,
                    () -> LAUNCH_PLUGIN,
                    SeraMixinTransformationService::isSeraMixinLaunchPlugin);
            if (plugins != locked) {
                HelperLib.setFieldValue(handler, "plugins", locked);
            }
        } catch (Throwable throwable) {
            logLaunchPluginGuardFailure(throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static void installTransformationServiceLock() {
        try {
            Launcher launcher = Launcher.INSTANCE;
            if (launcher == null) {
                return;
            }
            Object handler = HelperLib.getFieldValue(launcher, "transformationServicesHandler", Object.class);
            if (handler == null) {
                return;
            }
            Map<String, Object> services =
                    (Map<String, Object>) HelperLib.getFieldValue(handler, "serviceLookup", Map.class);
            if (services == null) {
                return;
            }
            Map<String, Object> locked = lockMap(
                    services,
                    TRANSFORMATION_SERVICE_NAME,
                    SeraMixinTransformationService::newTransformationServiceDecorator,
                    SeraMixinTransformationService::isSeraMixinTransformationDecorator);
            if (services != locked) {
                HelperLib.setFieldValue(handler, "serviceLookup", locked);
            }
        } catch (Throwable throwable) {
            logTransformationServiceGuardFailure(throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> lockMap(Map<String, V> source, String protectedKey,
                                             Supplier<V> protectedValueSupplier,
                                             Predicate<V> protectedValuePredicate) {
        if (source instanceof GuardedServiceMap<?> guardedMap && guardedMap.protects(protectedKey)) {
            GuardedServiceMap<V> locked = (GuardedServiceMap<V>) guardedMap;
            locked.restoreProtectedEntry();
            return locked;
        }
        return new GuardedServiceMap<>(source, protectedKey, protectedValueSupplier, protectedValuePredicate);
    }

    private static boolean isSeraMixinLaunchPlugin(ILaunchPluginService plugin) {
        return plugin instanceof SeraMixinLaunchPluginService;
    }

    private static boolean isSeraMixinTransformationDecorator(Object decorator) {
        if (decorator == null) {
            return false;
        }
        Object service = HelperLib.getFieldValue(decorator, "service", ITransformationService.class);
        return service instanceof SeraMixinTransformationService;
    }

    private static Object newTransformationServiceDecorator() {
        SeraMixinTransformationService service = currentService;
        if (service == null) {
            return null;
        }
        try {
            Class<?> decoratorClass = Class.forName(
                    "cpw.mods.modlauncher.TransformationServiceDecorator",
                    false,
                    Launcher.class.getClassLoader());
            Constructor<?> constructor = decoratorClass.getDeclaredConstructor(ITransformationService.class);
            constructor.trySetAccessible();
            return constructor.newInstance(service);
        } catch (Throwable throwable) {
            logTransformationServiceGuardFailure(throwable);
            return null;
        }
    }

    private static void startLockThread() {
        if (lockThreadStarted) {
            return;
        }
        synchronized (SeraMixinTransformationService.class) {
            if (lockThreadStarted) {
                return;
            }
            lockThreadStarted = true;
        }
        Thread thread = new Thread(SeraMixinTransformationService::runLockLoop, "SeraMixin-ServiceLock");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runLockLoop() {
        long deadline = System.currentTimeMillis() + LOCK_THREAD_LIFETIME_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            installLaunchPluginLock();
            installTransformationServiceLock();
            try {
                Thread.sleep(LOCK_THREAD_INTERVAL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void logLaunchPluginGuardFailure(Throwable throwable) {
        if (launchPluginGuardFailureLogged) {
            return;
        }
        launchPluginGuardFailureLogged = true;
        SeraMixinLogger.warn("Failed to lock SeraMixin launch plugin map: {}", throwable.getMessage());
        SeraMixinLogger.exception(throwable);
    }

    private static void logTransformationServiceGuardFailure(Throwable throwable) {
        if (transformationServiceGuardFailureLogged) {
            return;
        }
        transformationServiceGuardFailureLogged = true;
        SeraMixinLogger.warn("Failed to lock SeraMixin transformation service map: {}", throwable.getMessage());
        SeraMixinLogger.exception(throwable);
    }

    static {
        installLaunchPluginLock();
        HelperLib.coexistenceCoreAndMod();
        startLockThread();
    }

    private static final class GuardedServiceMap<V> extends AbstractMap<String, V> {
        private final LinkedHashMap<String, V> delegate = new LinkedHashMap<>();
        private final String protectedKey;
        private final Supplier<V> protectedValueSupplier;
        private final Predicate<V> protectedValuePredicate;
        private V protectedValue;
        private boolean restoring;

        GuardedServiceMap(Map<String, V> source, String protectedKey, Supplier<V> protectedValueSupplier,
                          Predicate<V> protectedValuePredicate) {
            this.protectedKey = Objects.requireNonNull(protectedKey, "protectedKey");
            this.protectedValueSupplier = Objects.requireNonNull(protectedValueSupplier, "protectedValueSupplier");
            this.protectedValuePredicate = Objects.requireNonNull(protectedValuePredicate, "protectedValuePredicate");
            this.protectedValue = findProtectedValue(source);
            restoreProtectedEntry();
            if (source != null) {
                for (Map.Entry<String, V> entry : source.entrySet()) {
                    if (!isProtectedEntry(entry.getKey(), entry.getValue())) {
                        this.delegate.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            restoreProtectedEntry();
        }

        boolean protects(String key) {
            return this.protectedKey.equals(key);
        }

        void restoreProtectedEntry() {
            if (this.restoring) {
                return;
            }
            this.restoring = true;
            try {
                V current = this.delegate.get(this.protectedKey);
                if (isProtectedValue(current)) {
                    this.protectedValue = current;
                    return;
                }
                V value = isProtectedValue(this.protectedValue) ? this.protectedValue : this.protectedValueSupplier.get();
                if (value == null) {
                    this.delegate.remove(this.protectedKey);
                    return;
                }
                this.protectedValue = value;
                this.delegate.put(this.protectedKey, value);
            } finally {
                this.restoring = false;
            }
        }

        @Override
        public V put(String key, V value) {
            if (isProtectedKey(key)) {
                V previous = this.delegate.get(this.protectedKey);
                if (isProtectedValue(value)) {
                    this.protectedValue = value;
                    return this.delegate.put(this.protectedKey, value);
                }
                restoreProtectedEntry();
                return previous;
            }
            V previous = this.delegate.put(key, value);
            restoreProtectedEntry();
            return previous;
        }

        @Override
        public V remove(Object key) {
            if (isProtectedKey(key)) {
                restoreProtectedEntry();
                return this.delegate.get(this.protectedKey);
            }
            V removed = this.delegate.remove(key);
            restoreProtectedEntry();
            return removed;
        }

        @Override
        public boolean remove(Object key, Object value) {
            if (isProtectedKey(key) || isProtectedObject(value)) {
                restoreProtectedEntry();
                return false;
            }
            boolean removed = this.delegate.remove(key, value);
            restoreProtectedEntry();
            return removed;
        }

        @Override
        public void clear() {
            this.delegate.clear();
            restoreProtectedEntry();
        }

        @Override
        public void putAll(Map<? extends String, ? extends V> map) {
            if (map == null) {
                return;
            }
            for (Map.Entry<? extends String, ? extends V> entry : map.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
            restoreProtectedEntry();
        }

        @Override
        public V putIfAbsent(String key, V value) {
            if (isProtectedKey(key)) {
                if (isProtectedValue(value) && !isProtectedValue(this.delegate.get(this.protectedKey))) {
                    this.protectedValue = value;
                    return this.delegate.put(this.protectedKey, value);
                }
                restoreProtectedEntry();
                return this.delegate.get(this.protectedKey);
            }
            V previous = this.delegate.putIfAbsent(key, value);
            restoreProtectedEntry();
            return previous;
        }

        @Override
        public boolean replace(String key, V oldValue, V newValue) {
            if (isProtectedKey(key)) {
                if (isProtectedValue(newValue)) {
                    this.protectedValue = newValue;
                    return this.delegate.replace(this.protectedKey, oldValue, newValue);
                }
                restoreProtectedEntry();
                return false;
            }
            boolean replaced = this.delegate.replace(key, oldValue, newValue);
            restoreProtectedEntry();
            return replaced;
        }

        @Override
        public V replace(String key, V value) {
            if (isProtectedKey(key)) {
                V previous = this.delegate.get(this.protectedKey);
                if (isProtectedValue(value)) {
                    this.protectedValue = value;
                    return this.delegate.replace(this.protectedKey, value);
                }
                restoreProtectedEntry();
                return previous;
            }
            V previous = this.delegate.replace(key, value);
            restoreProtectedEntry();
            return previous;
        }

        @Override
        public void replaceAll(BiFunction<? super String, ? super V, ? extends V> function) {
            Objects.requireNonNull(function, "function");
            this.delegate.replaceAll((key, oldValue) -> {
                if (!isProtectedKey(key)) {
                    return function.apply(key, oldValue);
                }
                V next = function.apply(key, oldValue);
                if (isProtectedValue(next)) {
                    this.protectedValue = next;
                    return next;
                }
                return oldValue;
            });
            restoreProtectedEntry();
        }

        @Override
        public V computeIfAbsent(String key, Function<? super String, ? extends V> mappingFunction) {
            Objects.requireNonNull(mappingFunction, "mappingFunction");
            if (isProtectedKey(key)) {
                restoreProtectedEntry();
                return this.delegate.get(this.protectedKey);
            }
            V value = this.delegate.computeIfAbsent(key, mappingFunction);
            restoreProtectedEntry();
            return value;
        }

        @Override
        public V computeIfPresent(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(remappingFunction, "remappingFunction");
            if (isProtectedKey(key)) {
                restoreProtectedEntry();
                return this.delegate.get(this.protectedKey);
            }
            V value = this.delegate.computeIfPresent(key, remappingFunction);
            restoreProtectedEntry();
            return value;
        }

        @Override
        public V compute(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(remappingFunction, "remappingFunction");
            if (isProtectedKey(key)) {
                restoreProtectedEntry();
                return this.delegate.get(this.protectedKey);
            }
            V value = this.delegate.compute(key, remappingFunction);
            restoreProtectedEntry();
            return value;
        }

        @Override
        public V merge(String key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(remappingFunction, "remappingFunction");
            if (isProtectedKey(key)) {
                restoreProtectedEntry();
                return this.delegate.get(this.protectedKey);
            }
            V merged = this.delegate.merge(key, value, remappingFunction);
            restoreProtectedEntry();
            return merged;
        }

        @Override
        public int size() {
            restoreProtectedEntry();
            return this.delegate.size();
        }

        @Override
        public boolean isEmpty() {
            restoreProtectedEntry();
            return this.delegate.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            restoreProtectedEntry();
            return this.delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            restoreProtectedEntry();
            return this.delegate.containsValue(value);
        }

        @Override
        public V get(Object key) {
            restoreProtectedEntry();
            return this.delegate.get(key);
        }

        @Override
        public Set<String> keySet() {
            restoreProtectedEntry();
            return new GuardedKeySet();
        }

        @Override
        public Collection<V> values() {
            restoreProtectedEntry();
            return new GuardedValues();
        }

        @Override
        public Set<Map.Entry<String, V>> entrySet() {
            restoreProtectedEntry();
            return new GuardedEntrySet();
        }

        private V findProtectedValue(Map<String, V> source) {
            if (source == null) {
                return null;
            }
            for (Map.Entry<String, V> entry : source.entrySet()) {
                if (isProtectedValue(entry.getValue())) {
                    return entry.getValue();
                }
            }
            V value = source.get(this.protectedKey);
            return isProtectedValue(value) ? value : null;
        }

        private boolean isProtectedKey(Object key) {
            return this.protectedKey.equals(key);
        }

        private boolean isProtectedEntry(Object key, Object value) {
            return isProtectedKey(key) || isProtectedObject(value);
        }

        @SuppressWarnings("unchecked")
        private boolean isProtectedObject(Object value) {
            try {
                return value != null && this.protectedValuePredicate.test((V) value);
            } catch (ClassCastException exception) {
                return false;
            }
        }

        private boolean isProtectedValue(V value) {
            return value != null && this.protectedValuePredicate.test(value);
        }

        private final class GuardedKeySet extends AbstractSet<String> {
            @Override
            public Iterator<String> iterator() {
                restoreProtectedEntry();
                Iterator<String> iterator = delegate.keySet().iterator();
                return new Iterator<>() {
                    private String current;

                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public String next() {
                        this.current = iterator.next();
                        return this.current;
                    }

                    @Override
                    public void remove() {
                        if (isProtectedKey(this.current)) {
                            restoreProtectedEntry();
                            return;
                        }
                        iterator.remove();
                        restoreProtectedEntry();
                    }
                };
            }

            @Override
            public int size() {
                return GuardedServiceMap.this.size();
            }

            @Override
            public boolean contains(Object key) {
                return GuardedServiceMap.this.containsKey(key);
            }

            @Override
            public boolean remove(Object key) {
                if (!delegate.containsKey(key) || isProtectedKey(key)) {
                    restoreProtectedEntry();
                    return false;
                }
                delegate.remove(key);
                restoreProtectedEntry();
                return true;
            }
        }

        private final class GuardedValues extends AbstractCollection<V> {
            @Override
            public Iterator<V> iterator() {
                restoreProtectedEntry();
                Iterator<V> iterator = delegate.values().iterator();
                return new Iterator<>() {
                    private V current;

                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public V next() {
                        this.current = iterator.next();
                        return this.current;
                    }

                    @Override
                    public void remove() {
                        if (isProtectedValue(this.current)) {
                            restoreProtectedEntry();
                            return;
                        }
                        iterator.remove();
                        restoreProtectedEntry();
                    }
                };
            }

            @Override
            public int size() {
                return GuardedServiceMap.this.size();
            }

            @Override
            public boolean contains(Object value) {
                return GuardedServiceMap.this.containsValue(value);
            }

            @Override
            public boolean remove(Object value) {
                if (isProtectedObject(value)) {
                    restoreProtectedEntry();
                    return false;
                }
                boolean removed = delegate.values().remove(value);
                restoreProtectedEntry();
                return removed;
            }
        }

        private final class GuardedEntrySet extends AbstractSet<Map.Entry<String, V>> {
            @Override
            public Iterator<Map.Entry<String, V>> iterator() {
                restoreProtectedEntry();
                Iterator<Map.Entry<String, V>> iterator = delegate.entrySet().iterator();
                return new Iterator<>() {
                    private Map.Entry<String, V> current;

                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Map.Entry<String, V> next() {
                        this.current = iterator.next();
                        return new GuardedEntry(this.current);
                    }

                    @Override
                    public void remove() {
                        if (this.current != null && isProtectedEntry(this.current.getKey(), this.current.getValue())) {
                            restoreProtectedEntry();
                            return;
                        }
                        iterator.remove();
                        restoreProtectedEntry();
                    }
                };
            }

            @Override
            public int size() {
                return GuardedServiceMap.this.size();
            }

            @Override
            public boolean contains(Object object) {
                restoreProtectedEntry();
                return delegate.entrySet().contains(object);
            }

            @Override
            public boolean remove(Object object) {
                if (!(object instanceof Map.Entry<?, ?> entry)) {
                    return false;
                }
                if (isProtectedEntry(entry.getKey(), entry.getValue())) {
                    restoreProtectedEntry();
                    return false;
                }
                boolean removed = delegate.entrySet().remove(object);
                restoreProtectedEntry();
                return removed;
            }
        }

        private final class GuardedEntry implements Map.Entry<String, V> {
            private final Map.Entry<String, V> delegateEntry;

            private GuardedEntry(Map.Entry<String, V> delegateEntry) {
                this.delegateEntry = delegateEntry;
            }

            @Override
            public String getKey() {
                return this.delegateEntry.getKey();
            }

            @Override
            public V getValue() {
                return this.delegateEntry.getValue();
            }

            @Override
            public V setValue(V value) {
                if (isProtectedKey(getKey())) {
                    V previous = getValue();
                    if (isProtectedValue(value)) {
                        protectedValue = value;
                        return this.delegateEntry.setValue(value);
                    }
                    restoreProtectedEntry();
                    return previous;
                }
                V previous = this.delegateEntry.setValue(value);
                restoreProtectedEntry();
                return previous;
            }

            @Override
            public boolean equals(Object object) {
                return this.delegateEntry.equals(object);
            }

            @Override
            public int hashCode() {
                return this.delegateEntry.hashCode();
            }

            @Override
            public String toString() {
                return this.delegateEntry.toString();
            }
        }
    }
}
