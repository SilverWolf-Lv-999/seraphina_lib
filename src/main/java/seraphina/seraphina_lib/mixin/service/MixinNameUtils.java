package seraphina.seraphina_lib.mixin.service;

final class MixinNameUtils {
    private MixinNameUtils() {
    }

    static String normalizeClassName(String className) {
        String normalized = className.trim();
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replace('/', '.').replace('\\', '.');
    }

    static String normalizePackageName(String packageName) {
        String normalized = packageName.trim();
        while (normalized.endsWith("/") || normalized.endsWith("\\") || normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(".*")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized.replace('/', '.').replace('\\', '.');
    }

    static String toInternalName(String className) {
        String normalized = className.trim();
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replace('.', '/').replace('\\', '/');
    }
}
