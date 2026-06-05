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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    TransformerHolder(MixinClassProvider classProvider, MixinHierarchyResolver hierarchyResolver,
                      String mixinClassName, String targetInternalName,
                      ClassLoader loader, ThreadLocal<Boolean> hasPrintedShadowHeader,
                      ThreadLocal<String> currentTargetClass, ClassLoader mixinClassLoader,
                      MixinMappingResolver mappingResolver) throws IOException {
        this.classProvider = classProvider;
        this.hierarchyResolver = hierarchyResolver;
        this.mixinClassName = mixinClassName;
        this.targetInternalName = targetInternalName;
        this.loader = loader != null ? loader : classProvider.getRuntimeClassLoader();
        this.mixinClassLoader = mixinClassLoader != null ? mixinClassLoader : this.loader;
        this.mappingResolver = mappingResolver == null ? MixinMappingResolver.EMPTY : mappingResolver;
        this.hasPrintedShadowHeader = hasPrintedShadowHeader;
        this.currentTargetClass = currentTargetClass;
        this.mixinClassNode = this.scanWithASM();
    }

    private ClassNode scanWithASM() throws IOException {
        byte[] mixinBytes = this.classProvider.loadMixinBytes(this.mixinClassName, this.mixinClassLoader);
        ClassNode classNode = new ClassNode();
        new ClassReader(mixinBytes).accept(classNode, ClassReader.EXPAND_FRAMES);
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
            for (AnnotationNode annotation : MixinAnnotationUtils.annotationNodes(field.visibleAnnotations, field.invisibleAnnotations)) {
                if (!MixinConstants.SHADOW_CLASS.equals(annotation.desc)) {
                    continue;
                }
                String targetFieldName = MixinAnnotationUtils.annotationStringValue(annotation, "value", field.name);
                if (remap) {
                    targetFieldName = this.mappingResolver.mapFieldName(this.targetInternalName, targetFieldName);
                }
                String targetFieldDesc = remap ? this.mappingResolver.mapDescriptor(field.desc) : field.desc;
                this.shadowFields.put(field.name, new ShadowFieldInfo(field.name, targetFieldName, targetFieldDesc));
                this.ensureShadowHeader();
                break;
            }
        }
    }

    private void scanMethod(MethodNode method, String mixinInternal, boolean remap) {
        ArrayList<InjectInfo> injects = new ArrayList<>();
        ArrayList<OverwriteInfo> overwrites = new ArrayList<>();

        for (AnnotationNode annotation : MixinAnnotationUtils.annotationNodes(method.visibleAnnotations, method.invisibleAnnotations)) {
            String desc = annotation.desc;
            if (MixinConstants.SHADOW_CLASS.equals(desc)) {
                ShadowMethodInfo shadowMethodInfo = this.getShadowMethodInfo(method, annotation, remap);
                this.shadowMethods.put(method.name + method.desc, shadowMethodInfo);
                this.ensureShadowHeader();
            } else if (MixinConstants.RETURN_FIELD_CLASS.equals(desc)) {
                this.addReturnFieldPoints(method, annotation, mixinInternal, remap);
            } else if (MixinConstants.INJECT_CLASS.equals(desc)) {
                injects.addAll(this.readInjectPoints(annotation, InsertPosition.HEAD, remap));
            } else if (MixinConstants.ASM_CLASS.equals(desc)) {
                this.reportUnsupportedASMHandler(method);
            } else if (MixinConstants.REDIRECT_CLASS.equals(desc)) {
                this.redirectPoints.addAll(this.readRedirectPoints(method, annotation, remap));
            } else if (MixinConstants.OVERWRITE_CLASS.equals(desc)) {
                overwrites.addAll(this.readOverwritePoints(annotation, remap));
            }
        }

        for (InjectInfo injectInfo : injects) {
            this.injectPoints.add(new InjectPoint(injectInfo.methodName, injectInfo.desc, injectInfo.at,
                    this.mixinClassName, method.name, method.desc, (method.access & Opcodes.ACC_STATIC) != 0,
                    InjectMode.MIXIN));
        }
        for (OverwriteInfo overwriteInfo : overwrites) {
            this.overwritePoints.add(new OverwritePoint(overwriteInfo.methodName, overwriteInfo.desc,
                    this.mixinClassName, method.name, method.desc));
        }

        MethodNode clonedMethod = this.cloneMethod(method);
        this.rewriteShadowReferences(clonedMethod, mixinInternal);
        this.rewrittenMethodCache.put(method.name + method.desc, clonedMethod);
    }

    private List<InjectInfo> readInjectPoints(AnnotationNode annotation, InsertPosition defaultPosition, boolean remap) {
        List<String> methodNames = MixinAnnotationUtils.annotationStringListValue(annotation, "methodName");
        String methodDesc = MixinAnnotationUtils.annotationStringValue(annotation, "desc", "");
        InsertPosition position = MixinAnnotationUtils.annotationEnumValue(annotation, "at", defaultPosition);
        ArrayList<InjectInfo> points = new ArrayList<>();
        for (String methodName : methodNames) {
            MappedMethod mapped = remap
                    ? this.mappingResolver.mapMethod(this.targetInternalName, methodName, methodDesc)
                    : new MappedMethod(methodName, methodDesc);
            points.add(new InjectInfo(mapped.name(), mapped.desc(), position));
        }
        return points;
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

    private void addReturnFieldPoints(MethodNode method, AnnotationNode annotation, String mixinInternal, boolean remap) {
        if ((method.access & Opcodes.ACC_STATIC) == 0 || (method.access & Opcodes.ACC_PUBLIC) == 0) {
            System.err.println("[SeraMixin] @ReturnField handler must be public static: "
                    + this.mixinClassName + "." + method.name + method.desc);
            return;
        }

        List<String> fields = MixinAnnotationUtils.annotationStringListValue(annotation, "field");
        String fieldDesc = MixinAnnotationUtils.annotationTypeDescriptorValue(annotation, "type");
        boolean isStatic = MixinAnnotationUtils.annotationBooleanValue(annotation, "isStatic", false);
        boolean read = MixinAnnotationUtils.annotationBooleanValue(annotation, "read", true);
        boolean write = MixinAnnotationUtils.annotationBooleanValue(annotation, "write", true);

        if (fields.isEmpty()) {
            System.err.println("[SeraMixin] @ReturnField has no field names: " + this.mixinClassName + "." + method.name + method.desc);
            return;
        }
        if (!read && !write) {
            System.err.println("[SeraMixin] @ReturnField must handle read or write: " + this.mixinClassName + "." + method.name + method.desc);
            return;
        }

        Type[] args = Type.getMethodType(method.desc).getArgumentTypes();
        int expectedArgCount = isStatic ? 1 : 2;
        if (args.length != expectedArgCount) {
            System.err.println("[SeraMixin] @ReturnField handler must accept exactly "
                    + expectedArgCount + " argument(s): " + this.mixinClassName + "." + method.name + method.desc);
            return;
        }
        if (!isStatic && !this.isReturnFieldSelfArgCompatible(args[0])) {
            System.err.println("[SeraMixin] @ReturnField handler first argument must be "
                    + this.targetInternalName.replace('/', '.') + " or its supertype: "
                    + this.mixinClassName + "." + method.name + method.desc);
            return;
        }
        if (fieldDesc == null || fieldDesc.isEmpty()) {
            fieldDesc = args[isStatic ? 0 : 1].getDescriptor();
        }
        if (remap) {
            fieldDesc = this.mappingResolver.mapDescriptor(fieldDesc);
        }

        String handlerDescForCheck = remap ? this.mappingResolver.mapDescriptor(method.desc) : method.desc;
        String returnCastType = this.resolveReturnCastType(fieldDesc, handlerDescForCheck, isStatic);
        if (ReturnFieldPoint.INCOMPATIBLE_CAST.equals(returnCastType)) {
            System.err.println("[SeraMixin] @ReturnField handler desc mismatch for "
                    + this.targetInternalName + " fields " + fields + ": fieldDesc=" + fieldDesc
                    + " handler=" + method.desc);
            return;
        }

        for (String fieldName : new LinkedHashSet<>(fields)) {
            String targetFieldName = remap ? this.mappingResolver.mapFieldName(this.targetInternalName, fieldName) : fieldName;
            this.returnFieldPoints.add(new ReturnFieldPoint(
                    this.targetInternalName,
                    targetFieldName,
                    fieldDesc,
                    isStatic,
                    mixinInternal,
                    method.name,
                    method.desc,
                    returnCastType,
                    read,
                    write));
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
                ShadowFieldInfo shadowField = this.shadowFields.get(fieldNode.name);
                if (!fieldNode.owner.equals(mixinInternal) || shadowField == null) {
                    continue;
                }
                fieldNode.owner = this.targetInternalName;
                fieldNode.name = shadowField.targetFieldName;
                fieldNode.desc = shadowField.desc;
                int opcode = fieldNode.getOpcode();
                if (opcode == Opcodes.GETSTATIC) {
                    fieldNode.setOpcode(Opcodes.GETFIELD);
                    instructions.insertBefore(fieldNode, new VarInsnNode(Opcodes.ALOAD, 0));
                } else if (opcode == Opcodes.PUTSTATIC) {
                    fieldNode.setOpcode(Opcodes.PUTFIELD);
                    Type type = Type.getType(shadowField.desc);
                    instructions.insertBefore(fieldNode, new VarInsnNode(Opcodes.ALOAD, 0));
                    instructions.insertBefore(fieldNode, type.getSize() == 1 ? new InsnNode(Opcodes.SWAP) : new InsnNode(Opcodes.DUP_X2));
                    if (type.getSize() == 2) {
                        instructions.insertBefore(fieldNode, new InsnNode(Opcodes.POP));
                    }
                }
                continue;
            }
            if (!(node instanceof MethodInsnNode methodNode) || !methodNode.owner.equals(mixinInternal)) {
                continue;
            }

            ShadowMethodInfo shadowMethod = this.shadowMethods.get(methodNode.name + methodNode.desc);
            if (shadowMethod == null) {
                for (ShadowMethodInfo info : this.shadowMethods.values()) {
                    if (info.mixinMethodName.equals(methodNode.name)) {
                        shadowMethod = info;
                        break;
                    }
                }
            }
            if (shadowMethod == null) {
                continue;
            }

            methodNode.owner = this.targetInternalName;
            methodNode.name = shadowMethod.targetMethodName;
            methodNode.desc = shadowMethod.desc;
            if (methodNode.getOpcode() == Opcodes.INVOKESTATIC) {
                methodNode.setOpcode(Opcodes.INVOKEVIRTUAL);
                instructions.insertBefore(methodNode, new VarInsnNode(Opcodes.ALOAD, 0));
            }
        }
    }

    MethodNode cloneMethod(MethodNode original) {
        MethodNode copy = new MethodNode(original.access, original.name, original.desc, original.signature,
                original.exceptions != null ? original.exceptions.toArray(new String[0]) : null);
        HashMap<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labelMap.put(label, new LabelNode());
            }
        }
        for (AbstractInsnNode insn = original.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            copy.instructions.add(insn.clone(labelMap));
        }
        if (original.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : original.tryCatchBlocks) {
                copy.tryCatchBlocks.add(new TryCatchBlockNode(
                        labelMap.get(block.start),
                        labelMap.get(block.end),
                        labelMap.get(block.handler),
                        block.type));
            }
        }
        if (original.localVariables != null) {
            for (LocalVariableNode local : original.localVariables) {
                copy.localVariables.add(new LocalVariableNode(local.name, local.desc, local.signature,
                        labelMap.get(local.start), labelMap.get(local.end), local.index));
            }
        }
        copy.maxStack = Math.max(original.maxStack, 0);
        copy.maxLocals = Math.max(original.maxLocals, 0);
        return copy;
    }

    private void ensureShadowHeader() {
        if (!Boolean.TRUE.equals(this.hasPrintedShadowHeader.get())) {
            this.hasPrintedShadowHeader.set(true);
        }
    }

    private record InjectInfo(String methodName, String desc, InsertPosition at) {
    }

    private record OverwriteInfo(String methodName, String desc) {
    }
}
