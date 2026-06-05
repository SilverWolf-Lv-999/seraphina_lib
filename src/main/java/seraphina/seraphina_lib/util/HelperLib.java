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

public final class HelperLib {
    static final Logger LOGGER = LoggerFactory.getLogger(HelperLib.class);

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

    public static long objectFieldOffset(Field f) {
        try {
            return UnsafeAccess.objectFieldOffset(f);
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot get object field offset for " + f, e);
        }
    }

    public static <T> T getFieldValue(Class<?> target, String fieldName, Class<T> clazz) {
        try {
            return getFieldValue(target.getDeclaredField(fieldName), (Object) null, clazz);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

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

    public static Method[] getAllDeclaredMethods(Class<?> clazz) {
        ArrayList<Method> methods = new ArrayList<>();
        for (Class<?> currentClass = clazz; currentClass != null && currentClass != Object.class; currentClass = currentClass.getSuperclass()) {
            methods.addAll(Arrays.asList(currentClass.getDeclaredMethods()));
        }
        return methods.toArray(new Method[0]);
    }

    public static Field[] getAllDeclaredFields(Class<?> clazz) {
        ArrayList<Field> fields = new ArrayList<>();
        for (Class<?> currentClass = clazz; currentClass != null && currentClass != Object.class; currentClass = currentClass.getSuperclass()) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
        }
        return fields.toArray(new Field[0]);
    }

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

    public static byte[] getClassBytes(Class<?> c) throws IOException {
        String className = c.getName();
        String classAsPath = className.replace('.', '/') + ".class";
        InputStream stream = c.getClassLoader().getResourceAsStream(classAsPath);
        return stream == null ? null : IOUtils.toByteArray(stream);
    }

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

    public static byte[] getClassBytes(String className) {
        try {
             return getClassBytes(HelperLib.getJarPath(), className);
        } catch (Exception exception) {
            return null;
        }
    }

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
