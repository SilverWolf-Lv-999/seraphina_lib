package seraphina.seraphina_lib.mixin.service;

import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.FMLLoader;
import seraphina.seraphina_lib.service.ISeraMixin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MixinMappingManager {
    private final Map<String, MixinMappingResolver> providerMappings = new ConcurrentHashMap<>();

    MixinMappingResolver resolverForProvider(ISeraMixin provider) {
        if (provider == null) {
            return MixinMappingResolver.EMPTY;
        }
        return this.providerMappings.computeIfAbsent(provider.getClass().getName(), ignored -> this.loadProviderMapping(provider));
    }

    MixinMappingResolver mappingFor(ISeraMixin provider) {
        if (provider == null) {
            return MixinMappingResolver.EMPTY;
        }
        return this.providerMappings.getOrDefault(provider.getClass().getName(), MixinMappingResolver.EMPTY);
    }

    private MixinMappingResolver loadProviderMapping(ISeraMixin provider) {
        String resourcePath = safeMappingPath(provider);
        if (resourcePath == null || resourcePath.isBlank()) {
            return MixinMappingResolver.EMPTY;
        }
        if (!isProductionEnvironment()) {
            return MixinMappingResolver.EMPTY;
        }

        String mappingType = safeMappingType(provider);
        try {
            Path mappingFile = this.extractMappingFile(provider, resourcePath, mappingType);
            if (mappingFile == null) {
                return MixinMappingResolver.EMPTY;
            }
            MixinMappingResolver resolver = MixinMappingResolver.readMapping(mappingFile, mappingType);
            if (resolver.isEnabled()) {
                SeraMixinLogger.info("Loaded mapping {}", mappingFile.toAbsolutePath());
            }
            return resolver;
        } catch (Throwable throwable) {
            SeraMixinLogger.error("Failed to load mapping for {}: {}", provider.getClass().getName(), throwable.getMessage());
            return MixinMappingResolver.EMPTY;
        }
    }

    private Path extractMappingFile(ISeraMixin provider, String resourcePath, String mappingType) throws IOException {
        String normalizedResourcePath = normalizeResourcePath(resourcePath);
        byte[] data = this.readResource(provider.getClass(), normalizedResourcePath);
        if (data == null) {
            SeraMixinLogger.warn("Mapping resource not found in jar: {}", resourcePath);
            return null;
        }

        Path targetDir = gameDir().resolve("seraphina_mixin").resolve("srg");
        Files.createDirectories(targetDir);
        Path targetFile = targetDir.resolve(outputFileName(normalizedResourcePath, mappingType));

        boolean needWrite = true;
        if (Files.exists(targetFile) && Files.size(targetFile) == data.length) {
            needWrite = false;
        }
        if (needWrite) {
            writeMappingFile(targetFile, data);
            SeraMixinLogger.info("Extracted mapping -> {}", targetFile.toAbsolutePath());
        }
        return targetFile;
    }

    private static void writeMappingFile(Path targetFile, byte[] data) throws IOException {
        try {
            Files.write(targetFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw exception;
        }
    }

    private byte[] readResource(Class<?> providerClass, String resourcePath) throws IOException {
        ClassLoader classLoader = providerClass.getClassLoader();
        InputStream stream = classLoader == null
                ? ClassLoader.getSystemResourceAsStream(resourcePath)
                : classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            stream = providerClass.getResourceAsStream('/' + resourcePath);
        }
        if (stream == null) {
            ClassLoader libClassLoader = ISeraMixin.class.getClassLoader();
            stream = libClassLoader == null
                    ? ClassLoader.getSystemResourceAsStream(resourcePath)
                    : libClassLoader.getResourceAsStream(resourcePath);
        }
        if (stream == null) {
            return null;
        }
        try (InputStream input = stream) {
            return input.readAllBytes();
        }
    }

    private static Path gameDir() {
        try {
            return FMLPaths.GAMEDIR.get();
        } catch (Throwable ignored) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private static String outputFileName(String resourcePath, String mappingType) {
        String name = resourcePath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }

        String type = mappingType == null || mappingType.isBlank() ? "srg" : mappingType.trim();
        if (!type.startsWith(".")) {
            type = "." + type;
        }
        return name + type;
    }

    private static String normalizeResourcePath(String resourcePath) {
        String normalized = resourcePath.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String safeMappingPath(ISeraMixin provider) {
        try {
            return provider.getMappingPath();
        } catch (Throwable throwable) {
            SeraMixinLogger.error("ISeraMixin.getMappingPath failed for {}: {}",
                    provider.getClass().getName(), throwable.getMessage());
            return "";
        }
    }

    private static String safeMappingType(ISeraMixin provider) {
        try {
            String mappingType = provider.getMappingType();
            return mappingType == null || mappingType.isBlank() ? "srg" : mappingType;
        } catch (Throwable throwable) {
            SeraMixinLogger.error("ISeraMixin.getMappingType failed for {}: {}",
                    provider.getClass().getName(), throwable.getMessage());
            return "srg";
        }
    }

    private static boolean isProductionEnvironment() {
        try {
            return FMLLoader.isProduction() || System.getProperties().containsKey("production");
        } catch (Throwable ignored) {
            return System.getProperties().containsKey("production");
        }
    }
}

final class MixinMappingResolver {
    static final MixinMappingResolver EMPTY = new MixinMappingResolver(false, Map.of(), Map.of(), Map.of());

    private final boolean enabled;
    private final Map<String, String> classNames;
    private final Map<String, String> reverseClassNames;
    private final Map<String, MappingClass> classMappings;

    private MixinMappingResolver(boolean enabled, Map<String, String> classNames,
                                 Map<String, String> reverseClassNames,
                                 Map<String, MappingClass> classMappings) {
        this.enabled = enabled;
        this.classNames = classNames;
        this.reverseClassNames = reverseClassNames;
        this.classMappings = classMappings;
    }

    static MixinMappingResolver readMapping(Path mappingFile, String mappingType) throws IOException {
        MappingBuilder builder = new MappingBuilder();
        try (BufferedReader reader = Files.newBufferedReader(mappingFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                readSrgLine(builder, line);
            }
        }
        return builder.build();
    }

    boolean isEnabled() {
        return this.enabled;
    }

    String mapClassName(String internalName) {
        if (!this.enabled || internalName == null || internalName.isBlank()) {
            return internalName;
        }
        String normalized = normalizeInternalName(internalName);
        return this.classNames.getOrDefault(normalized, normalized);
    }

    String mapFieldName(String ownerInternalName, String fieldName) {
        if (!this.enabled || fieldName == null || fieldName.isBlank()) {
            return fieldName;
        }
        MappingClass mappingClass = this.findClassMapping(ownerInternalName);
        if (mappingClass == null) {
            return fieldName;
        }
        return mappingClass.fields.getOrDefault(fieldName, fieldName);
    }

    MappedMethod mapMethod(String ownerInternalName, String methodName, String desc) {
        if (!this.enabled || methodName == null || methodName.isBlank()) {
            return new MappedMethod(methodName, desc);
        }

        MappingClass mappingClass = this.findClassMapping(ownerInternalName);
        if (mappingClass != null) {
            List<MappingMethod> methods = mappingClass.methods.get(methodName);
            MappingMethod method = this.findMethod(methods, desc);
            if (method != null) {
                return new MappedMethod(method.runtimeName, method.runtimeDesc);
            }
        }
        return new MappedMethod(methodName, this.mapDescriptor(desc));
    }

    String mapDescriptor(String desc) {
        if (!this.enabled || desc == null || desc.isBlank()) {
            return desc;
        }
        StringBuilder builder = new StringBuilder(desc.length());
        for (int i = 0; i < desc.length(); i++) {
            char c = desc.charAt(i);
            if (c != 'L') {
                builder.append(c);
                continue;
            }
            int end = desc.indexOf(';', i);
            if (end < 0) {
                builder.append(desc.substring(i));
                break;
            }
            String internalName = desc.substring(i + 1, end);
            builder.append('L').append(this.mapClassName(internalName)).append(';');
            i = end;
        }
        return builder.toString();
    }

    private MappingClass findClassMapping(String ownerInternalName) {
        if (ownerInternalName == null || ownerInternalName.isBlank()) {
            return null;
        }
        String normalized = normalizeInternalName(ownerInternalName);
        MappingClass mappingClass = this.classMappings.get(normalized);
        if (mappingClass != null) {
            return mappingClass;
        }
        String sourceName = this.reverseClassNames.get(normalized);
        return sourceName == null ? null : this.classMappings.get(sourceName);
    }

    private MappingMethod findMethod(List<MappingMethod> methods, String desc) {
        if (methods == null || methods.isEmpty()) {
            return null;
        }
        if (desc == null || desc.isBlank()) {
            return methods.get(0);
        }
        String mappedDesc = this.mapDescriptor(desc);
        for (MappingMethod method : methods) {
            if (desc.equals(method.sourceDesc) || desc.equals(method.runtimeDesc)
                    || mappedDesc.equals(method.sourceDesc) || mappedDesc.equals(method.runtimeDesc)) {
                return method;
            }
        }
        return null;
    }

    private static void readSrgLine(MappingBuilder builder, String rawLine) {
        String line = stripComment(rawLine).trim();
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            return;
        }

        if ("CL:".equals(parts[0])) {
            builder.addClass(parts[1], parts[2]);
            return;
        }
        if ("FD:".equals(parts[0])) {
            String[] runtime = splitClassMember(parts[1]);
            String[] source = splitClassMember(parts[2]);
            if (runtime[1].isEmpty() || source[1].isEmpty()) {
                return;
            }
            builder.addField(runtime[0], runtime[1], source[0], source[1]);
            return;
        }
        if ("MD:".equals(parts[0]) && parts.length >= 5) {
            String[] runtime = splitClassMember(parts[1]);
            String[] source = splitClassMember(parts[3]);
            if (runtime[1].isEmpty() || source[1].isEmpty()) {
                return;
            }
            builder.addMethod(runtime[0], runtime[1], parts[2], source[0], source[1], parts[4]);
        }
    }

    private static String[] splitClassMember(String raw) {
        int index = raw.lastIndexOf('/');
        if (index < 0) {
            index = raw.lastIndexOf('.');
        }
        if (index < 0) {
            return new String[] {normalizeInternalName(raw), ""};
        }
        return new String[] {normalizeInternalName(raw.substring(0, index)), raw.substring(index + 1)};
    }

    private static String normalizeInternalName(String name) {
        String normalized = name.replace('.', '/');
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String stripComment(String line) {
        int index = line.indexOf('#');
        return index >= 0 ? line.substring(0, index) : line;
    }

    private static <K, V> V putValue(Map<K, V> map, K key, V value) {
        return map.put(key, value);
    }

    private static final class MappingBuilder {
        private final Map<String, String> classNames = new HashMap<>();
        private final Map<String, String> reverseClassNames = new HashMap<>();
        private final Map<String, MappingClass> classMappings = new HashMap<>();

        void addClass(String runtimeName, String sourceName) {
            String runtime = normalizeInternalName(runtimeName);
            String source = normalizeInternalName(sourceName);
            putValue(this.classNames, source, runtime);
            putValue(this.reverseClassNames, runtime, source);
            this.classMappings.computeIfAbsent(source, ignored -> new MappingClass());
        }

        void addField(String runtimeOwner, String runtimeName, String sourceOwner, String sourceName) {
            putValue(this.ensureClassMapping(runtimeOwner, sourceOwner).fields, sourceName, runtimeName);
        }

        void addMethod(String runtimeOwner, String runtimeName, String runtimeDesc,
                       String sourceOwner, String sourceName, String sourceDesc) {
            this.ensureClassMapping(runtimeOwner, sourceOwner)
                    .methods
                    .computeIfAbsent(sourceName, ignored -> new ArrayList<>())
                    .add(new MappingMethod(runtimeName, runtimeDesc, sourceDesc));
        }

        MixinMappingResolver build() {
            boolean enabled = !this.classNames.isEmpty() || !this.classMappings.isEmpty();
            return new MixinMappingResolver(
                    enabled,
                    Map.copyOf(this.classNames),
                    Map.copyOf(this.reverseClassNames),
                    copyClassMappings(this.classMappings));
        }

        private MappingClass ensureClassMapping(String runtimeOwner, String sourceOwner) {
            String runtime = normalizeInternalName(runtimeOwner);
            String source = normalizeInternalName(sourceOwner);
            this.classNames.putIfAbsent(source, runtime);
            this.reverseClassNames.putIfAbsent(runtime, source);
            return this.classMappings.computeIfAbsent(source, ignored -> new MappingClass());
        }

        private static Map<String, MappingClass> copyClassMappings(Map<String, MappingClass> source) {
            HashMap<String, MappingClass> copy = new HashMap<>();
            for (Map.Entry<String, MappingClass> entry : source.entrySet()) {
                putValue(copy, entry.getKey(), entry.getValue().copy());
            }
            return Map.copyOf(copy);
        }
    }

    private static final class MappingClass {
        private final Map<String, String> fields = new HashMap<>();
        private final Map<String, List<MappingMethod>> methods = new HashMap<>();

        MappingClass copy() {
            MappingClass copy = new MappingClass();
            copy.fields.putAll(this.fields);
            for (Map.Entry<String, List<MappingMethod>> entry : this.methods.entrySet()) {
                putValue(copy.methods, entry.getKey(), List.copyOf(entry.getValue()));
            }
            return copy;
        }
    }

    private record MappingMethod(String runtimeName, String runtimeDesc, String sourceDesc) {
    }
}

record MappedMethod(String name, String desc) {
}
