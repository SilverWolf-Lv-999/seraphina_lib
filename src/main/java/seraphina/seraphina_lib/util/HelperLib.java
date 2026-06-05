package seraphina.seraphina_lib.util;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.ModuleLayerHandler;
import cpw.mods.modlauncher.api.NamedPath;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import org.apache.commons.compress.utils.IOUtils;
import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;
import seraphina.seraphina_lib.util.clazz.ClassUtils;
import seraphina.seraphina_lib.util.clazz.UnsafeAccess;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.module.ResolvedModule;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * General reflection, module, and class-resource helpers used by the library.
 * <p>
 * Most methods in this class are low-level utilities for launch-time code where
 * normal reflective access can be blocked by modules or access checks.
 */
public final class HelperLib {
    static final Logger LOGGER = LoggerFactory.getLogger(HelperLib.class);

    /**
     * Returns the filesystem path of the jar or classpath location that contains
     * {@link HelperLib}.
     *
     * @return absolute jar path, or an empty string when it cannot be resolved
     */
    public static String getJarPath() {
        try {
            CodeSource codeSource = HelperLib.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                URL jarUrl = codeSource.getLocation();
                return new File(jarUrl.getPath()).getAbsolutePath().split("%")[0];
            }
        } catch (Throwable e) {
            LOGGER.warn(e.getMessage());
        }
        return "";
    }

    /**
     * Copies non-static field values from one object to another object by field name.
     * <p>
     * Fields that cannot be read or written are logged and skipped.
     *
     * @param old source object
     * @param next target object
     */
    public static void copyFields(Object old, Object next) {
        Map<String, Object> oldFieldMap = new HashMap<>();
        for (Field field : old.getClass().getDeclaredFields()) {
            try {
                if (!Modifier.isStatic(field.getModifiers())) {
                    oldFieldMap.put(field.getName(), ClassUtils.LOOKUP.unreflectGetter(field).invoke(old));
                }
            } catch (Throwable e) {
                LOGGER.exception(e);
            }
        }
        for (Field field : next.getClass().getDeclaredFields()) {
            if (oldFieldMap.containsKey(field.getName())) {
                Object obj = oldFieldMap.get(field.getName());
                try {
                    ClassUtils.LOOKUP.unreflectSetter(field).invoke(next, obj);
                } catch (Throwable e) {
                    LOGGER.exception(e);
                }
            }
        }
    }

    /**
     * Reads a field value from an object, searching the target class hierarchy.
     *
     * @param target object to read from
     * @param fieldName declared field name
     * @param clazz expected field type token
     * @param <T> expected result type
     * @return field value cast to {@code T}, or {@code null} when not found or unreadable
     */
    public static <T> T getFieldValue(Object target, String fieldName, Class<T> clazz) {
        if (target == null) {
            System.err.println("Cannot get field " + fieldName + " from null target");
            return null;
        }
        Class<?> searchClass = target.getClass();
        while (searchClass != null && searchClass != Object.class) {
            try {
                Field f = searchClass.getDeclaredField(fieldName);
                return getFieldValue(f, target, clazz);
            } catch (NoSuchFieldException e) {
                searchClass = searchClass.getSuperclass();
            }
        }
        System.err.println("Field " + fieldName + " not found in hierarchy of " + target.getClass());
        return null;
    }

    /**
     * Reads a field value using method handles first and {@code Unsafe} as fallback.
     *
     * @param f field to read
     * @param target target object for instance fields, or {@code null} for static fields
     * @param clazz expected field type token
     * @param <T> expected result type
     * @return field value cast to {@code T}, or {@code null} if both access paths fail
     */
    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Field f, Object target, Class<T> clazz) {
        try {
            return (T) readFieldValue(f, target);
        } catch (Throwable lookupException) {
            try {
                long offset;
                if (Modifier.isStatic(f.getModifiers())) {
                    target = UnsafeAccess.staticFieldBase(f);
                    offset = UnsafeAccess.staticFieldOffset(f);
                } else offset = objectFieldOffset(f);
                return (T) UnsafeAccess.getObject(target, offset);
            } catch (Throwable unsafeException) {
                lookupException.addSuppressed(unsafeException);
                lookupException.printStackTrace();
            }
        }
        return null;
    }

    private static Object readFieldValue(Field f, Object target) throws Throwable {
        f.trySetAccessible();
        MethodHandle getter = ClassUtils.LOOKUP.unreflectGetter(f);
        if (Modifier.isStatic(f.getModifiers())) {
            return getter.invoke();
        }
        return getter.invoke(target);
    }

    /**
     * Returns the {@code Unsafe} offset for an instance field.
     *
     * @param f instance field
     * @return memory offset for the field
     * @throws IllegalStateException if the offset cannot be resolved
     */
    public static long objectFieldOffset(Field f) {
        try {
            return UnsafeAccess.objectFieldOffset(f);
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot get object field offset for " + f, e);
        }
    }

    /**
     * Reads a static field declared directly on a class.
     *
     * @param target class that declares the field
     * @param fieldName declared field name
     * @param clazz expected field type token
     * @param <T> expected result type
     * @return field value cast to {@code T}, or {@code null} when it cannot be read
     */
    public static <T> T getFieldValue(Class<?> target, String fieldName, Class<T> clazz) {
        try {
            return getFieldValue(target.getDeclaredField(fieldName), (Object) null, clazz);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Writes a field value on the target object by declared field name.
     *
     * @param target object whose declared field should be written
     * @param fieldName declared field name
     * @param value new field value
     */
    public static void setFieldValue(Object target, String fieldName, Object value) {
        try {
            setFieldValue(target.getClass().getDeclaredField(fieldName), target, value);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void setFieldValueWithLookup(Field f, Object target, Object value) throws Throwable {
        f.trySetAccessible();
        MethodHandle setter = ClassUtils.LOOKUP.unreflectSetter(f);
        if (Modifier.isStatic(f.getModifiers())) {
            setter.invoke(value);
        } else {
            setter.invoke(target, value);
        }
    }

    /**
     * Returns all methods declared by a class and its superclasses, excluding
     * {@link Object}.
     *
     * @param clazz class whose hierarchy should be scanned
     * @return declared methods from the class hierarchy
     */
    public static Method[] getAllDeclaredMethods(Class<?> clazz) {
        ArrayList<Method> methods = new ArrayList<>();
        for (Class<?> currentClass = clazz; currentClass != null && currentClass != Object.class; currentClass = currentClass.getSuperclass()) {
            methods.addAll(Arrays.asList(currentClass.getDeclaredMethods()));
        }
        return methods.toArray(new Method[0]);
    }

    /**
     * Returns all fields declared by a class and its superclasses, excluding
     * {@link Object}.
     *
     * @param clazz class whose hierarchy should be scanned
     * @return declared fields from the class hierarchy
     */
    public static Field[] getAllDeclaredFields(Class<?> clazz) {
        ArrayList<Field> fields = new ArrayList<>();
        for (Class<?> currentClass = clazz; currentClass != null && currentClass != Object.class; currentClass = currentClass.getSuperclass()) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
        }
        return fields.toArray(new Field[0]);
    }

    /**
     * Writes a field value using {@code Unsafe} first and method handles as fallback.
     *
     * @param f field to write
     * @param target target object for instance fields, or {@code null} for static fields
     * @param value new field value
     */
    public static void setFieldValue(Field f, Object target, Object value) {
        try {
            long offset;
            if (Modifier.isStatic(f.getModifiers())) {
                target = UnsafeAccess.staticFieldBase(f);
                offset = UnsafeAccess.staticFieldOffset(f);
            } else offset = objectFieldOffset(f);
            UnsafeAccess.putObject(target, offset, value);
        } catch (Throwable e) {
            try {
                setFieldValueWithLookup(f, target, value);
            } catch (Throwable lookupException) {
                e.addSuppressed(lookupException);
                e.printStackTrace();
            }
        }
    }

    /**
     * Returns the jar path that contains the supplied class.
     *
     * @param clazz class whose code source should be inspected
     * @return decoded jar path
     */
    public static String getJarPath(Class<?> clazz) {
        String file = clazz.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (!file.isEmpty()) {
            if (file.startsWith("union:"))
                file = file.substring(6);
            if (file.startsWith("/"))
                file = file.substring(1);
            file = file.substring(0, file.lastIndexOf(".jar") + 4);
            file = file.replaceAll("/", "\\\\");
        }
        return URLDecoder.decode(file, StandardCharsets.UTF_8);
    }

    /**
     * Removes the library jar from selected Forge loader structures when the same
     * code is present as both a core component and a mod.
     */
    @SuppressWarnings({"ConstantConditions", "unchecked", "rawtypes"})
    public static void coexistenceCoreAndMod() {
        List<NamedPath> found = HelperLib.getFieldValue(ModDirTransformerDiscoverer.class, "found", List.class);
        String currentJarPath = HelperLib.getJarPath(HelperLib.class);
        if (found != null) {
            found.removeIf(namedPath -> Arrays.stream(namedPath.paths()).anyMatch(path -> currentJarPath.equals(path.toString())));
        }

        ModuleLayerHandler moduleLayerHandler = HelperLib.getFieldValue(Launcher.INSTANCE, "moduleLayerHandler", ModuleLayerHandler.class);
        EnumMap completedLayers = moduleLayerHandler == null ? null : HelperLib.getFieldValue(moduleLayerHandler, "completedLayers", EnumMap.class);
        if (completedLayers == null) return;

        String moduleName = HelperLib.class.getModule().getName();
        completedLayers.values().forEach(layerInfo -> {
            ModuleLayer layer = HelperLib.getFieldValue(layerInfo, "layer", ModuleLayer.class);
            if (layer == null) return;

            layer.modules().forEach(module -> {
                if (moduleName.equals(module.getName())) {
                    Set<ResolvedModule> existingModules = HelperLib.getFieldValue(layer.configuration(), "modules", Set.class);
                    Map<String, ResolvedModule> existingNameToModule = HelperLib.getFieldValue(layer.configuration(), "nameToModule", Map.class);
                    if (existingModules == null || existingNameToModule == null) return;

                    Set<ResolvedModule> modules = new HashSet<>(existingModules);
                    Map<String, ResolvedModule> nameToModule = new HashMap<>(existingNameToModule);

                    modules.remove(nameToModule.remove(moduleName));

                    HelperLib.setFieldValue(layer.configuration(), "modules", modules);
                    HelperLib.setFieldValue(layer.configuration(), "nameToModule", nameToModule);
                }
            });
        });
    }

    /**
     * Copies non-static fields declared on a specific class from {@code source} to
     * {@code target}.
     *
     * @param clazz class declaring the fields to copy
     * @param source object to read from
     * @param target object to write to
     */
    public static void copyProperties(Class<?> clazz, Object source, Object target) {
        try {
            Field[] fields = clazz.getDeclaredFields();
            AccessibleObject.setAccessible(fields, true);

            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.set(target, field.get(source));
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reads bytecode for a loaded class from its class loader resource path.
     *
     * @param c class whose bytecode should be read
     * @return class file bytes, or {@code null} if the resource is unavailable
     * @throws IOException if the class resource cannot be read
     */
    public static byte[] getClassBytes(Class<?> c) throws IOException {
        String className = c.getName();
        String classAsPath = className.replace('.', '/') + ".class";
        InputStream stream = c.getClassLoader().getResourceAsStream(classAsPath);
        return stream == null ? null : IOUtils.toByteArray(stream);
    }

    /**
     * Reads bytecode for a class stored inside a jar file.
     *
     * @param jarPath path to the jar file
     * @param className binary class name
     * @return class file bytes
     * @throws Exception when the jar cannot be read or the class entry is missing
     */
    public static byte[] getClassBytes(String jarPath, String className) throws Exception {
        try (JarFile jarFile = new JarFile(jarPath)) {
            String classPath = className.replace('.', '/') + ".class";
            JarEntry entry = jarFile.getJarEntry(classPath);
            if (entry == null) {
                jarFile.close();
                throw new ClassNotFoundException("Class not found in JAR: " + className);
            }
            try (InputStream is = jarFile.getInputStream(entry)) {
                return readAllBytes(is);
            }
        }
    }

    /**
     * Reads bytecode for a class from the jar that contains {@link HelperLib}.
     *
     * @param className binary class name
     * @return class file bytes, or {@code null} when the class cannot be read
     */
    public static byte[] getClassBytes(String className) {
        try {
             return getClassBytes(HelperLib.getJarPath(), className);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Reads all bytes from an input stream.
     *
     * @param is stream to consume
     * @return byte array containing the complete stream contents
     * @throws IOException if the stream cannot be read
     */
    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    static {
        ModuleUtil.INSTANCE.openAllRequiredModules(HelperLib.class);
    }
}
