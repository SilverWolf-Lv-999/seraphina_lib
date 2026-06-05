package seraphina.seraphina_lib.util.clazz;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Utilities for privileged lookup access and hidden class definition.
 * <p>
 * This class relies on internal JVM entry points and should be used only during
 * controlled launch-time or transformer workflows.
 */
public class ClassUtils {
	static ClassUtils Instance = null;
	static final Logger LOGGER = LoggerFactory.getLogger(ClassUtils.class);
	/**
	 * Cache of hidden classes keyed by their original or generated names.
	 */
	public Map<String, Class<?>> hiddenClassMap = new HashMap<>();
	public static MethodHandle methodhandle;
	/**
	 * Trusted lookup used for private and internal member access.
	 */
	public static final MethodHandles.Lookup LOOKUP = getInstance().getLookup();

	/**
	 * Returns the shared {@link ClassUtils} instance.
	 *
	 * @return singleton utility instance
	 */
	public static ClassUtils getInstance() {
		if (Instance == null) {
			Instance = new ClassUtils();
		}
		return Instance;
	}

	/**
	 * Resolves a trusted {@link MethodHandles.Lookup} instance.
	 *
	 * @return lookup capable of accessing private members, or {@code null} if it cannot be created
	 */
	public  MethodHandles.Lookup getLookup() {
		try {
			Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
			Object base = UnsafeAccess.staticFieldBase(implLookupField);
			long offset = UnsafeAccess.staticFieldOffset(implLookupField);
			return (MethodHandles.Lookup) UnsafeAccess.getObjectVolatile(base, offset);
		} catch (Throwable e) {
			try {
				Constructor<MethodHandles.Lookup> c = MethodHandles.Lookup.class.getDeclaredConstructor();
				c.setAccessible(true);
				return c.newInstance();
			} catch (Throwable var3) {
				var3.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * Defines a hidden copy of a class from this library package.
	 *
	 * @param klassName binary name of the class to load from resources
	 * @param loader class loader to define the hidden class into
	 * @return defined hidden class
	 */
	public Class<?> defineHiddenPackageClass(String klassName, ClassLoader loader) {
		return this.defineHiddenPackageClass(klassName, loader, ClassUtils.class,  true, ClassOption.STRONG);
	}

	/**
	 * Defines a hidden copy of a class using the system class loader.
	 *
	 * @param name binary name of the class to load from resources
	 * @return defined hidden class
	 */
    public Class<?> defineHiddenPackageClass(String name) {
        return this.defineHiddenPackageClass(name, ClassLoader.getSystemClassLoader());
    }

	static {
		try {
			if (LOOKUP != null) {
				methodhandle = LOOKUP.findStatic(ClassLoader.class, "defineClass0", MethodType.methodType(Class.class, ClassLoader.class, Class.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class, boolean.class, int.class, Object.class));
			}
		} catch (Throwable e) {
			throw new Error("Could not init ClassUtil", e);
		}
	}

//	public  Class<?> defineClass(String name, ClassLoader loader, byte[] b, int off, int len, ProtectionDomain pd) {
//		try {
//			return (Class<?>) defineClass.invoke(loader, name, b, off, len, pd);
//		} catch (Throwable e) {
//			try {
//				Class.forName(name);
//				return Class.forName(name);
//			} catch (ClassNotFoundException ignored) {
//			}
//			throw new Error(e);
//		}
//	}
//
//	public Class<?> defineClass(ClassLoader loader, String name, byte[] buf) {
//		try {
//			return (Class<?>) defineClass.invoke(loader, name, buf, 0, buf.length, null, null);
//		} catch (Throwable e1) {
//			try {
//				return Class.forName(name);
//			} catch (Exception e) {
//				e1.addSuppressed(e);
//				throw new RuntimeException(e1);
//			}
//		}
//	}
//
//	public Class<?> definePackageClass(String name, Class<?> lookup, ClassLoader loader) {
//		try {
//			InputStream is = lookup.getResourceAsStream("/" + name.replace('.', '/') + ".class");
//			byte[] dat = new byte[is.available()];
//			is.read(dat);
//			is.close();
//			Objects.requireNonNull(dat);
//			if (loader == null)
//				loader = lookup.getClassLoader();
//			return (Class<?>) defineClass.invoke(loader, name, dat, 0, dat.length, null);
//		} catch (Throwable e) {
//			try {
//				if (Class.forName(name) != null) {
//					return Class.forName(name);
//				}
//			} catch (ClassNotFoundException ignored) {}
//			throw new Error(e);
//		}
//	}

	/**
	 * Defines a hidden class from raw class bytes.
	 *
	 * @param name cache key and binary class name
	 * @param loader target class loader
	 * @param lookup lookup class used as the hidden-class host
	 * @param b class file bytes
	 * @param off byte array offset
	 * @param len byte length
	 * @param initialize whether to initialize the class immediately
	 * @param options hidden-class options
	 * @return defined hidden class
	 */
	public Class<?> defineHiddenClass(String name, ClassLoader loader, Class<?> lookup, byte[] b, int off, int len, boolean initialize, ClassOption... options) {
		try {
			if (hiddenClassMap.containsKey(name) && hiddenClassMap.get(name) != null) {
				return hiddenClassMap.get(name);
			} else {
				Objects.requireNonNull(options);
				int flags = 2 | ClassOption.optionsToFlag(Set.of(options));
				if (loader == null || loader == ClassLoader.getPlatformClassLoader()) {
					flags |= 8;
				}
				return (Class<?>) methodhandle.invoke(loader, lookup, name, b, off, len, null, initialize, flags, null);
			}
		} catch (Throwable e) {
			throw new Error(e);
		}
	}

	/**
	 * Loads a class resource, remaps its internal name, and defines it as a hidden class.
	 *
	 * @param originalName binary name of the class resource to load
	 * @param loader target class loader
	 * @param lookup lookup class used as the resource owner and hidden-class host
	 * @param initialize whether to initialize the class immediately
	 * @param options hidden-class options
	 * @return defined hidden class
	 */
	public Class<?> defineHiddenPackageClass(String originalName, ClassLoader loader, Class<?> lookup, boolean initialize, ClassOption... options) {
		try {
			final String hiddenClassName = "Nega";

			if (hiddenClassMap.containsKey(originalName) && hiddenClassMap.get(originalName) != null) {
				return hiddenClassMap.get(originalName);
			}

			InputStream is = lookup.getResourceAsStream("/" + originalName.replace('.', '/') + ".class");
			if (is == null) {
				throw new RuntimeException("Cannot find class resource: " + originalName);
			}
			byte[] dat = new byte[is.available()];
			is.read(dat);
			is.close();
			Objects.requireNonNull(dat, "Class data is null");
			byte[] modifiedBytes = remapClassName(dat, originalName);

			Objects.requireNonNull(options);
			int flags = 2 | ClassOption.optionsToFlag(Set.of(options));
			if (loader == null || loader == ClassLoader.getPlatformClassLoader()) {
				flags |= 8;
			}

			Class<?> klass = (Class<?>) methodhandle.invoke(
					loader,
					lookup,
					hiddenClassName,
					modifiedBytes,
					0,
					modifiedBytes.length,
					null,
					initialize,
					flags,
					null
			);

			hiddenClassMap.put(originalName, klass);
			hiddenClassMap.put(hiddenClassName, klass);
			return klass;

		} catch (Throwable e) {
			throw new Error("Failed to define hidden class: " + originalName, e);
		}
	}

	/**
	 * Rewrites a class file's internal name to the hidden-class placeholder name.
	 *
	 * @param classBytes source class file bytes
	 * @param oldClassName original binary class name
	 * @return remapped class file bytes
	 */
	public byte[] remapClassName(byte[] classBytes, String oldClassName) {
		String oldInternal = oldClassName.replace('.', '/');
		ClassReader reader = new ClassReader(classBytes);
		ClassWriter writer = new ClassWriter(ClassReader.EXPAND_FRAMES);
		SimpleRemapper remapper = new SimpleRemapper(oldInternal, "Nega");
		ClassRemapper classRemapper = new ClassRemapper(writer, remapper);
		acceptExpanded(reader, classRemapper);
		return writer.toByteArray();
	}

	private static void acceptExpanded(ClassReader reader, ClassRemapper classRemapper) {
		try {
			reader.accept(classRemapper, ClassReader.EXPAND_FRAMES);
		} catch (RuntimeException exception) {
			throw exception;
		}
	}

	/**
	 * Defines a hidden package class using the lookup class loader.
	 *
	 * @param name binary name of the class resource to load
	 * @param lookup lookup class used as the hidden-class host
	 * @param initialize whether to initialize the class immediately
	 * @param options hidden-class options
	 * @return defined hidden class
	 */
	public Class<?> defineHiddenPackageClass(String name, Class<?> lookup, boolean initialize, ClassOption... options) {
		return defineHiddenPackageClass(name, lookup.getClassLoader(), lookup, initialize, options);
	}

	/**
	 * Defines a strong hidden package class using the lookup class loader.
	 *
	 * @param name binary name of the class resource to load
	 * @param lookup lookup class used as the hidden-class host
	 * @return defined hidden class
	 */
	public Class<?> defineHiddenPackageClass(String name, Class<?> lookup) {
		return defineHiddenPackageClass(name, lookup.getClassLoader(), lookup, true, ClassOption.STRONG);
	}

	/**
	 * Finds a class through the trusted lookup.
	 *
	 * @param name binary class name
	 * @return found class, or {@code null} when it is not visible
	 */
	public Class<?> findClass(String name) {
		try {
			return LOOKUP.findClass(name);
		} catch (ClassNotFoundException e) {
			return null;
		} catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

	/**
	 * Disables self-attach support through system properties and known HotSpot internals.
	 */
	public void prohibitedAttachSelf() {
		System.setProperty("jdk.attach.allowAttachSelf", "false");
		try {
			Class<?> vmClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
			Field allowAttachSelfField = vmClass.getDeclaredField("ALLOW_ATTACH_SELF");
			Object base = UnsafeAccess.staticFieldBase(allowAttachSelfField);
			long offset = UnsafeAccess.staticFieldOffset(allowAttachSelfField);
			UnsafeAccess.putBoolean(base, offset, false);
		} catch (Throwable e) {
			LOGGER.warn("Failed to allow attach self using reflection");
		}
	}
}
