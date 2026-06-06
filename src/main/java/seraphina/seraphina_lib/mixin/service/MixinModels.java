package seraphina.seraphina_lib.mixin.service;

import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.mixin.util.InsertShift;
import seraphina.seraphina_lib.service.ISeraMixin;

import java.util.List;

final class PendingMixin {
    private final Class<?> mixinClass;
    private final String mixinClassName;
    private final String targetClassName;
    private final ClassLoader classLoader;

    private PendingMixin(Class<?> mixinClass, String mixinClassName, String targetClassName, ClassLoader classLoader) {
        this.mixinClass = mixinClass;
        this.mixinClassName = mixinClassName;
        this.targetClassName = targetClassName;
        this.classLoader = classLoader;
    }

    static PendingMixin ofClass(Class<?> mixinClass) {
        return new PendingMixin(mixinClass, null, null, mixinClass.getClassLoader());
    }

    static PendingMixin ofMixinName(String mixinClassName, ClassLoader classLoader) {
        return new PendingMixin(null, mixinClassName, null, classLoader);
    }

    static PendingMixin ofNames(String mixinClassName, String targetClassName, ClassLoader classLoader) {
        return new PendingMixin(null, mixinClassName, targetClassName, classLoader);
    }

    void apply(SeraMixinLaunchPluginService service) {
        if (this.mixinClass != null) {
            service.register(this.mixinClass);
        } else if (this.targetClassName == null) {
            service.register(this.mixinClassName, this.classLoader);
        } else {
            service.register(this.mixinClassName, this.targetClassName, this.classLoader);
        }
    }
}

final class ClassInfo {
    final String mixinClassName;
    final String targetInternalName;
    final ClassLoader mixinClassLoader;
    final ISeraMixin hook;
    final int priority;
    final MixinMappingResolver mappingResolver;

    ClassInfo(String mixinClassName, String targetInternalName, ClassLoader mixinClassLoader,
              ISeraMixin hook, int priority, MixinMappingResolver mappingResolver) {
        this.mixinClassName = mixinClassName;
        this.targetInternalName = targetInternalName;
        this.mixinClassLoader = mixinClassLoader;
        this.hook = hook;
        this.priority = priority;
        this.mappingResolver = mappingResolver == null ? MixinMappingResolver.EMPTY : mappingResolver;
    }

    String key() {
        return this.targetInternalName + '\n' + this.mixinClassName;
    }

    int priority() {
        return this.priority;
    }

    String mixinClassName() {
        return this.mixinClassName;
    }
}

final class ClassHierarchyInfo {
    final String internalName;
    final String superName;
    final boolean isInterface;

    ClassHierarchyInfo(String internalName, String superName, boolean isInterface) {
        this.internalName = internalName;
        this.superName = superName;
        this.isInterface = isInterface;
    }
}

final class OverwritePoint {
    final String targetMethodName;
    final String targetDesc;
    final String mixinClassName;
    final String mixinMethodName;
    final String mixinMethodDesc;

    OverwritePoint(String targetMethodName, String targetDesc,
                   String mixinClassName, String mixinMethodName, String mixinMethodDesc) {
        this.targetMethodName = targetMethodName;
        this.targetDesc = targetDesc;
        this.mixinClassName = mixinClassName;
        this.mixinMethodName = mixinMethodName;
        this.mixinMethodDesc = mixinMethodDesc;
    }

    boolean matches(String name, String desc) {
        return this.targetMethodName.equals(name) && this.targetDesc.equals(desc);
    }
}

final class InjectPoint {
    final String targetMethodName;
    final String targetDesc;
    final InsertPosition position;
    final InjectionSelector selector;
    final String mixinClassName;
    final String mixinMethodName;
    final String mixinMethodDesc;
    final boolean mixinMethodStatic;
    final InjectMode mode;

    InjectPoint(String targetMethodName, String targetDesc, InsertPosition position, InjectionSelector selector,
                String mixinClassName, String mixinMethodName, String mixinMethodDesc,
                boolean mixinMethodStatic, InjectMode mode) {
        this.targetMethodName = targetMethodName;
        this.targetDesc = targetDesc;
        this.position = position;
        this.selector = selector == null ? InjectionSelector.DEFAULT : selector;
        this.mixinClassName = mixinClassName;
        this.mixinMethodName = mixinMethodName;
        this.mixinMethodDesc = mixinMethodDesc;
        this.mixinMethodStatic = mixinMethodStatic;
        this.mode = mode;
    }

    boolean matches(String name, String desc) {
        return this.targetMethodName.equals(name) && this.targetDesc.equals(desc);
    }
}

final class InjectionSelector {
    static final InjectionSelector DEFAULT = new InjectionSelector("", "", "", -1, -1, -1, InsertShift.DEFAULT, 0);

    final String owner;
    final String name;
    final String desc;
    final int ordinal;
    final int opcode;
    final int index;
    final InsertShift shift;
    final int by;

    InjectionSelector(String owner, String name, String desc, int ordinal, int opcode, int index,
                      InsertShift shift, int by) {
        this.owner = owner == null ? "" : owner;
        this.name = name == null ? "" : name;
        this.desc = desc == null ? "" : desc;
        this.ordinal = ordinal;
        this.opcode = opcode;
        this.index = index;
        this.shift = shift == null ? InsertShift.DEFAULT : shift;
        this.by = by;
    }
}

final class InjectHandlerCall {
    final int opcode;
    final String owner;
    final String name;
    final String desc;
    final boolean isInterface;
    final boolean needsReceiver;

    InjectHandlerCall(int opcode, String owner, String name, String desc, boolean isInterface,
                      boolean needsReceiver) {
        this.opcode = opcode;
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.isInterface = isInterface;
        this.needsReceiver = needsReceiver;
    }
}

enum InjectMode {
    MIXIN
}

final class ShadowFieldInfo {
    final String mixinFieldName;
    final String targetFieldName;
    final String desc;

    ShadowFieldInfo(String mixinFieldName, String targetFieldName, String desc) {
        this.mixinFieldName = mixinFieldName;
        this.targetFieldName = targetFieldName;
        this.desc = desc;
    }
}

final class ShadowMethodInfo {
    final String mixinMethodName;
    final String targetMethodName;
    final String desc;

    ShadowMethodInfo(String mixinMethodName, String targetMethodName, String desc) {
        this.mixinMethodName = mixinMethodName;
        this.targetMethodName = targetMethodName;
        this.desc = desc;
    }
}

final class RedirectPoint {
    final String targetMethodName;
    final String targetMethodDesc;
    final String mixinClassName;
    final String mixinMethodName;
    final String mixinMethodDesc;
    final List<TargetCall> targetCalls;

    RedirectPoint(String targetMethodName, String targetMethodDesc,
                  String mixinClassName, String mixinMethodName, String mixinMethodDesc,
                  List<TargetCall> targetCalls) {
        this.targetMethodName = targetMethodName;
        this.targetMethodDesc = targetMethodDesc;
        this.mixinClassName = mixinClassName;
        this.mixinMethodName = mixinMethodName;
        this.mixinMethodDesc = mixinMethodDesc;
        this.targetCalls = targetCalls;
    }

    boolean matches(String name, String desc) {
        return this.targetMethodName.equals(name) && this.targetMethodDesc.equals(desc);
    }
}

final class TargetCall {
    final String owner;
    final String name;
    final String desc;

    TargetCall(String owner, String name, String desc) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
    }

    boolean matches(String owner, String name, String desc) {
        return this.owner.equals(owner) && this.name.equals(name) && this.desc.equals(desc);
    }
}

final class ReturnFieldPoint {
    static final String INCOMPATIBLE_CAST = "<INCOMPATIBLE_RETURN_FIELD>";

    final String ownerInternalName;
    final String fieldName;
    final String fieldDesc;
    final boolean isStatic;
    final String handlerOwnerInternalName;
    final String handlerMethodName;
    final String handlerMethodDesc;
    final String returnCastType;
    final boolean read;
    final boolean write;

    ReturnFieldPoint(String ownerInternalName, String fieldName, String fieldDesc, boolean isStatic,
                     String handlerOwnerInternalName, String handlerMethodName, String handlerMethodDesc,
                     String returnCastType, boolean read, boolean write) {
        this.ownerInternalName = ownerInternalName;
        this.fieldName = fieldName;
        this.fieldDesc = fieldDesc;
        this.isStatic = isStatic;
        this.handlerOwnerInternalName = handlerOwnerInternalName;
        this.handlerMethodName = handlerMethodName;
        this.handlerMethodDesc = handlerMethodDesc;
        this.returnCastType = returnCastType;
        this.read = read;
        this.write = write;
    }
}
