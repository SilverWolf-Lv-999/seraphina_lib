package seraphina.seraphina_lib.mixin.service;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.mixin.util.InsertShift;

import java.util.ArrayList;
import java.util.List;

final class MixinAnnotationUtils {
    private MixinAnnotationUtils() {
    }

    static AnnotationNode findAnnotation(List<AnnotationNode> visibleAnnotations,
                                         List<AnnotationNode> invisibleAnnotations,
                                         String desc) {
        for (AnnotationNode annotation : annotationNodes(visibleAnnotations, invisibleAnnotations)) {
            if (desc.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    static List<AnnotationNode> annotationNodes(List<AnnotationNode> visibleAnnotations,
                                                List<AnnotationNode> invisibleAnnotations) {
        boolean hasVisible = visibleAnnotations != null && !visibleAnnotations.isEmpty();
        boolean hasInvisible = invisibleAnnotations != null && !invisibleAnnotations.isEmpty();
        if (!hasVisible && !hasInvisible) {
            return List.of();
        }
        if (!hasVisible) {
            return invisibleAnnotations;
        }
        if (!hasInvisible) {
            return visibleAnnotations;
        }
        ArrayList<AnnotationNode> annotations = new ArrayList<>(visibleAnnotations);
        annotations.addAll(invisibleAnnotations);
        return annotations;
    }

    static String annotationClassInternalName(Object value) {
        if (value instanceof Type type && type.getSort() == Type.OBJECT) {
            return type.getInternalName();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return toInternalName(stringValue);
        }
        return null;
    }

    private static String toInternalName(String className) {
        String normalized = className.trim();
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replace('.', '/').replace('\\', '/');
    }

    static String annotationStringValue(AnnotationNode annotation, String key, String defaultValue) {
        Object value = annotationValue(annotation, key);
        return value instanceof String stringValue && !stringValue.isEmpty() ? stringValue : defaultValue;
    }

    static boolean annotationBooleanValue(AnnotationNode annotation, String key, boolean defaultValue) {
        Object value = annotationValue(annotation, key);
        return value instanceof Boolean booleanValue ? booleanValue : defaultValue;
    }

    static int annotationIntValue(AnnotationNode annotation, String key, int defaultValue) {
        Object value = annotationValue(annotation, key);
        return value instanceof Integer intValue ? intValue : defaultValue;
    }

    static String annotationTypeDescriptorValue(AnnotationNode annotation, String key) {
        Object value = annotationValue(annotation, key);
        if (value instanceof Type type) {
            return type.getDescriptor();
        }
        return value instanceof String stringValue ? stringValue : null;
    }

    @SuppressWarnings("unchecked")
    static List<String> annotationStringListValue(AnnotationNode annotation, String key) {
        Object value = annotationValue(annotation, key);
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof String stringValue && !stringValue.isEmpty()) {
                result.add(stringValue);
            }
        }
        return result;
    }

    static InsertPosition annotationEnumValue(AnnotationNode annotation, String key, InsertPosition defaultValue) {
        return annotationEnumValue(annotation, key, InsertPosition.class, defaultValue);
    }

    static InsertShift annotationEnumValue(AnnotationNode annotation, String key, InsertShift defaultValue) {
        return annotationEnumValue(annotation, key, InsertShift.class, defaultValue);
    }

    static <E extends Enum<E>> E annotationEnumValue(AnnotationNode annotation, String key,
                                                    Class<E> enumType, E defaultValue) {
        Object value = annotationValue(annotation, key);
        if (value instanceof String[] enumValue && enumValue.length > 1) {
            try {
                return Enum.valueOf(enumType, enumValue[1]);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return defaultValue;
    }

    static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }
}
