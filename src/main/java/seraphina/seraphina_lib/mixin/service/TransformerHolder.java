package seraphina.seraphina_lib.mixin.service;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.mixin.util.InsertShift;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Stores the pre-scanned mixin metadata used while transforming a single target
 * class. The holder keeps ASM nodes instead of loading mixin classes, because
 * loading them too early can trigger side effects during class transformation.
 */
final class TransformerHolder {
    final MixinClassProvider classProvider;
    final MixinHierarchyResolver hierarchyResolver;
    final String mixinClassName;
    final String targetInternalName;
    final List<InjectPoint> injectPoints = new ArrayList<>();
    final List<OverwritePoint> overwritePoints = new ArrayList<>();
    final List<RedirectPoint> redirectPoints = new ArrayList<>();
    final List<ReturnFieldPoint> returnFieldPoints = new ArrayList<>();
    final Map<String, ShadowFieldInfo> shadowFields = new HashMap<>();
    final Map<String, ShadowMethodInfo> shadowMethods = new HashMap<>();
    final Map<String, MethodNode> rewrittenMethodCache = new HashMap<>();
    final ClassLoader loader;
    final ClassLoader mixinClassLoader;
    final MixinMappingResolver mappingResolver;
    final ThreadLocal<Boolean> hasPrintedShadowHeader;
    final ThreadLocal<String> currentTargetClass;
    final ClassNode mixinClassNode;

    TransformerHolder(Services services, ClassInfo info, ClassLoader loader) throws IOException {
        this.classProvider = services.classProvider;
        this.hierarchyResolver = services.hierarchyResolver;
        this.mixinClassName = info.mixinClassName;
        this.targetInternalName = info.targetInternalName;
        this.loader = loader != null ? loader : services.classProvider.getRuntimeClassLoader();
        this.mixinClassLoader = info.mixinClassLoader != null ? info.mixinClassLoader : this.loader;
        this.mappingResolver = info.mappingResolver == null ? MixinMappingResolver.EMPTY : info.mappingResolver;
        this.hasPrintedShadowHeader = services.hasPrintedShadowHeader;
        this.currentTargetClass = services.currentTargetClass;
        this.mixinClassNode = this.scanWithASM();
    }

    private ClassNode scanWithASM() throws IOException {
        byte[] mixinBytes = this.classProvider.loadMixinBytes(this.mixinClassName, this.mixinClassLoader);
        ClassNode classNode = new ClassNode();
        acceptExpandedClass(mixinBytes, classNode);
        String mixinInternal = this.mixinClassName.replace('.', '/');
        boolean remap = this.mappingResolver.isEnabled() && !this.hasNoRemapping(classNode);

        this.scanShadowFields(classNode, remap);
        for (MethodNode method : classNode.methods) {
            this.scanMethod(method, mixinInternal, remap);
        }
        return classNode;
    }

    private boolean hasNoRemapping(ClassNode classNode) {
        return MixinAnnotationUtils.findAnnotation(
                classNode.visibleAnnotations,
                classNode.invisibleAnnotations,
                MixinConstants.NO_REMAPPING_CLASS) != null;
    }

    private void scanShadowFields(ClassNode classNode, boolean remap) {
        for (FieldNode field : classNode.fields) {
            AnnotationNode annotation = MixinAnnotationUtils.findAnnotation(
                    field.visibleAnnotations,
                    field.invisibleAnnotations,
                    MixinConstants.SHADOW_CLASS);
            if (annotation == null) {
                continue;
            }
            String targetFieldName = MixinAnnotationUtils.annotationStringValue(annotation, "value", field.name);
            if (remap) {
                targetFieldName = this.mappingResolver.mapFieldName(this.targetInternalName, targetFieldName);
            }
            String targetFieldDesc = remap ? this.mappingResolver.mapDescriptor(field.desc) : field.desc;
            putValue(this.shadowFields, field.name, new ShadowFieldInfo(field.name, targetFieldName, targetFieldDesc));
            this.ensureShadowHeader();
        }
    }

    /**
     * Reads all supported mixin annotations on one method, then stores a cached
     * method clone with shadow references already pointed at the target class.
     */
    private void scanMethod(MethodNode method, String mixinInternal, boolean remap) {
        MethodScan scan = new MethodScan();
        for (AnnotationNode annotation : MixinAnnotationUtils.annotationNodes(method.visibleAnnotations, method.invisibleAnnotations)) {
            this.scanMethodAnnotation(method, annotation, mixinInternal, remap, scan);
        }
        for (AnnotationNode annotation : scan.injectAnnotations) {
            scan.injects.addAll(this.readInjectPoints(annotation, InsertPosition.CUSTOM, scan.customInjectPoint, remap));
        }

        this.registerInjectPoints(method, scan.injects);
        this.registerOverwritePoints(method, scan.overwrites);
        this.cacheRewrittenMethod(method, mixinInternal);
    }

    /**
     * Routes each annotation kind to the parser that understands its remapping
     * and target descriptor rules.
     */
    private void scanMethodAnnotation(MethodNode method, AnnotationNode annotation, String mixinInternal,
                                      boolean remap, MethodScan scan) {
        String desc = annotation.desc;
        if (MixinConstants.SHADOW_CLASS.equals(desc)) {
            this.registerShadowMethod(method, annotation, remap);
            return;
        }
        if (MixinConstants.RETURN_FIELD_CLASS.equals(desc)) {
            this.addReturnFieldPoints(method, annotation, mixinInternal, remap);
            return;
        }
        if (MixinConstants.INJECT_CLASS.equals(desc)) {
            scan.injectAnnotations.add(annotation);
            return;
        }
        if (MixinConstants.INJECT_POINT_CLASS.equals(desc)) {
            scan.customInjectPoint = this.readCustomInjectPoint(annotation, remap);
            return;
        }
        if (MixinConstants.ASM_CLASS.equals(desc)) {
            this.reportUnsupportedASMHandler(method);
            return;
        }
        if (MixinConstants.REDIRECT_CLASS.equals(desc)) {
            this.redirectPoints.addAll(this.readRedirectPoints(method, annotation, remap));
            return;
        }
        if (MixinConstants.OVERWRITE_CLASS.equals(desc)) {
            scan.overwrites.addAll(this.readOverwritePoints(annotation, remap));
        }
    }

    private void registerShadowMethod(MethodNode method, AnnotationNode annotation, boolean remap) {
        ShadowMethodInfo shadowMethodInfo = this.getShadowMethodInfo(method, annotation, remap);
        putValue(this.shadowMethods, method.name + method.desc, shadowMethodInfo);
        this.ensureShadowHeader();
    }

    private void registerInjectPoints(MethodNode method, List<InjectInfo> injects) {
        for (InjectInfo injectInfo : injects) {
            this.injectPoints.add(new InjectPoint(injectInfo.methodName, injectInfo.desc, injectInfo.at, injectInfo.selector,
                    this.mixinClassName, method.name, method.desc, (method.access & Opcodes.ACC_STATIC) != 0,
                    InjectMode.MIXIN));
        }
    }

    private void registerOverwritePoints(MethodNode method, List<OverwriteInfo> overwrites) {
        for (OverwriteInfo overwriteInfo : overwrites) {
            this.overwritePoints.add(new OverwritePoint(overwriteInfo.methodName, overwriteInfo.desc,
                    this.mixinClassName, method.name, method.desc));
        }
    }

    private void cacheRewrittenMethod(MethodNode method, String mixinInternal) {
        MethodNode clonedMethod = this.cloneMethod(method);
        this.rewriteShadowReferences(clonedMethod, mixinInternal);
        putValue(this.rewrittenMethodCache, method.name + method.desc, clonedMethod);
    }

    private List<InjectInfo> readInjectPoints(AnnotationNode annotation, InsertPosition defaultPosition,
                                              CustomInjectInfo customInjectPoint, boolean remap) {
        List<String> methodNames = MixinAnnotationUtils.annotationStringListValue(annotation, "methodName");
        String methodDesc = MixinAnnotationUtils.annotationStringValue(annotation, "desc", "");
        InsertPosition fallbackPosition = customInjectPoint == null ? defaultPosition : customInjectPoint.at;
        InsertPosition position = MixinAnnotationUtils.annotationEnumValue(annotation, "at", fallbackPosition);
        InjectionSelector selector = this.readInjectionSelector(annotation, position,
                customInjectPoint == null ? InjectionSelector.DEFAULT : customInjectPoint.selector,
                remap,
                -1);
        ArrayList<InjectInfo> points = new ArrayList<>();
        for (String methodName : methodNames) {
            MappedMethod mapped = remap
                    ? this.mappingResolver.mapMethod(this.targetInternalName, methodName, methodDesc)
                    : new MappedMethod(methodName, methodDesc);
            points.add(new InjectInfo(mapped.name(), mapped.desc(), position, selector));
        }
        return points;
    }

    private CustomInjectInfo readCustomInjectPoint(AnnotationNode annotation, boolean remap) {
        InsertPosition position = MixinAnnotationUtils.annotationEnumValue(annotation, "at", InsertPosition.CUSTOM);
        InjectionSelector selector = this.readInjectionSelector(annotation, position, InjectionSelector.DEFAULT, remap, 1);
        return new CustomInjectInfo(position, selector);
    }

    private InjectionSelector readInjectionSelector(AnnotationNode annotation, InsertPosition position,
                                                    InjectionSelector fallback, boolean remap, int defaultIndex) {
        ParsedInjectionTarget parsed = this.parseInjectionTarget(
                MixinAnnotationUtils.annotationStringValue(annotation, "target", ""),
                position);
        String owner = firstNonBlankRaw(
                MixinAnnotationUtils.annotationStringValue(annotation, "owner", ""),
                parsed.owner,
                fallback.owner);
        if (!owner.isEmpty()) {
            owner = toInternalClassName(owner);
        }
        String name = firstNonBlankRaw(
                MixinAnnotationUtils.annotationStringValue(annotation, "name", ""),
                parsed.name,
                fallback.name);
        String desc = normalizeDescriptor(firstNonBlankRaw(
                MixinAnnotationUtils.annotationStringValue(annotation, "targetDesc", ""),
                parsed.desc,
                fallback.desc));
        int ordinal = MixinAnnotationUtils.annotationIntValue(annotation, "ordinal", fallback.ordinal);
        int opcode = MixinAnnotationUtils.annotationIntValue(annotation, "opcode", fallback.opcode);
        int index = MixinAnnotationUtils.annotationIntValue(annotation, "index", fallback.index >= 0 ? fallback.index : defaultIndex);
        InsertShift shift = MixinAnnotationUtils.annotationEnumValue(annotation, "shift", fallback.shift);
        int by = MixinAnnotationUtils.annotationIntValue(annotation, "by", fallback.by);
        InjectionSelector selector = new InjectionSelector(owner, name, desc, ordinal, opcode, index, shift, by);
        return remap ? this.remapInjectionSelector(position, selector) : selector;
    }

    private InjectionSelector remapInjectionSelector(InsertPosition position, InjectionSelector selector) {
        String owner = selector.owner;
        String name = selector.name;
        String desc = selector.desc;
        switch (position) {
            case INVOKE -> {
                if (!owner.isEmpty()) {
                    MappedMethod mapped = this.mappingResolver.mapMethod(owner, name, desc);
                    owner = this.mappingResolver.mapClassName(owner);
                    name = mapped.name();
                    desc = mapped.desc();
                } else if (!desc.isEmpty()) {
                    desc = this.mappingResolver.mapDescriptor(desc);
                }
            }
            case FIELD -> {
                if (!owner.isEmpty()) {
                    if (!name.isEmpty()) {
                        name = this.mappingResolver.mapFieldName(owner, name);
                    }
                    owner = this.mappingResolver.mapClassName(owner);
                }
                if (!desc.isEmpty()) {
                    desc = this.mappingResolver.mapDescriptor(desc);
                }
            }
            case NEW -> {
                if (!owner.isEmpty()) {
                    owner = this.mappingResolver.mapClassName(owner);
                } else if (!name.isEmpty()) {
                    name = this.mappingResolver.mapClassName(name);
                }
            }
            default -> {
                if (!desc.isEmpty()) {
                    desc = this.mappingResolver.mapDescriptor(desc);
                }
            }
        }
        return new InjectionSelector(owner, name, desc, selector.ordinal, selector.opcode, selector.index,
                selector.shift, selector.by);
    }

    private ParsedInjectionTarget parseInjectionTarget(String target, InsertPosition position) {
        if (target == null || target.isBlank()) {
            return ParsedInjectionTarget.EMPTY;
        }
        String value = target.trim();
        if (position == InsertPosition.NEW) {
            return new ParsedInjectionTarget(toInternalClassName(value), "", "");
        }
        if (value.startsWith("L")) {
            int ownerEnd = value.indexOf(';');
            if (ownerEnd > 1) {
                String owner = value.substring(1, ownerEnd);
                String member = value.substring(ownerEnd + 1);
                return this.parseMemberTarget(owner, member);
            }
        }
        int descStart = value.indexOf('(');
        if (descStart >= 0) {
            int nameStart = lastMemberSeparator(value, descStart);
            if (nameStart >= 0) {
                String owner = value.substring(0, nameStart);
                String name = value.substring(nameStart + 1, descStart);
                return new ParsedInjectionTarget(toInternalClassName(owner), name, value.substring(descStart));
            }
            return new ParsedInjectionTarget("", value.substring(0, descStart), value.substring(descStart));
        }
        int fieldDescStart = value.indexOf(':');
        if (fieldDescStart >= 0) {
            int nameStart = lastMemberSeparator(value, fieldDescStart);
            if (nameStart >= 0) {
                String owner = value.substring(0, nameStart);
                String name = value.substring(nameStart + 1, fieldDescStart);
                return new ParsedInjectionTarget(toInternalClassName(owner), name, value.substring(fieldDescStart + 1));
            }
            return new ParsedInjectionTarget("", value.substring(0, fieldDescStart), value.substring(fieldDescStart + 1));
        }
        if (position == InsertPosition.FIELD || position == InsertPosition.INVOKE) {
            return new ParsedInjectionTarget("", value, "");
        }
        return new ParsedInjectionTarget(toInternalClassName(value), "", "");
    }

    private ParsedInjectionTarget parseMemberTarget(String owner, String member) {
        if (member == null || member.isEmpty()) {
            return new ParsedInjectionTarget(toInternalClassName(owner), "", "");
        }
        int descStart = member.indexOf('(');
        if (descStart >= 0) {
            return new ParsedInjectionTarget(toInternalClassName(owner), member.substring(0, descStart), member.substring(descStart));
        }
        int fieldDescStart = member.indexOf(':');
        if (fieldDescStart >= 0) {
            return new ParsedInjectionTarget(toInternalClassName(owner), member.substring(0, fieldDescStart),
                    member.substring(fieldDescStart + 1));
        }
        return new ParsedInjectionTarget(toInternalClassName(owner), member, "");
    }

    private static int lastMemberSeparator(String value, int before) {
        int slash = value.lastIndexOf('/', before - 1);
        int dot = value.lastIndexOf('.', before - 1);
        return Math.max(slash, dot);
    }

    private static String firstNonBlankRaw(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return third == null ? "" : third.trim();
    }

    private static String normalizeDescriptor(String desc) {
        return desc == null ? "" : desc.trim().replace('\\', '/');
    }

    private static String normalizeSelectorValue(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            return normalized.substring(1, normalized.length() - 1).replace('.', '/').replace('\\', '/');
        }
        return normalized.replace('\\', '/');
    }

    private static String toInternalClassName(String className) {
        String normalized = normalizeSelectorValue(className);
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized.replace('.', '/');
    }

    private List<OverwriteInfo> readOverwritePoints(AnnotationNode annotation, boolean remap) {
        List<String> methodNames = MixinAnnotationUtils.annotationStringListValue(annotation, "methodName");
        String methodDesc = MixinAnnotationUtils.annotationStringValue(annotation, "desc", "");
        ArrayList<OverwriteInfo> points = new ArrayList<>();
        for (String methodName : methodNames) {
            MappedMethod mapped = remap
                    ? this.mappingResolver.mapMethod(this.targetInternalName, methodName, methodDesc)
                    : new MappedMethod(methodName, methodDesc);
            points.add(new OverwriteInfo(mapped.name(), mapped.desc()));
        }
        return points;
    }

    private void reportUnsupportedASMHandler(MethodNode method) {
        System.err.println("[SeraMixin] @ASM handler is ignored because invoking it would load the mixin class: "
                + this.mixinClassName + "." + method.name + method.desc);
    }

    private List<RedirectPoint> readRedirectPoints(MethodNode method, AnnotationNode annotation, boolean remap) {
        List<String> methodNames = MixinAnnotationUtils.annotationStringListValue(annotation, "methodName");
        String methodDesc = MixinAnnotationUtils.annotationStringValue(annotation, "methodDesc", "");
        List<String> targetMethods = MixinAnnotationUtils.annotationStringListValue(annotation, "targetMethod");
        String targetMethodDesc = MixinAnnotationUtils.annotationStringValue(annotation, "targetMethodDesc", "");
        List<TargetCall> targetCalls = this.getTargetCalls(targetMethods, targetMethodDesc, remap);
        ArrayList<RedirectPoint> points = new ArrayList<>();
        for (String methodName : methodNames) {
            MappedMethod mapped = remap
                    ? this.mappingResolver.mapMethod(this.targetInternalName, methodName, methodDesc)
                    : new MappedMethod(methodName, methodDesc);
            points.add(new RedirectPoint(mapped.name(), mapped.desc(), this.mixinClassName, method.name, method.desc, targetCalls));
        }
        return points;
    }

    private List<TargetCall> getTargetCalls(List<String> targetMethods, String targetMethodDesc, boolean remap) {
        ArrayList<TargetCall> targetCalls = new ArrayList<>();
        for (String targetMethod : targetMethods) {
            int lastSlash = targetMethod.lastIndexOf('/');
            if (lastSlash < 0) {
                lastSlash = targetMethod.lastIndexOf('.');
            }
            if (lastSlash <= 0) {
                continue;
            }
            String owner = targetMethod.substring(0, lastSlash).replace('.', '/');
            String name = targetMethod.substring(lastSlash + 1);
            String desc = targetMethodDesc;
            if (remap) {
                MappedMethod mapped = this.mappingResolver.mapMethod(owner, name, targetMethodDesc);
                owner = this.mappingResolver.mapClassName(owner);
                name = mapped.name();
                desc = mapped.desc();
            }
            targetCalls.add(new TargetCall(owner, name, desc));
        }
        return targetCalls;
    }

    private ShadowMethodInfo getShadowMethodInfo(MethodNode method, AnnotationNode annotation, boolean remap) {
        String targetMethodName = MixinAnnotationUtils.annotationStringValue(annotation, "value", method.name);
        String targetMethodDesc = method.desc;
        if (remap) {
            MappedMethod mapped = this.mappingResolver.mapMethod(this.targetInternalName, targetMethodName, method.desc);
            targetMethodName = mapped.name();
            targetMethodDesc = mapped.desc();
        }
        return new ShadowMethodInfo(method.name, targetMethodName, targetMethodDesc);
    }

    /**
     * Builds field access interception points after checking handler signature
     * compatibility. Invalid handlers are ignored with a diagnostic instead of
     * producing broken bytecode.
     */
    private void addReturnFieldPoints(MethodNode method, AnnotationNode annotation, String mixinInternal, boolean remap) {
        ReturnFieldSpec spec = this.readReturnFieldSpec(annotation);
        if (!this.validateReturnFieldMethod(method) || !this.validateReturnFieldSpec(method, spec)) {
            return;
        }

        Type[] args = Type.getMethodType(method.desc).getArgumentTypes();
        if (!this.validateReturnFieldArguments(method, spec, args)) {
            return;
        }

        String fieldDesc = this.resolveReturnFieldDescriptor(spec, args, remap);
        String handlerDescForCheck = remap ? this.mappingResolver.mapDescriptor(method.desc) : method.desc;
        String returnCastType = this.resolveReturnCastType(fieldDesc, handlerDescForCheck, spec.isStatic);
        if (ReturnFieldPoint.INCOMPATIBLE_CAST.equals(returnCastType)) {
            System.err.println("[SeraMixin] @ReturnField handler desc mismatch for "
                    + this.targetInternalName + " fields " + spec.fields + ": fieldDesc=" + fieldDesc
                    + " handler=" + method.desc);
            return;
        }

        this.addReturnFieldTargets(method, mixinInternal, remap, new ReturnFieldTargetSpec(spec, fieldDesc, returnCastType));
    }

    private ReturnFieldSpec readReturnFieldSpec(AnnotationNode annotation) {
        List<String> fields = MixinAnnotationUtils.annotationStringListValue(annotation, "field");
        String fieldDesc = MixinAnnotationUtils.annotationTypeDescriptorValue(annotation, "type");
        boolean isStatic = MixinAnnotationUtils.annotationBooleanValue(annotation, "isStatic", false);
        boolean read = MixinAnnotationUtils.annotationBooleanValue(annotation, "read", true);
        boolean write = MixinAnnotationUtils.annotationBooleanValue(annotation, "write", true);
        return new ReturnFieldSpec(fields, fieldDesc, isStatic, read, write);
    }

    private boolean validateReturnFieldMethod(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) != 0 && (method.access & Opcodes.ACC_PUBLIC) != 0) {
            return true;
        }
        System.err.println("[SeraMixin] @ReturnField handler must be public static: "
                + this.mixinClassName + "." + method.name + method.desc);
        return false;
    }

    private boolean validateReturnFieldSpec(MethodNode method, ReturnFieldSpec spec) {
        if (spec.fields.isEmpty()) {
            System.err.println("[SeraMixin] @ReturnField has no field names: " + this.mixinClassName + "." + method.name + method.desc);
            return false;
        }
        if (spec.read || spec.write) {
            return true;
        }
        System.err.println("[SeraMixin] @ReturnField must handle read or write: " + this.mixinClassName + "." + method.name + method.desc);
        return false;
    }

    private boolean validateReturnFieldArguments(MethodNode method, ReturnFieldSpec spec, Type[] args) {
        int expectedArgCount = spec.isStatic ? 1 : 2;
        if (args.length != expectedArgCount) {
            System.err.println("[SeraMixin] @ReturnField handler must accept exactly "
                    + expectedArgCount + " argument(s): " + this.mixinClassName + "." + method.name + method.desc);
            return false;
        }
        if (spec.isStatic || this.isReturnFieldSelfArgCompatible(args[0])) {
            return true;
        }
        System.err.println("[SeraMixin] @ReturnField handler first argument must be "
                + this.targetInternalName.replace('/', '.') + " or its supertype: "
                + this.mixinClassName + "." + method.name + method.desc);
        return false;
    }

    private String resolveReturnFieldDescriptor(ReturnFieldSpec spec, Type[] args, boolean remap) {
        String fieldDesc = spec.fieldDesc;
        if (fieldDesc == null || fieldDesc.isEmpty()) {
            fieldDesc = args[spec.isStatic ? 0 : 1].getDescriptor();
        }
        return remap ? this.mappingResolver.mapDescriptor(fieldDesc) : fieldDesc;
    }

    private void addReturnFieldTargets(MethodNode method, String mixinInternal, boolean remap, ReturnFieldTargetSpec targetSpec) {
        ReturnFieldSpec spec = targetSpec.spec;
        for (String fieldName : new LinkedHashSet<>(spec.fields)) {
            String targetFieldName = remap ? this.mappingResolver.mapFieldName(this.targetInternalName, fieldName) : fieldName;
            this.returnFieldPoints.add(new ReturnFieldPoint(
                    this.targetInternalName,
                    targetFieldName,
                    targetSpec.fieldDesc,
                    spec.isStatic,
                    mixinInternal,
                    method.name,
                    method.desc,
                    targetSpec.returnCastType,
                    spec.read,
                    spec.write));
        }
    }

    private boolean isReturnFieldSelfArgCompatible(Type argType) {
        if (argType.getSort() != Type.OBJECT) {
            return false;
        }
        String argInternal = this.mappingResolver.mapClassName(argType.getInternalName());
        return "java/lang/Object".equals(argInternal)
                || this.targetInternalName.equals(argInternal)
                || this.hierarchyResolver.isSubclassASM(this.targetInternalName, argInternal, this.loader);
    }

    private String resolveReturnCastType(String fieldDesc, String handlerDesc, boolean isStatic) {
        Type fieldType = Type.getType(fieldDesc);
        Type handlerType = Type.getMethodType(handlerDesc);
        Type[] args = handlerType.getArgumentTypes();
        Type returnType = handlerType.getReturnType();
        int fieldArgIndex = isStatic ? 0 : 1;
        if (args.length <= fieldArgIndex || returnType.getSort() == Type.VOID) {
            return ReturnFieldPoint.INCOMPATIBLE_CAST;
        }

        String argDesc = args[fieldArgIndex].getDescriptor();
        String returnDesc = returnType.getDescriptor();
        boolean referenceField = fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY;
        boolean argOk = argDesc.equals(fieldDesc) || (referenceField && "Ljava/lang/Object;".equals(argDesc));
        boolean returnOk = returnDesc.equals(fieldDesc) || (referenceField && "Ljava/lang/Object;".equals(returnDesc));
        if (!argOk || !returnOk) {
            return ReturnFieldPoint.INCOMPATIBLE_CAST;
        }
        if (referenceField && !returnDesc.equals(fieldDesc)) {
            return fieldType.getSort() == Type.ARRAY ? fieldDesc : fieldType.getInternalName();
        }
        return null;
    }

    /**
     * Rewrites shadow member calls in cloned mixin methods so the final method
     * body targets the transformed class instead of the mixin class.
     */
    private void rewriteShadowReferences(MethodNode method, String mixinInternal) {
        if (this.shadowFields.isEmpty() && this.shadowMethods.isEmpty()) {
            return;
        }
        InsnList instructions = method.instructions;
        if (instructions == null) {
            return;
        }
        for (AbstractInsnNode node : instructions.toArray()) {
            if (node instanceof FieldInsnNode fieldNode) {
                this.rewriteShadowFieldReference(instructions, fieldNode, mixinInternal);
                continue;
            }
            if (node instanceof MethodInsnNode methodNode) {
                this.rewriteShadowMethodReference(instructions, methodNode, mixinInternal);
            }
        }
    }

    private void rewriteShadowFieldReference(InsnList instructions, FieldInsnNode fieldNode, String mixinInternal) {
        ShadowFieldInfo shadowField = this.shadowFields.get(fieldNode.name);
        if (!fieldNode.owner.equals(mixinInternal) || shadowField == null) {
            return;
        }
        fieldNode.owner = this.targetInternalName;
        fieldNode.name = shadowField.targetFieldName;
        fieldNode.desc = shadowField.desc;
        this.rewriteStaticShadowFieldAccess(instructions, fieldNode, shadowField.desc);
    }

    private void rewriteStaticShadowFieldAccess(InsnList instructions, FieldInsnNode fieldNode, String fieldDesc) {
        int opcode = fieldNode.getOpcode();
        if (opcode == Opcodes.GETSTATIC) {
            fieldNode.setOpcode(Opcodes.GETFIELD);
            instructions.insertBefore(fieldNode, new VarInsnNode(Opcodes.ALOAD, 0));
            return;
        }
        if (opcode == Opcodes.PUTSTATIC) {
            fieldNode.setOpcode(Opcodes.PUTFIELD);
            Type type = Type.getType(fieldDesc);
            instructions.insertBefore(fieldNode, new VarInsnNode(Opcodes.ALOAD, 0));
            instructions.insertBefore(fieldNode, type.getSize() == 1 ? new InsnNode(Opcodes.SWAP) : new InsnNode(Opcodes.DUP_X2));
            if (type.getSize() == 2) {
                instructions.insertBefore(fieldNode, new InsnNode(Opcodes.POP));
            }
        }
    }

    private void rewriteShadowMethodReference(InsnList instructions, MethodInsnNode methodNode, String mixinInternal) {
        if (!methodNode.owner.equals(mixinInternal)) {
            return;
        }
        ShadowMethodInfo shadowMethod = this.findShadowMethod(methodNode);
        if (shadowMethod == null) {
            return;
        }
        methodNode.owner = this.targetInternalName;
        methodNode.name = shadowMethod.targetMethodName;
        methodNode.desc = shadowMethod.desc;
        if (methodNode.getOpcode() == Opcodes.INVOKESTATIC) {
            methodNode.setOpcode(Opcodes.INVOKEVIRTUAL);
            instructions.insertBefore(methodNode, new VarInsnNode(Opcodes.ALOAD, 0));
        }
    }

    private ShadowMethodInfo findShadowMethod(MethodInsnNode methodNode) {
        ShadowMethodInfo shadowMethod = this.shadowMethods.get(methodNode.name + methodNode.desc);
        if (shadowMethod != null) {
            return shadowMethod;
        }
        for (ShadowMethodInfo info : this.shadowMethods.values()) {
            if (info.mixinMethodName.equals(methodNode.name)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Performs an ASM method clone with fresh labels so inserted bytecode does
     * not share mutable instruction metadata with the original mixin method.
     */
    MethodNode cloneMethod(MethodNode original) {
        MethodNode copy = new MethodNode(original.access, original.name, original.desc, original.signature,
                original.exceptions != null ? original.exceptions.toArray(new String[0]) : null);
        HashMap<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                putValue(labelMap, label, new LabelNode());
            }
        }
        for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            copy.instructions.add(insn.clone(labelMap));
        }
        if (original.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : original.tryCatchBlocks) {
                copy.tryCatchBlocks.add(new TryCatchBlockNode(
                        labelFor(labelMap, block.start),
                        labelFor(labelMap, block.end),
                        labelFor(labelMap, block.handler),
                        block.type));
            }
        }
        if (original.localVariables != null) {
            for (LocalVariableNode local : original.localVariables) {
                copy.localVariables.add(new LocalVariableNode(local.name, local.desc, local.signature,
                        labelFor(labelMap, local.start), labelFor(labelMap, local.end), local.index));
            }
        }
        copy.maxStack = Math.max(original.maxStack, 0);
        copy.maxLocals = Math.max(original.maxLocals, 0);
        return copy;
    }

    private boolean shadowHeaderPrinted() {
        return Boolean.TRUE.equals(this.hasPrintedShadowHeader.get());
    }

    private static void acceptExpandedClass(byte[] classBytes, ClassNode classNode) {
        try {
            new ClassReader(classBytes).accept(classNode, ClassReader.EXPAND_FRAMES);
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    private static <K, V> V putValue(Map<K, V> map, K key, V value) {
        return map.put(key, value);
    }

    private static LabelNode labelFor(Map<LabelNode, LabelNode> labelMap, LabelNode label) {
        return labelMap.get(label);
    }

    private void ensureShadowHeader() {
        if (!this.shadowHeaderPrinted()) {
            this.hasPrintedShadowHeader.set(true);
        }
    }

    private static final class MethodScan {
        private final ArrayList<AnnotationNode> injectAnnotations = new ArrayList<>();
        private final ArrayList<InjectInfo> injects = new ArrayList<>();
        private final ArrayList<OverwriteInfo> overwrites = new ArrayList<>();
        private CustomInjectInfo customInjectPoint;
    }

    private static final class ReturnFieldTargetSpec {
        private final ReturnFieldSpec spec;
        private final String fieldDesc;
        private final String returnCastType;

        private ReturnFieldTargetSpec(ReturnFieldSpec spec, String fieldDesc, String returnCastType) {
            this.spec = spec;
            this.fieldDesc = fieldDesc;
            this.returnCastType = returnCastType;
        }
    }

    static final class Services {
        private final MixinClassProvider classProvider;
        private final MixinHierarchyResolver hierarchyResolver;
        private final ThreadLocal<Boolean> hasPrintedShadowHeader;
        private final ThreadLocal<String> currentTargetClass;

        Services(MixinClassProvider classProvider, MixinHierarchyResolver hierarchyResolver,
                 ThreadLocal<Boolean> hasPrintedShadowHeader, ThreadLocal<String> currentTargetClass) {
            this.classProvider = classProvider;
            this.hierarchyResolver = hierarchyResolver;
            this.hasPrintedShadowHeader = hasPrintedShadowHeader;
            this.currentTargetClass = currentTargetClass;
        }
    }

    private record InjectInfo(String methodName, String desc, InsertPosition at, InjectionSelector selector) {
    }

    private record OverwriteInfo(String methodName, String desc) {
    }

    private record ReturnFieldSpec(List<String> fields, String fieldDesc, boolean isStatic, boolean read, boolean write) {
    }

    private record CustomInjectInfo(InsertPosition at, InjectionSelector selector) {
    }

    private record ParsedInjectionTarget(String owner, String name, String desc) {
        private static final ParsedInjectionTarget EMPTY = new ParsedInjectionTarget("", "", "");
    }
}
