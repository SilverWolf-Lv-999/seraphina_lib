package seraphina.seraphina_lib.mixin.service;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.mixin.util.InsertShift;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies pre-scanned mixin metadata to ASM class nodes during launch-time
 * transformation. The engine keeps each transformation step isolated so failed
 * mixins can be reported without corrupting the target class.
 */
final class MixinTransformerEngine {
    private final Map<String, List<ClassInfo>> registeredMixins;
    private final Map<String, TransformerHolder> activeTransformers = new ConcurrentHashMap<>();
    private final MixinClassProvider classProvider;
    private final MixinHierarchyResolver hierarchyResolver;
    private final ThreadLocal<Boolean> isSubclassMode;
    private final ThreadLocal<Boolean> hasPrintedShadowHeader;
    private final ThreadLocal<String> currentTargetClass;
    private final TransformerHolder.Services transformerHolderServices;
    private volatile boolean returnFieldHoldersInitialized;

    MixinTransformerEngine(Map<String, List<ClassInfo>> registeredMixins,
                           MixinClassProvider classProvider,
                           MixinHierarchyResolver hierarchyResolver,
                           ThreadLocal<Boolean> isSubclassMode,
                           ThreadLocal<Boolean> hasPrintedShadowHeader,
                           ThreadLocal<String> currentTargetClass) {
        this.registeredMixins = registeredMixins;
        this.classProvider = classProvider;
        this.hierarchyResolver = hierarchyResolver;
        this.isSubclassMode = isSubclassMode;
        this.hasPrintedShadowHeader = hasPrintedShadowHeader;
        this.currentTargetClass = currentTargetClass;
        this.transformerHolderServices = new TransformerHolder.Services(
                classProvider,
                hierarchyResolver,
                hasPrintedShadowHeader,
                currentTargetClass);
    }

    boolean applyClassNodeTransform(ClassNode classNode, String internalName, ClassLoader loader) {
        List<ClassInfo> directInfos = this.registeredMixins.get(internalName);
        boolean changed;
        if (hasClassInfos(directInfos)) {
            this.isSubclassMode.set(false);
            changed = this.applyMixins(classNode, directInfos, loader, internalName);
        } else {
            changed = this.applyParentMixins(classNode, internalName, loader);
        }

        if (this.applyReturnFields(classNode, loader, this.collectReturnFieldPoints(loader))) {
            changed = true;
        }
        return changed;
    }

    private boolean applyParentMixins(ClassNode classNode, String internalName, ClassLoader loader) {
        String parentTarget = this.hierarchyResolver.findParentTarget(internalName, classNode, loader);
        if (parentTarget == null) {
            return false;
        }
        List<ClassInfo> parentInfos = this.registeredMixins.get(parentTarget);
        if (!hasClassInfos(parentInfos)) {
            return false;
        }
        this.isSubclassMode.set(true);
        return this.applyMixins(classNode, parentInfos, loader, internalName);
    }

    private static boolean hasClassInfos(List<ClassInfo> infos) {
        return infos != null && !infos.isEmpty();
    }

    String[] getReturnFieldTargets(ClassLoader loader) {
        this.ensureReturnFieldHoldersInitialized(loader);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (TransformerHolder holder : this.activeTransformers.values()) {
            if (holder == null || holder.returnFieldPoints.isEmpty()) {
                continue;
            }
            for (ReturnFieldPoint point : holder.returnFieldPoints) {
                targets.add(point.ownerInternalName + "/" + point.fieldName + " " + point.fieldDesc);
            }
        }
        return targets.toArray(new String[0]);
    }

    void invalidateReturnFieldHolders() {
        this.returnFieldHoldersInitialized = false;
    }

    private boolean applyMixins(ClassNode classNode, List<ClassInfo> infos, ClassLoader loader, String actualClassName) {
        boolean changed = false;
        for (ClassInfo info : infos) {
            try {
                TransformerHolder holder = this.getOrCreateTransformerHolder(info, loader, "Failed to scan mixin: ");
                if (info.hook != null && !info.hook.shouldApplyMixin(classNode, holder.mixinClassNode)) {
                    continue;
                }
                changed |= this.applyTransform(classNode, holder, loader, actualClassName);
            } catch (Throwable throwable) {
                System.err.println("[SeraMixin] Failed to apply " + info.mixinClassName + " to " + actualClassName + ": " + throwable.getMessage());
                throwable.printStackTrace(System.err);
            }
        }
        return changed;
    }

    private TransformerHolder getOrCreateTransformerHolder(ClassInfo info, ClassLoader loader, String errorPrefix) {
        TransformerHolder holder = this.activeTransformers.get(info.key());
        if (holder != null) {
            return holder;
        }
        try {
            TransformerHolder created = new TransformerHolder(this.transformerHolderServices, info, loader);
            TransformerHolder existing = this.activeTransformers.putIfAbsent(info.key(), created);
            return existing == null ? created : existing;
        } catch (Throwable throwable) {
            throw new RuntimeException(errorPrefix + info.mixinClassName, throwable);
        }
    }

    private void ensureReturnFieldHoldersInitialized(ClassLoader loader) {
        if (this.returnFieldHoldersInitialized) {
            return;
        }
        synchronized (this.activeTransformers) {
            if (this.returnFieldHoldersInitialized) {
                return;
            }
            for (ClassInfo info : this.allClassInfos()) {
                this.getOrCreateTransformerHolder(info, loader, "ReturnField scan failed: ");
            }
            this.returnFieldHoldersInitialized = true;
        }
    }

    private List<ReturnFieldPoint> collectReturnFieldPoints(ClassLoader loader) {
        this.ensureReturnFieldHoldersInitialized(loader);
        ArrayList<ReturnFieldPoint> points = new ArrayList<>();
        for (TransformerHolder holder : this.activeTransformers.values()) {
            if (holder != null && !holder.returnFieldPoints.isEmpty()) {
                points.addAll(holder.returnFieldPoints);
            }
        }
        return points;
    }

    private List<ClassInfo> allClassInfos() {
        ArrayList<ClassInfo> result = new ArrayList<>();
        for (List<ClassInfo> infos : this.registeredMixins.values()) {
            result.addAll(infos);
        }
        result.sort(Comparator
                .comparingInt(ClassInfo::priority).reversed()
                .thenComparing(ClassInfo::mixinClassName));
        return result;
    }

    /**
     * Applies overwrite, inject, and redirect phases in a fixed order. Lambdas
     * and copied handlers are staged first, then appended after each phase so
     * method iteration does not observe partially-added methods.
     */
    private boolean applyTransform(ClassNode classNode, TransformerHolder holder, ClassLoader loader, String actualClassName) throws Exception {
        String mixinInternal = holder.mixinClassName.replace('.', '/');
        String targetInternal = holder.targetInternalName;
        ArrayList<MethodNode> lambdaMethodsToAdd = new ArrayList<>();
        ArrayList<MethodNode> injectHandlerMethodsToAdd = new ArrayList<>();
        InjectionState injectionState = new InjectionState(injectHandlerMethodsToAdd);

        boolean anyMatched = this.applyOverwriteTransforms(classNode, holder, targetInternal, mixinInternal, lambdaMethodsToAdd);
        appendPendingMethods(classNode, lambdaMethodsToAdd);
        anyMatched |= this.applyInjectTransforms(classNode, holder, loader, actualClassName, injectionState);
        anyMatched |= this.applyModifyConstantTransforms(classNode, holder, loader, actualClassName, injectionState);
        anyMatched |= this.applyModifyArgsTransforms(classNode, holder, loader, actualClassName, injectionState);
        anyMatched |= this.applyModifyVariableTransforms(classNode, holder, loader, actualClassName, injectionState);
        appendPendingMethods(classNode, injectHandlerMethodsToAdd);
        anyMatched |= this.applyRedirectTransforms(classNode, holder, mixinInternal);
        anyMatched |= this.applyMixinMembers(classNode, holder, mixinInternal, actualClassName);
        return anyMatched;
    }

    private boolean applyOverwriteTransforms(ClassNode classNode, TransformerHolder holder, String targetInternal,
                                             String mixinInternal, List<MethodNode> lambdaMethodsToAdd) throws IOException, NoSuchMethodException {
        boolean changed = false;
        MixinCopyContext copyContext = new MixinCopyContext(classNode, holder, targetInternal, mixinInternal, lambdaMethodsToAdd);
        for (MethodNode method : classNode.methods) {
            for (OverwritePoint point : holder.overwritePoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                this.applyOverwrite(method, point, copyContext);
                changed = true;
            }
        }
        return changed;
    }

    private boolean applyInjectTransforms(ClassNode classNode, TransformerHolder holder, ClassLoader loader,
                                          String actualClassName, InjectionState injectionState) throws NoSuchMethodException, IOException {
        boolean changed = false;
        InjectContext injectContext = new InjectContext(holder, loader, actualClassName, classNode, injectionState);
        for (MethodNode method : classNode.methods) {
            for (InjectPoint point : holder.injectPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                changed |= this.applyMixinInject(method, point, injectContext);
            }
        }
        return changed;
    }

    private boolean applyModifyConstantTransforms(ClassNode classNode, TransformerHolder holder, ClassLoader loader,
                                                  String actualClassName, InjectionState injectionState) throws NoSuchMethodException, IOException {
        boolean changed = false;
        InjectContext context = new InjectContext(holder, loader, actualClassName, classNode, injectionState);
        for (MethodNode method : classNode.methods) {
            for (ModifyConstantPoint point : holder.modifyConstantPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                changed |= this.applyModifyConstantToMethod(method, point, context);
            }
        }
        return changed;
    }

    private boolean applyModifyArgsTransforms(ClassNode classNode, TransformerHolder holder, ClassLoader loader,
                                              String actualClassName, InjectionState injectionState) throws NoSuchMethodException, IOException {
        boolean changed = false;
        InjectContext context = new InjectContext(holder, loader, actualClassName, classNode, injectionState);
        for (MethodNode method : classNode.methods) {
            for (ModifyArgsPoint point : holder.modifyArgsPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                changed |= this.applyModifyArgsToMethod(method, point, context);
            }
        }
        return changed;
    }

    private boolean applyModifyVariableTransforms(ClassNode classNode, TransformerHolder holder, ClassLoader loader,
                                                  String actualClassName, InjectionState injectionState) throws NoSuchMethodException, IOException {
        boolean changed = false;
        InjectContext context = new InjectContext(holder, loader, actualClassName, classNode, injectionState);
        for (MethodNode method : classNode.methods) {
            for (ModifyVariablePoint point : holder.modifyVariablePoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                changed |= this.applyModifyVariableToMethod(method, point, context);
            }
        }
        return changed;
    }

    private boolean applyRedirectTransforms(ClassNode classNode, TransformerHolder holder, String mixinInternal) {
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            for (RedirectPoint point : holder.redirectPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                changed |= this.applyRedirectToMethod(method, point, mixinInternal);
            }
        }
        return changed;
    }

    private boolean applyMixinMembers(ClassNode classNode, TransformerHolder holder, String mixinInternal,
                                      String actualClassName) throws IOException {
        boolean changed = false;
        changed |= this.applyMixinInterfaces(classNode, holder);
        changed |= this.applyMixinFields(classNode, holder, mixinInternal, actualClassName);
        changed |= this.applyMixinFieldInitializers(classNode, holder, mixinInternal, actualClassName);
        changed |= this.applyAccessorAndInvokerMethods(classNode, holder, actualClassName);
        changed |= this.applyMixinMethods(classNode, holder, mixinInternal, actualClassName);
        return changed;
    }

    private boolean applyMixinInterfaces(ClassNode classNode, TransformerHolder holder) {
        if (holder.interfaceInternalNames.isEmpty()) {
            return false;
        }
        if (classNode.interfaces == null) {
            classNode.interfaces = new ArrayList<>();
        }
        boolean changed = false;
        for (String interfaceName : holder.interfaceInternalNames) {
            if (!classNode.interfaces.contains(interfaceName)) {
                classNode.interfaces.add(interfaceName);
                changed = true;
            }
        }
        return changed;
    }

    private boolean applyAccessorAndInvokerMethods(ClassNode classNode, TransformerHolder holder, String actualClassName) {
        if (holder.accessorPoints.isEmpty() && holder.invokerPoints.isEmpty()) {
            return false;
        }
        ArrayList<MethodNode> methodsToAdd = new ArrayList<>();
        for (AccessorPoint point : holder.accessorPoints) {
            MethodNode generated = this.generateAccessorMethod(point, actualClassName);
            if (generated == null || this.hasMethod(classNode, methodsToAdd, generated.name, generated.desc)) {
                continue;
            }
            methodsToAdd.add(generated);
        }
        for (InvokerPoint point : holder.invokerPoints) {
            MethodNode generated = this.generateInvokerMethod(point, classNode, actualClassName);
            if (generated == null || this.hasMethod(classNode, methodsToAdd, generated.name, generated.desc)) {
                continue;
            }
            methodsToAdd.add(generated);
        }
        appendPendingMethods(classNode, methodsToAdd);
        return !methodsToAdd.isEmpty();
    }

    private MethodNode generateAccessorMethod(AccessorPoint point, String targetInternal) {
        boolean bridgeStatic = (point.mixinMethodAccess & Opcodes.ACC_STATIC) != 0;
        if (bridgeStatic && !point.targetStatic) {
            this.reportInvalidBridge("@Accessor", point.mixinMethodName, point.mixinMethodDesc,
                    "static bridge cannot access an instance field");
            return null;
        }
        if (!this.isAccessorDescriptorCompatible(point)) {
            this.reportInvalidBridge("@Accessor", point.mixinMethodName, point.mixinMethodDesc,
                    "method descriptor does not match target field type " + point.targetFieldDesc);
            return null;
        }

        MethodNode method = new MethodNode(sanitizeBridgeAccess(point.mixinMethodAccess),
                point.mixinMethodName, point.mixinMethodDesc, null, null);
        if (point.setter) {
            this.writeAccessorSetter(method, point, bridgeStatic, targetInternal);
        } else {
            this.writeAccessorGetter(method, point, targetInternal);
        }
        method.maxLocals = bridgeLocalSlots(point.mixinMethodDesc, bridgeStatic);
        method.maxStack = 0;
        return method;
    }

    private boolean isAccessorDescriptorCompatible(AccessorPoint point) {
        Type bridgeType = Type.getMethodType(point.mixinMethodDesc);
        Type fieldType = Type.getType(point.targetFieldDesc);
        Type[] args = bridgeType.getArgumentTypes();
        if (point.setter) {
            return bridgeType.getReturnType().getSort() == Type.VOID
                    && args.length == 1
                    && isSameStackType(args[0], fieldType);
        }
        return args.length == 0 && isSameStackType(bridgeType.getReturnType(), fieldType);
    }

    private void writeAccessorGetter(MethodNode method, AccessorPoint point, String targetInternal) {
        if (!point.targetStatic) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        method.instructions.add(new FieldInsnNode(point.targetStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD,
                targetInternal, point.targetFieldName, point.targetFieldDesc));
        method.instructions.add(returnInsn(Type.getReturnType(point.mixinMethodDesc)));
    }

    private void writeAccessorSetter(MethodNode method, AccessorPoint point, boolean bridgeStatic, String targetInternal) {
        Type fieldType = Type.getType(point.targetFieldDesc);
        int valueIndex = bridgeStatic ? 0 : 1;
        if (!point.targetStatic) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        method.instructions.add(this.getLoadInsn(fieldType, valueIndex));
        method.instructions.add(new FieldInsnNode(point.targetStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD,
                targetInternal, point.targetFieldName, point.targetFieldDesc));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    private MethodNode generateInvokerMethod(InvokerPoint point, ClassNode classNode, String targetInternal) {
        boolean bridgeStatic = (point.mixinMethodAccess & Opcodes.ACC_STATIC) != 0;
        if (bridgeStatic && !point.targetStatic) {
            this.reportInvalidBridge("@Invoker", point.mixinMethodName, point.mixinMethodDesc,
                    "static bridge cannot invoke an instance method");
            return null;
        }
        if (!this.isInvokerDescriptorCompatible(point)) {
            this.reportInvalidBridge("@Invoker", point.mixinMethodName, point.mixinMethodDesc,
                    "method descriptor does not match target method descriptor " + point.targetMethodDesc);
            return null;
        }

        MethodNode method = new MethodNode(sanitizeBridgeAccess(point.mixinMethodAccess),
                point.mixinMethodName, point.mixinMethodDesc, null, null);
        if (!point.targetStatic) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        Type[] args = Type.getArgumentTypes(point.mixinMethodDesc);
        int localIndex = bridgeStatic ? 0 : 1;
        for (Type arg : args) {
            method.instructions.add(this.getLoadInsn(arg, localIndex));
            localIndex += arg.getSize();
        }
        int opcode = this.invokerOpcode(point, classNode);
        method.instructions.add(new MethodInsnNode(opcode, targetInternal, point.targetMethodName, point.targetMethodDesc,
                opcode == Opcodes.INVOKEINTERFACE));
        method.instructions.add(returnInsn(Type.getReturnType(point.mixinMethodDesc)));
        method.maxLocals = bridgeLocalSlots(point.mixinMethodDesc, bridgeStatic);
        method.maxStack = 0;
        return method;
    }

    private boolean isInvokerDescriptorCompatible(InvokerPoint point) {
        Type bridgeType = Type.getMethodType(point.mixinMethodDesc);
        Type targetType = Type.getMethodType(point.targetMethodDesc);
        Type[] bridgeArgs = bridgeType.getArgumentTypes();
        Type[] targetArgs = targetType.getArgumentTypes();
        if (bridgeArgs.length != targetArgs.length) {
            return false;
        }
        for (int i = 0; i < bridgeArgs.length; i++) {
            if (!isSameStackType(bridgeArgs[i], targetArgs[i])) {
                return false;
            }
        }
        return isSameStackType(bridgeType.getReturnType(), targetType.getReturnType());
    }

    private int invokerOpcode(InvokerPoint point, ClassNode classNode) {
        if (point.targetStatic) {
            return Opcodes.INVOKESTATIC;
        }
        MethodNode target = this.findMethod(classNode, point.targetMethodName, point.targetMethodDesc);
        if (target != null && (target.access & Opcodes.ACC_PRIVATE) != 0) {
            return Opcodes.INVOKESPECIAL;
        }
        return (classNode.access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
    }

    private boolean applyMixinFields(ClassNode classNode, TransformerHolder holder, String mixinInternal,
                                     String actualClassName) {
        boolean changed = false;
        for (FieldNode field : holder.mixinFields) {
            FieldNode cloned = cloneMixinField(field, mixinInternal, actualClassName);
            if (this.hasField(classNode, cloned.name, cloned.desc)) {
                continue;
            }
            classNode.fields.add(cloned);
            changed = true;
        }
        return changed;
    }

    private static FieldNode cloneMixinField(FieldNode field, String mixinInternal, String targetInternal) {
        return new FieldNode(
                field.access,
                field.name,
                rewriteText(field.desc, mixinInternal, targetInternal),
                rewriteText(field.signature, mixinInternal, targetInternal),
                field.value);
    }

    private boolean applyMixinFieldInitializers(ClassNode classNode, TransformerHolder holder, String mixinInternal,
                                                String actualClassName) {
        if (holder.instanceFieldInitializers.isEmpty()) {
            return false;
        }
        boolean changed = false;
        SelfRewriteContext rewriteContext = new SelfRewriteContext(mixinInternal, actualClassName,
                holder.shadowFields, holder.shadowMethods);
        for (MethodNode method : classNode.methods) {
            if (!isConstructor(method)) {
                continue;
            }
            AbstractInsnNode initCall = findSuperConstructorInitCall(method, classNode);
            if (initCall == null) {
                continue;
            }
            InsnList initializer = new InsnList();
            for (InsnList mixinInitializer : holder.instanceFieldInitializers) {
                initializer.add(cloneInsnList(mixinInitializer));
            }
            this.rewriteSelfInstructions(initializer, rewriteContext);
            method.instructions.insert(initCall, initializer);
            changed = true;
        }
        return changed;
    }

    private boolean applyMixinMethods(ClassNode classNode, TransformerHolder holder, String mixinInternal,
                                      String actualClassName) throws IOException {
        ArrayList<MethodNode> methodsToAdd = new ArrayList<>();
        MixinCopyContext copyContext = new MixinCopyContext(classNode, holder, actualClassName, mixinInternal, methodsToAdd);
        for (MethodNode mixinMethod : holder.mixinMethods) {
            MethodNode cloned = holder.cloneMethod(mixinMethod);
            this.rewriteSelfReferences(cloned, copyContext.selfRewriteContext());
            cloned.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
            cloned.visibleAnnotations = null;
            cloned.invisibleAnnotations = null;
            cloned.visibleParameterAnnotations = null;
            cloned.invisibleParameterAnnotations = null;
            if (this.hasMethod(classNode, methodsToAdd, cloned.name, cloned.desc)) {
                continue;
            }
            methodsToAdd.add(cloned);
            this.copyLambdaMethods(holder.mixinClassName, mixinMethod.name, copyContext);
        }
        appendPendingMethods(classNode, methodsToAdd);
        return !methodsToAdd.isEmpty();
    }

    private boolean applyRedirectToMethod(MethodNode method, RedirectPoint point, String mixinInternal) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode methodInsn && this.rewriteRedirectCall(methodInsn, point, mixinInternal)) {
                changed = true;
            }
        }
        return changed;
    }

    private boolean rewriteRedirectCall(MethodInsnNode methodInsn, RedirectPoint point, String mixinInternal) {
        for (TargetCall targetCall : point.targetCalls) {
            if (!targetCall.matches(methodInsn.owner, methodInsn.name, methodInsn.desc)) {
                continue;
            }
            methodInsn.setOpcode(Opcodes.INVOKESTATIC);
            methodInsn.owner = mixinInternal;
            methodInsn.name = point.mixinMethodName;
            methodInsn.desc = point.mixinMethodDesc;
            methodInsn.itf = false;
            return true;
        }
        return false;
    }

    private boolean applyModifyConstantToMethod(MethodNode method, ModifyConstantPoint point,
                                                InjectContext context) throws NoSuchMethodException, IOException {
        ValueHandlerTypes handlerTypes = this.validateUnaryValueHandler("@ModifyConstant",
                point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc);
        if (handlerTypes == null) {
            return false;
        }
        Type requestedType = this.optionalType(point.constantTypeDesc);
        if (requestedType != null && !isSameStackValueType(handlerTypes.argumentType, requestedType)) {
            this.reportInvalidHandler("@ModifyConstant", point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc,
                    "handler argument does not match requested constant type " + requestedType.getDescriptor());
            return false;
        }

        String mixinInternal = point.mixinClassName.replace('.', '/');
        InjectHandlerCall handlerCall = this.ensureTransformHandler("@ModifyConstant", "modifyConstant",
                point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc, point.mixinMethodStatic,
                context, mixinInternal, (method.access & Opcodes.ACC_STATIC) == 0);
        if (handlerCall == null) {
            return false;
        }

        ArrayList<ConstantInstruction> matches = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            ConstantInstruction constant = this.constantInstruction(insn);
            if (constant != null && this.matchesModifyConstant(constant, point, handlerTypes.argumentType, requestedType)) {
                matches.add(constant);
            }
        }
        List<ConstantInstruction> selected = this.selectOrdinal(matches, point.ordinal);
        for (ConstantInstruction constant : selected) {
            Type outputType = requestedType != null ? requestedType : constant.type;
            method.instructions.insert(constant.node, this.valueHandlerInvocation(method, handlerCall, handlerTypes, outputType));
        }
        return !selected.isEmpty();
    }

    private boolean applyModifyVariableToMethod(MethodNode method, ModifyVariablePoint point,
                                                InjectContext context) throws NoSuchMethodException, IOException {
        if (!point.load && !point.store) {
            return false;
        }
        ValueHandlerTypes handlerTypes = this.validateUnaryValueHandler("@ModifyVariable",
                point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc);
        if (handlerTypes == null) {
            return false;
        }
        Type requestedType = this.optionalType(point.variableTypeDesc);
        if (requestedType != null && !isSameStackValueType(handlerTypes.argumentType, requestedType)) {
            this.reportInvalidHandler("@ModifyVariable", point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc,
                    "handler argument does not match requested variable type " + requestedType.getDescriptor());
            return false;
        }

        String mixinInternal = point.mixinClassName.replace('.', '/');
        InjectHandlerCall handlerCall = this.ensureTransformHandler("@ModifyVariable", "modifyVariable",
                point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc, point.mixinMethodStatic,
                context, mixinInternal, (method.access & Opcodes.ACC_STATIC) == 0);
        if (handlerCall == null) {
            return false;
        }

        ArrayList<VariableInstruction> matches = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof VarInsnNode varInsn) {
                VariableInstruction variable = this.variableInstruction(varInsn);
                if (variable != null && this.matchesModifyVariable(variable, point, handlerTypes.argumentType, requestedType)) {
                    matches.add(variable);
                }
            }
        }
        List<VariableInstruction> selected = this.selectOrdinal(matches, point.ordinal);
        for (VariableInstruction variable : selected) {
            Type outputType = requestedType != null ? requestedType : variable.type;
            InsnList invoke = this.valueHandlerInvocation(method, handlerCall, handlerTypes, outputType);
            if (variable.store) {
                method.instructions.insertBefore(variable.node, invoke);
            } else {
                method.instructions.insert(variable.node, invoke);
            }
        }
        return !selected.isEmpty();
    }

    private boolean applyModifyArgsToMethod(MethodNode method, ModifyArgsPoint point,
                                            InjectContext context) throws NoSuchMethodException, IOException {
        if (!this.validateModifyArgsHandler(point)) {
            return false;
        }
        String mixinInternal = point.mixinClassName.replace('.', '/');
        InjectHandlerCall handlerCall = this.ensureTransformHandler("@ModifyArgs", "modifyArgs",
                point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc, point.mixinMethodStatic,
                context, mixinInternal, (method.access & Opcodes.ACC_STATIC) == 0);
        if (handlerCall == null) {
            return false;
        }

        ArrayList<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode methodInsn && this.matchesModifyArgsCall(methodInsn, point)) {
                matches.add(methodInsn);
            }
        }
        List<MethodInsnNode> selected = this.selectOrdinal(matches, point.ordinal);
        for (MethodInsnNode methodInsn : selected) {
            method.instructions.insertBefore(methodInsn, this.modifyArgsInvocation(method, methodInsn, handlerCall));
        }
        return !selected.isEmpty();
    }

    private boolean matchesModifyConstant(ConstantInstruction constant, ModifyConstantPoint point,
                                          Type handlerArgType, Type requestedType) {
        return matchesOpcode(constant.node.getOpcode(), point.opcode)
                && this.matchesConstantValue(constant.value, point.constantValue)
                && isConstantTypeCompatible(constant.value, constant.type, requestedType != null ? requestedType : handlerArgType);
    }

    private static boolean isConstantTypeCompatible(Object value, Type constantType, Type expectedType) {
        if (value == null && isReferenceType(expectedType)) {
            return true;
        }
        if (isIntLikeType(constantType) && isIntLikeType(expectedType)) {
            return true;
        }
        if (isReferenceType(constantType) && isReferenceType(expectedType)) {
            return "Ljava/lang/Object;".equals(expectedType.getDescriptor())
                    || constantType.getDescriptor().equals(expectedType.getDescriptor());
        }
        return constantType.getSort() == expectedType.getSort();
    }

    private boolean matchesModifyVariable(VariableInstruction variable, ModifyVariablePoint point,
                                          Type handlerArgType, Type requestedType) {
        if (!matchesOpcode(variable.node.getOpcode(), point.opcode)) {
            return false;
        }
        if (point.variableIndex >= 0 && point.variableIndex != variable.node.var) {
            return false;
        }
        if ((variable.load && !point.load) || (variable.store && !point.store)) {
            return false;
        }
        return isSameStackValueType(variable.type, requestedType != null ? requestedType : handlerArgType);
    }

    private boolean matchesModifyArgsCall(MethodInsnNode methodInsn, ModifyArgsPoint point) {
        if (!matchesOpcode(methodInsn.getOpcode(), point.opcode)) {
            return false;
        }
        for (TargetCall targetCall : point.targetCalls) {
            if (targetCall.matches(methodInsn.owner, methodInsn.name, methodInsn.desc)) {
                return true;
            }
        }
        return false;
    }

    private ConstantInstruction constantInstruction(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return switch (opcode) {
            case Opcodes.ACONST_NULL -> new ConstantInstruction(insn, null, Type.getType("Ljava/lang/Object;"));
            case Opcodes.ICONST_M1 -> new ConstantInstruction(insn, -1, Type.INT_TYPE);
            case Opcodes.ICONST_0 -> new ConstantInstruction(insn, 0, Type.INT_TYPE);
            case Opcodes.ICONST_1 -> new ConstantInstruction(insn, 1, Type.INT_TYPE);
            case Opcodes.ICONST_2 -> new ConstantInstruction(insn, 2, Type.INT_TYPE);
            case Opcodes.ICONST_3 -> new ConstantInstruction(insn, 3, Type.INT_TYPE);
            case Opcodes.ICONST_4 -> new ConstantInstruction(insn, 4, Type.INT_TYPE);
            case Opcodes.ICONST_5 -> new ConstantInstruction(insn, 5, Type.INT_TYPE);
            case Opcodes.LCONST_0 -> new ConstantInstruction(insn, 0L, Type.LONG_TYPE);
            case Opcodes.LCONST_1 -> new ConstantInstruction(insn, 1L, Type.LONG_TYPE);
            case Opcodes.FCONST_0 -> new ConstantInstruction(insn, 0.0F, Type.FLOAT_TYPE);
            case Opcodes.FCONST_1 -> new ConstantInstruction(insn, 1.0F, Type.FLOAT_TYPE);
            case Opcodes.FCONST_2 -> new ConstantInstruction(insn, 2.0F, Type.FLOAT_TYPE);
            case Opcodes.DCONST_0 -> new ConstantInstruction(insn, 0.0D, Type.DOUBLE_TYPE);
            case Opcodes.DCONST_1 -> new ConstantInstruction(insn, 1.0D, Type.DOUBLE_TYPE);
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> new ConstantInstruction(insn, ((IntInsnNode) insn).operand, Type.INT_TYPE);
            case Opcodes.LDC -> this.ldcConstantInstruction((LdcInsnNode) insn);
            default -> null;
        };
    }

    private ConstantInstruction ldcConstantInstruction(LdcInsnNode insn) {
        Object value = insn.cst;
        if (value instanceof Integer) {
            return new ConstantInstruction(insn, value, Type.INT_TYPE);
        }
        if (value instanceof Long) {
            return new ConstantInstruction(insn, value, Type.LONG_TYPE);
        }
        if (value instanceof Float) {
            return new ConstantInstruction(insn, value, Type.FLOAT_TYPE);
        }
        if (value instanceof Double) {
            return new ConstantInstruction(insn, value, Type.DOUBLE_TYPE);
        }
        if (value instanceof String) {
            return new ConstantInstruction(insn, value, Type.getType("Ljava/lang/String;"));
        }
        if (value instanceof Type) {
            return new ConstantInstruction(insn, value, Type.getType("Ljava/lang/Class;"));
        }
        return null;
    }

    private boolean matchesConstantValue(Object value, String expected) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (value == null) {
            return "null".equals(expected);
        }
        if (value instanceof Type type) {
            return expected.equals(type.getDescriptor())
                    || expected.equals(type.getInternalName())
                    || expected.equals(type.getClassName());
        }
        return expected.equals(String.valueOf(value));
    }

    private VariableInstruction variableInstruction(VarInsnNode varInsn) {
        int opcode = varInsn.getOpcode();
        return switch (opcode) {
            case Opcodes.ILOAD -> new VariableInstruction(varInsn, Type.INT_TYPE, true, false);
            case Opcodes.LLOAD -> new VariableInstruction(varInsn, Type.LONG_TYPE, true, false);
            case Opcodes.FLOAD -> new VariableInstruction(varInsn, Type.FLOAT_TYPE, true, false);
            case Opcodes.DLOAD -> new VariableInstruction(varInsn, Type.DOUBLE_TYPE, true, false);
            case Opcodes.ALOAD -> new VariableInstruction(varInsn, Type.getType("Ljava/lang/Object;"), true, false);
            case Opcodes.ISTORE -> new VariableInstruction(varInsn, Type.INT_TYPE, false, true);
            case Opcodes.LSTORE -> new VariableInstruction(varInsn, Type.LONG_TYPE, false, true);
            case Opcodes.FSTORE -> new VariableInstruction(varInsn, Type.FLOAT_TYPE, false, true);
            case Opcodes.DSTORE -> new VariableInstruction(varInsn, Type.DOUBLE_TYPE, false, true);
            case Opcodes.ASTORE -> new VariableInstruction(varInsn, Type.getType("Ljava/lang/Object;"), false, true);
            default -> null;
        };
    }

    private InsnList valueHandlerInvocation(MethodNode method, InjectHandlerCall handlerCall,
                                            ValueHandlerTypes handlerTypes, Type outputType) {
        InsnList invoke = new InsnList();
        if (handlerCall.needsReceiver) {
            int tempLocal = this.allocateLocal(method, handlerTypes.argumentType);
            invoke.add(this.getStoreInsn(handlerTypes.argumentType, tempLocal));
            invoke.add(new VarInsnNode(Opcodes.ALOAD, 0));
            invoke.add(this.getLoadInsn(handlerTypes.argumentType, tempLocal));
        }
        invoke.add(new MethodInsnNode(handlerCall.opcode, handlerCall.owner, handlerCall.name,
                handlerCall.desc, handlerCall.isInterface));
        this.appendReferenceCast(invoke, handlerTypes.returnType, outputType);
        return invoke;
    }

    private InsnList modifyArgsInvocation(MethodNode method, MethodInsnNode methodInsn, InjectHandlerCall handlerCall) {
        InsnList replacement = new InsnList();
        Type[] argTypes = Type.getArgumentTypes(methodInsn.desc);
        int[] argLocals = new int[argTypes.length];
        for (int i = argTypes.length - 1; i >= 0; i--) {
            argLocals[i] = this.allocateLocal(method, argTypes[i]);
            replacement.add(this.getStoreInsn(argTypes[i], argLocals[i]));
        }

        boolean invokeStatic = methodInsn.getOpcode() == Opcodes.INVOKESTATIC;
        int receiverLocal = -1;
        if (!invokeStatic) {
            receiverLocal = this.allocateLocal(method, Type.getType("Ljava/lang/Object;"));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        }

        int arrayLocal = this.allocateLocal(method, Type.getType("[Ljava/lang/Object;"));
        replacement.add(pushIntConstant(argTypes.length));
        replacement.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, arrayLocal));
        for (int i = 0; i < argTypes.length; i++) {
            replacement.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            replacement.add(pushIntConstant(i));
            replacement.add(this.getLoadInsn(argTypes[i], argLocals[i]));
            this.appendBox(replacement, argTypes[i]);
            replacement.add(new InsnNode(Opcodes.AASTORE));
        }

        int argsLocal = this.allocateLocal(method, Type.getObjectType(MixinConstants.ARGS_CLASS));
        replacement.add(new TypeInsnNode(Opcodes.NEW, MixinConstants.ARGS_CLASS));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MixinConstants.ARGS_CLASS,
                "<init>", "([Ljava/lang/Object;)V", false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, argsLocal));

        if (handlerCall.needsReceiver) {
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        replacement.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        replacement.add(new MethodInsnNode(handlerCall.opcode, handlerCall.owner, handlerCall.name,
                handlerCall.desc, handlerCall.isInterface));

        if (!invokeStatic) {
            replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        }
        for (int i = 0; i < argTypes.length; i++) {
            replacement.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
            replacement.add(pushIntConstant(i));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MixinConstants.ARGS_CLASS,
                    "get", "(I)Ljava/lang/Object;", false));
            this.appendUnboxOrCast(replacement, argTypes[i]);
        }
        return replacement;
    }

    private InjectHandlerCall ensureTransformHandler(String annotationName, String prefix,
                                                     String mixinClassName, String mixinMethodName,
                                                     String mixinMethodDesc, boolean mixinMethodStatic,
                                                     InjectContext context, String mixinInternal,
                                                     boolean targetIsInstance) throws NoSuchMethodException, IOException {
        if (!mixinMethodStatic && !targetIsInstance) {
            this.reportInvalidHandler(annotationName, mixinClassName, mixinMethodName, mixinMethodDesc,
                    "non-static handler cannot be called from a static target method");
            return null;
        }

        String key = prefix + '\n' + mixinClassName + '\n' + mixinMethodName + mixinMethodDesc + '\n' + mixinMethodStatic;
        InjectHandlerCall existing = context.injectionState.handlerCalls.get(key);
        if (existing != null) {
            return existing;
        }

        MethodNode mixinMethod = context.holder.rewrittenMethodCache.get(mixinMethodName + mixinMethodDesc);
        if (mixinMethod == null) {
            throw new NoSuchMethodException("Cannot find " + annotationName + " handler: " + mixinMethodName + mixinMethodDesc);
        }

        MethodNode handler = context.holder.cloneMethod(mixinMethod);
        this.rewriteSelfReferences(handler, new SelfRewriteContext(mixinInternal, context.actualClassName,
                context.holder.shadowFields, context.holder.shadowMethods));
        handler.name = this.uniqueTransformHandlerName(prefix, mixinClassName, mixinMethodName, mixinMethodDesc,
                handler.desc, context.classNode, context.injectionState.methodsToAdd);
        handler.access = handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        handler.access = (handler.access | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC);
        if (mixinMethodStatic) {
            handler.access |= Opcodes.ACC_STATIC;
        } else {
            handler.access &= ~Opcodes.ACC_STATIC;
        }
        handler.visibleAnnotations = null;
        handler.invisibleAnnotations = null;
        handler.visibleParameterAnnotations = null;
        handler.invisibleParameterAnnotations = null;
        context.injectionState.methodsToAdd.add(handler);
        this.copyLambdaMethods(mixinClassName, mixinMethodName,
                new MixinCopyContext(context.classNode, context.holder, context.actualClassName, mixinInternal,
                        context.injectionState.methodsToAdd));

        InjectHandlerCall call = new InjectHandlerCall(
                mixinMethodStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                context.actualClassName,
                handler.name,
                handler.desc,
                (context.classNode.access & Opcodes.ACC_INTERFACE) != 0,
                !mixinMethodStatic);
        putValue(context.injectionState.handlerCalls, key, call);
        return call;
    }

    private String uniqueTransformHandlerName(String prefix, String mixinClassName, String mixinMethodName,
                                              String mixinMethodDesc, String methodDesc, ClassNode targetClass,
                                              List<MethodNode> methodsToAdd) {
        String baseName = "sera$" + prefix + "$" + sanitizeMethodName(mixinMethodName) + "$"
                + Integer.toHexString(Objects.hash(prefix, mixinClassName, mixinMethodName, mixinMethodDesc));
        String candidate = baseName;
        int suffix = 0;
        while (this.hasMethod(targetClass, methodsToAdd, candidate, methodDesc)) {
            suffix++;
            candidate = baseName + "$" + suffix;
        }
        return candidate;
    }

    private ValueHandlerTypes validateUnaryValueHandler(String annotationName, String mixinClassName,
                                                        String methodName, String methodDesc) {
        Type methodType = Type.getMethodType(methodDesc);
        Type[] args = methodType.getArgumentTypes();
        Type returnType = methodType.getReturnType();
        if (args.length != 1) {
            this.reportInvalidHandler(annotationName, mixinClassName, methodName, methodDesc,
                    "handler must accept exactly one argument");
            return null;
        }
        if (returnType.getSort() == Type.VOID) {
            this.reportInvalidHandler(annotationName, mixinClassName, methodName, methodDesc,
                    "handler must return the replacement value");
            return null;
        }
        if (!isSameStackValueType(args[0], returnType)) {
            this.reportInvalidHandler(annotationName, mixinClassName, methodName, methodDesc,
                    "handler argument and return types must use the same JVM stack type");
            return null;
        }
        return new ValueHandlerTypes(args[0], returnType);
    }

    private boolean validateModifyArgsHandler(ModifyArgsPoint point) {
        Type methodType = Type.getMethodType(point.mixinMethodDesc);
        Type[] args = methodType.getArgumentTypes();
        Type returnType = methodType.getReturnType();
        if (args.length == 1
                && args[0].getSort() == Type.OBJECT
                && MixinConstants.ARGS_CLASS.equals(args[0].getInternalName())
                && returnType.getSort() == Type.VOID) {
            return true;
        }
        this.reportInvalidHandler("@ModifyArgs", point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc,
                "handler must have descriptor (L" + MixinConstants.ARGS_CLASS + ";)V");
        return false;
    }

    private void reportInvalidHandler(String annotationName, String mixinClassName, String methodName,
                                      String methodDesc, String reason) {
        System.err.println("[SeraMixin] Invalid " + annotationName + " handler "
                + mixinClassName + "." + methodName + methodDesc + ": " + reason);
    }

    private static void appendPendingMethods(ClassNode classNode, List<MethodNode> methodsToAdd) {
        if (!methodsToAdd.isEmpty()) {
            classNode.methods.addAll(methodsToAdd);
        }
    }

    private void applyOverwrite(MethodNode target, OverwritePoint point, MixinCopyContext context) throws IOException, NoSuchMethodException {
        MethodNode mixinMethod = context.holder.rewrittenMethodCache.get(point.mixinMethodName + point.mixinMethodDesc);
        if (mixinMethod == null) {
            throw new NoSuchMethodException("Cannot find overwrite method: " + point.mixinMethodName + point.mixinMethodDesc);
        }

        MethodNode cloned = context.holder.cloneMethod(mixinMethod);
        this.rewriteSelfReferences(cloned, context.selfRewriteContext());
        target.instructions = cloned.instructions;
        target.tryCatchBlocks = cloned.tryCatchBlocks;
        target.localVariables = cloned.localVariables;
        target.maxStack = Math.max(cloned.maxStack, 0);
        target.maxLocals = Math.max(cloned.maxLocals, 0);
        this.copyLambdaMethods(point.mixinClassName, point.mixinMethodName, context);
    }

    private void copyLambdaMethods(String mixinClassName, String methodName, MixinCopyContext context) throws IOException {
        byte[] mixinBytes = this.classProvider.loadMixinBytes(mixinClassName, context.holder.mixinClassLoader);
        ClassNode mixinClass = new ClassNode();
        acceptExpandedClass(mixinBytes, mixinClass);
        String dotPrefix = "lambda." + methodName + ".";
        String dollarPrefix = "lambda$" + methodName + "$";

        for (MethodNode mixinMethod : mixinClass.methods) {
            boolean lambdaName = mixinMethod.name.startsWith(dotPrefix) || mixinMethod.name.startsWith(dollarPrefix);
            boolean exists = this.hasMethod(context.targetClass, context.methodsToAdd, mixinMethod.name, mixinMethod.desc);
            if (!lambdaName || exists) {
                continue;
            }
            MethodNode clonedLambda = context.holder.cloneMethod(mixinMethod);
            this.rewriteSelfReferences(clonedLambda, context.selfRewriteContext());
            clonedLambda.desc = clonedLambda.desc.replace(context.mixinInternal, context.targetInternal);
            clonedLambda.access = (clonedLambda.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;
            context.methodsToAdd.add(clonedLambda);
        }
    }

    /**
     * Rewrites copied mixin bytecode so references to the mixin owner become
     * references to the class currently being transformed.
     */
    private void rewriteSelfReferences(MethodNode method, SelfRewriteContext context) {
        method.desc = rewriteText(method.desc, context.mixinInternal, context.targetInternal);
        method.signature = rewriteText(method.signature, context.mixinInternal, context.targetInternal);
        if (method.exceptions != null) {
            for (int i = 0; i < method.exceptions.size(); i++) {
                method.exceptions.set(i, rewriteText(method.exceptions.get(i), context.mixinInternal, context.targetInternal));
            }
        }

        this.rewriteSelfInstructions(method.instructions, context);
        this.rewriteLocalVariables(method, context.mixinInternal, context.targetInternal);
    }

    private void rewriteSelfInstructions(InsnList instructions, SelfRewriteContext context) {
        if (instructions == null) {
            return;
        }
        for (AbstractInsnNode node : instructions.toArray()) {
            this.rewriteSelfInstruction(instructions, node, context);
        }
    }

    private void rewriteSelfInstruction(InsnList instructions, AbstractInsnNode node, SelfRewriteContext context) {
        if (node instanceof FieldInsnNode field) {
            rewriteSelfFieldReference(field, context);
            return;
        }
        if (node instanceof MethodInsnNode methodCall) {
            this.rewriteSelfMethodReference(instructions, methodCall, context);
            return;
        }
        if (node instanceof TypeInsnNode typeInsn) {
            rewriteSelfTypeReference(typeInsn, context.mixinInternal, context.targetInternal);
            return;
        }
        if (node instanceof InvokeDynamicInsnNode indy) {
            this.rewriteInvokeDynamic(instructions, indy, context.mixinInternal, context.targetInternal);
        }
    }

    private static void rewriteSelfFieldReference(FieldInsnNode field, SelfRewriteContext context) {
        if (field.owner.equals(context.mixinInternal)) {
            field.owner = context.targetInternal;
        }
        field.desc = rewriteText(field.desc, context.mixinInternal, context.targetInternal);
        ShadowFieldInfo shadowField = context.shadowFields.get(field.name);
        if (shadowField != null) {
            field.name = shadowField.targetFieldName;
            field.desc = shadowField.desc;
            field.owner = context.targetInternal;
        }
    }

    private void rewriteSelfMethodReference(InsnList instructions, MethodInsnNode methodCall, SelfRewriteContext context) {
        String originalDesc = methodCall.desc;
        methodCall.desc = rewriteText(methodCall.desc, context.mixinInternal, context.targetInternal);
        if (!methodCall.owner.equals(context.mixinInternal)) {
            return;
        }
        ShadowMethodInfo shadowMethod = context.shadowMethods.get(methodCall.name + originalDesc);
        if (shadowMethod != null) {
            this.rewriteShadowMethodCall(instructions, methodCall, shadowMethod);
        } else if (isLambdaMethodName(methodCall.name) && methodCall.getOpcode() != Opcodes.INVOKESTATIC) {
            methodCall.setOpcode(Opcodes.INVOKEVIRTUAL);
        }
        methodCall.owner = context.targetInternal;
    }

    private void rewriteShadowMethodCall(InsnList instructions, MethodInsnNode methodCall, ShadowMethodInfo shadowMethod) {
        methodCall.name = shadowMethod.targetMethodName;
        methodCall.desc = shadowMethod.desc;
        if (methodCall.getOpcode() == Opcodes.INVOKESTATIC) {
            methodCall.setOpcode(Opcodes.INVOKEVIRTUAL);
            instructions.insertBefore(methodCall, new VarInsnNode(Opcodes.ALOAD, 0));
        }
    }

    private static void rewriteSelfTypeReference(TypeInsnNode typeInsn, String mixinInternal, String targetInternal) {
        typeInsn.desc = rewriteText(typeInsn.desc, mixinInternal, targetInternal);
    }

    private static void rewriteLocalVariables(MethodNode method, String mixinInternal, String targetInternal) {
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                if (local.desc != null && local.desc.contains(mixinInternal)) {
                    local.desc = local.desc.replace(mixinInternal, targetInternal);
                }
                if (local.signature != null) {
                    local.signature = local.signature.replace(mixinInternal, targetInternal);
                }
            }
        }
    }

    private void rewriteInvokeDynamic(InsnList instructions, InvokeDynamicInsnNode indy, String mixinInternal, String targetInternal) {
        String newDesc = indy.desc.replace(mixinInternal, targetInternal);
        Object[] newBsmArgs = new Object[indy.bsmArgs.length];
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            newBsmArgs[i] = this.rewriteBootstrapArg(indy.bsmArgs[i], mixinInternal, targetInternal);
        }
        instructions.set(indy, new InvokeDynamicInsnNode(indy.name, newDesc, indy.bsm, newBsmArgs));
    }

    private Object rewriteBootstrapArg(Object arg, String mixinInternal, String targetInternal) {
        if (arg instanceof Type type) {
            return rewriteBootstrapType(type, mixinInternal, targetInternal);
        }
        if (arg instanceof Handle handle && handle.getOwner().equals(mixinInternal)) {
            return rewriteBootstrapHandle(handle, mixinInternal, targetInternal);
        }
        return arg;
    }

    private static Object rewriteBootstrapType(Type type, String mixinInternal, String targetInternal) {
        String oldDesc = type.getDescriptor();
        String rewritten = oldDesc.replace(mixinInternal, targetInternal);
        if (oldDesc.equals(rewritten)) {
            return type;
        }
        return type.getSort() == Type.METHOD ? Type.getMethodType(rewritten) : Type.getType(rewritten);
    }

    private static Handle rewriteBootstrapHandle(Handle handle, String mixinInternal, String targetInternal) {
        return new Handle(
                remappedHandleTag(handle),
                targetInternal,
                handle.getName(),
                handle.getDesc().replace(mixinInternal, targetInternal),
                handle.isInterface());
    }

    private static int remappedHandleTag(Handle handle) {
        int tag = handle.getTag();
        if (tag == Opcodes.H_NEWINVOKESPECIAL && isLambdaMethodName(handle.getName())) {
            return Opcodes.H_INVOKEVIRTUAL;
        }
        return tag;
    }

    private static boolean isLambdaMethodName(String name) {
        return name.startsWith("lambda.") || name.startsWith("lambda$");
    }

    private boolean applyMixinInject(MethodNode target, InjectPoint point, InjectContext context) throws NoSuchMethodException, IOException {
        List<ResolvedInjectionPoint> insertionPoints = this.resolveInjectionPoints(target, point, context.classNode);
        if (insertionPoints.isEmpty()) {
            this.reportMissingInjectionPoint(target, point);
            return false;
        }
        Type targetType = Type.getMethodType(target.desc);
        Type[] targetArgs = targetType.getArgumentTypes();
        boolean targetIsInstance = (target.access & Opcodes.ACC_STATIC) == 0;
        String mixinInternal = point.mixinClassName.replace('.', '/');
        InjectHandlerCall handlerCall = this.resolveInjectHandler(point, context, mixinInternal, targetIsInstance);
        Type mixinMethodType = Type.getMethodType(handlerCall.desc);
        Type[] mixinArgTypes = mixinMethodType.getArgumentTypes();
        boolean injectThis = this.shouldInjectThis(targetIsInstance, mixinArgTypes, context);

        InjectionBuildState buildState = new InjectionBuildState(target, targetIsInstance);
        this.appendReceiverArgument(buildState.inject, handlerCall);
        this.appendThisArgument(buildState, injectThis);
        this.appendMixinArguments(buildState, targetArgs, mixinArgTypes);
        buildState.inject.add(new MethodInsnNode(handlerCall.opcode, handlerCall.owner, handlerCall.name, handlerCall.desc, handlerCall.isInterface));
        this.appendCallbackReturnGuard(buildState, targetType.getReturnType());
        this.insertResolvedInjectInstructions(target, insertionPoints, buildState.inject);

        target.maxLocals = Math.max(target.maxLocals, buildState.baseLocalSlots);
        target.maxStack = Math.max(target.maxStack, 0);
        return true;
    }

    private InjectHandlerCall resolveInjectHandler(InjectPoint point, InjectContext context, String mixinInternal,
                                                   boolean targetIsInstance) throws NoSuchMethodException, IOException {
        if (point.mixinMethodStatic) {
            return new InjectHandlerCall(Opcodes.INVOKESTATIC, mixinInternal, point.mixinMethodName, point.mixinMethodDesc, false, false);
        }
        return this.ensureInjectHandlerMethod(point, context, mixinInternal, targetIsInstance);
    }

    private boolean shouldInjectThis(boolean targetIsInstance, Type[] mixinArgTypes, InjectContext context) {
        if (!targetIsInstance || mixinArgTypes.length == 0 || mixinArgTypes[0].getSort() != Type.OBJECT) {
            return false;
        }
        String firstArg = mixinArgTypes[0].getInternalName();
        return firstArg.equals(context.holder.targetInternalName)
                || this.hierarchyResolver.isSubclassASM(context.actualClassName, firstArg, context.loader);
    }

    private void appendReceiverArgument(InsnList inject, InjectHandlerCall handlerCall) {
        if (handlerCall.needsReceiver) {
            inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
    }

    private void appendThisArgument(InjectionBuildState buildState, boolean injectThis) {
        if (injectThis) {
            buildState.inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
            buildState.mixinArgIndex++;
        }
    }

    private void appendMixinArguments(InjectionBuildState buildState, Type[] targetArgs, Type[] mixinArgTypes) {
        while (buildState.mixinArgIndex < mixinArgTypes.length) {
            this.appendMixinArgument(buildState, targetArgs, mixinArgTypes[buildState.mixinArgIndex]);
            buildState.mixinArgIndex++;
        }
    }

    private void appendMixinArgument(InjectionBuildState buildState, Type[] targetArgs, Type mixinArg) {
        String argInternal = mixinArg.getSort() == Type.OBJECT ? mixinArg.getInternalName() : "";
        if (MixinConstants.CALL_BACK_INFO.equals(argInternal)) {
            this.appendCallbackArgument(buildState);
            return;
        }
        if (buildState.targetArgIndex < targetArgs.length) {
            this.appendTargetArgument(buildState, targetArgs);
            return;
        }
        buildState.inject.add(this.getDefaultInsn(mixinArg));
    }

    private void appendCallbackArgument(InjectionBuildState buildState) {
        buildState.inject.add(new TypeInsnNode(Opcodes.NEW, MixinConstants.CALL_BACK_INFO));
        buildState.inject.add(new InsnNode(Opcodes.DUP));
        buildState.inject.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MixinConstants.CALL_BACK_INFO, "<init>", "()V", false));
        buildState.callbackVar = buildState.baseLocalSlots;
        buildState.baseLocalSlots++;
        buildState.inject.add(new VarInsnNode(Opcodes.ASTORE, buildState.callbackVar));
        buildState.inject.add(new VarInsnNode(Opcodes.ALOAD, buildState.callbackVar));
        buildState.hasCallback = true;
    }

    private void appendTargetArgument(InjectionBuildState buildState, Type[] targetArgs) {
        Type targetArg = targetArgs[buildState.targetArgIndex];
        buildState.inject.add(this.getLoadInsn(targetArg, buildState.localIndex));
        buildState.localIndex += targetArg.getSize();
        buildState.targetArgIndex++;
    }

    private void appendCallbackReturnGuard(InjectionBuildState buildState, Type returnType) {
        if (!buildState.hasCallback) {
            return;
        }
        LabelNode continueLabel = new LabelNode();
        buildState.inject.add(new VarInsnNode(Opcodes.ALOAD, buildState.callbackVar));
        buildState.inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MixinConstants.CALL_BACK_INFO, "isBack", "()Z", false));
        buildState.inject.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
        this.appendCallbackReturnValue(buildState.inject, buildState.callbackVar, returnType);
        buildState.inject.add(continueLabel);
    }

    private void appendCallbackReturnValue(InsnList inject, int callbackVar, Type returnType) {
        if (returnType.getSort() == Type.VOID) {
            inject.add(new InsnNode(Opcodes.RETURN));
            return;
        }
        inject.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
        inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MixinConstants.CALL_BACK_INFO, "getBackValue", "()Ljava/lang/Object;", false));
        inject.add(this.unboxAndReturn(returnType));
    }

    private List<ResolvedInjectionPoint> resolveInjectionPoints(MethodNode target, InjectPoint point, ClassNode classNode) {
        if (target.instructions == null) {
            return List.of();
        }
        return switch (point.position) {
            case NONE -> List.of();
            case HEAD -> this.resolveHeadPoint(target, point, classNode);
            case TAIL -> this.resolveReturnPoints(target, point, true);
            case RETURN, LAST -> this.resolveReturnPoints(target, point, false);
            case INVOKE -> this.resolveInvokePoints(target, point);
            case FIELD -> this.resolveFieldPoints(target, point);
            case NEW -> this.resolveNewPoints(target, point);
            case JUMP -> this.resolveJumpPoints(target, point);
            case CUSTOM, STR -> this.resolveCustomPoint(target, point, classNode);
        };
    }

    private List<ResolvedInjectionPoint> resolveHeadPoint(MethodNode target, InjectPoint point, ClassNode classNode) {
        if (target.instructions.size() == 0) {
            return List.of(new ResolvedInjectionPoint(null, false));
        }
        if (isConstructor(target) && point.selector.shift == InsertShift.DEFAULT && point.selector.by == 0) {
            AbstractInsnNode initCall = findConstructorInitCall(target, classNode);
            if (initCall != null) {
                return List.of(new ResolvedInjectionPoint(initCall, false));
            }
        }
        AbstractInsnNode first = firstRealInstruction(target);
        if (first == null) {
            return List.of(new ResolvedInjectionPoint(null, false));
        }
        ResolvedInjectionPoint shifted = this.shiftedInjectionPoint(first, point, true);
        return shifted == null ? List.of() : List.of(shifted);
    }

    private List<ResolvedInjectionPoint> resolveCustomPoint(MethodNode target, InjectPoint point, ClassNode classNode) {
        if (point.selector.index < 0) {
            return this.resolveHeadPoint(target, point, classNode);
        }
        AbstractInsnNode indexed = realInstructionAt(target, point.selector.index);
        if (indexed == null) {
            return List.of();
        }
        ResolvedInjectionPoint shifted = this.shiftedInjectionPoint(indexed, point, point.position.isBefore());
        return shifted == null ? List.of() : List.of(shifted);
    }

    private List<ResolvedInjectionPoint> resolveReturnPoints(MethodNode target, InjectPoint point, boolean tailOnly) {
        ArrayList<AbstractInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : target.instructions.toArray()) {
            int opcode = node.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN && matchesOpcode(opcode, point.selector)) {
                matches.add(node);
            }
        }
        List<AbstractInsnNode> selected = this.selectOrdinal(matches, point.selector.ordinal);
        if (tailOnly && point.selector.ordinal < 0 && !selected.isEmpty()) {
            selected = List.of(selected.get(selected.size() - 1));
        }
        return this.toInsertionPoints(selected, point, true);
    }

    private List<ResolvedInjectionPoint> resolveInvokePoints(MethodNode target, InjectPoint point) {
        ArrayList<AbstractInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : target.instructions.toArray()) {
            if (node instanceof MethodInsnNode methodInsn && this.matchesInvoke(methodInsn, point.selector)) {
                matches.add(node);
            }
        }
        return this.toInsertionPoints(this.selectOrdinal(matches, point.selector.ordinal), point, point.position.isBefore());
    }

    private List<ResolvedInjectionPoint> resolveFieldPoints(MethodNode target, InjectPoint point) {
        ArrayList<AbstractInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : target.instructions.toArray()) {
            if (node instanceof FieldInsnNode fieldInsn && this.matchesField(fieldInsn, point.selector)) {
                matches.add(node);
            }
        }
        return this.toInsertionPoints(this.selectOrdinal(matches, point.selector.ordinal), point, point.position.isBefore());
    }

    private List<ResolvedInjectionPoint> resolveNewPoints(MethodNode target, InjectPoint point) {
        ArrayList<AbstractInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : target.instructions.toArray()) {
            if (node instanceof TypeInsnNode typeInsn && this.matchesNew(typeInsn, point.selector)) {
                matches.add(node);
            }
        }
        return this.toInsertionPoints(this.selectOrdinal(matches, point.selector.ordinal), point, point.position.isBefore());
    }

    private List<ResolvedInjectionPoint> resolveJumpPoints(MethodNode target, InjectPoint point) {
        ArrayList<AbstractInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : target.instructions.toArray()) {
            if (node instanceof JumpInsnNode && matchesOpcode(node.getOpcode(), point.selector)) {
                matches.add(node);
            }
        }
        return this.toInsertionPoints(this.selectOrdinal(matches, point.selector.ordinal), point, point.position.isBefore());
    }

    private <T> List<T> selectOrdinal(List<T> matches, int ordinal) {
        if (ordinal < 0) {
            return matches;
        }
        if (ordinal >= matches.size()) {
            return List.of();
        }
        return List.of(matches.get(ordinal));
    }

    private List<ResolvedInjectionPoint> toInsertionPoints(List<AbstractInsnNode> anchors, InjectPoint point, boolean defaultBefore) {
        ArrayList<ResolvedInjectionPoint> insertionPoints = new ArrayList<>();
        for (AbstractInsnNode anchor : anchors) {
            ResolvedInjectionPoint shifted = this.shiftedInjectionPoint(anchor, point, defaultBefore);
            if (shifted != null) {
                insertionPoints.add(shifted);
            }
        }
        return insertionPoints;
    }

    private ResolvedInjectionPoint shiftedInjectionPoint(AbstractInsnNode anchor, InjectPoint point, boolean defaultBefore) {
        AbstractInsnNode shifted = shiftRealInstructions(anchor, point.selector.by);
        if (shifted == null) {
            return null;
        }
        boolean before = switch (point.selector.shift) {
            case DEFAULT -> defaultBefore;
            case BEFORE, BY -> true;
            case AFTER -> false;
        };
        return new ResolvedInjectionPoint(shifted, before);
    }

    private void insertResolvedInjectInstructions(MethodNode target, List<ResolvedInjectionPoint> insertionPoints, InsnList inject) {
        for (ResolvedInjectionPoint insertionPoint : insertionPoints) {
            InsnList copy = cloneInsnList(inject);
            if (insertionPoint.node == null) {
                target.instructions.add(copy);
            } else if (insertionPoint.before) {
                target.instructions.insertBefore(insertionPoint.node, copy);
            } else {
                target.instructions.insert(insertionPoint.node, copy);
            }
        }
    }

    private boolean matchesInvoke(MethodInsnNode methodInsn, InjectionSelector selector) {
        return matchesOpcode(methodInsn.getOpcode(), selector)
                && matchesText(selector.owner, methodInsn.owner)
                && matchesText(selector.name, methodInsn.name)
                && matchesText(selector.desc, methodInsn.desc);
    }

    private boolean matchesField(FieldInsnNode fieldInsn, InjectionSelector selector) {
        return matchesOpcode(fieldInsn.getOpcode(), selector)
                && matchesText(selector.owner, fieldInsn.owner)
                && matchesText(selector.name, fieldInsn.name)
                && matchesText(selector.desc, fieldInsn.desc);
    }

    private boolean matchesNew(TypeInsnNode typeInsn, InjectionSelector selector) {
        String targetType = !selector.owner.isEmpty() ? selector.owner : selector.name;
        if (targetType.isEmpty()) {
            targetType = internalNameFromObjectDescriptor(selector.desc);
        }
        return typeInsn.getOpcode() == Opcodes.NEW
                && matchesOpcode(typeInsn.getOpcode(), selector)
                && matchesText(targetType, typeInsn.desc);
    }

    private static boolean matchesOpcode(int opcode, InjectionSelector selector) {
        return selector.opcode < 0 || selector.opcode == opcode;
    }

    private static boolean matchesOpcode(int opcode, int expectedOpcode) {
        return expectedOpcode < 0 || expectedOpcode == opcode;
    }

    private static boolean matchesText(String expected, String actual) {
        return expected == null || expected.isEmpty() || expected.equals(actual);
    }

    private static String internalNameFromObjectDescriptor(String desc) {
        if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') {
            return "";
        }
        return desc.substring(1, desc.length() - 1);
    }

    private static AbstractInsnNode firstRealInstruction(MethodNode target) {
        for (AbstractInsnNode node = target.instructions.getFirst(); node != null; node = node.getNext()) {
            if (isRealInstruction(node)) {
                return node;
            }
        }
        return null;
    }

    private static AbstractInsnNode realInstructionAt(MethodNode target, int index) {
        int currentIndex = 0;
        for (AbstractInsnNode node = target.instructions.getFirst(); node != null; node = node.getNext()) {
            if (!isRealInstruction(node)) {
                continue;
            }
            if (currentIndex == index) {
                return node;
            }
            currentIndex++;
        }
        return null;
    }

    private static AbstractInsnNode shiftRealInstructions(AbstractInsnNode anchor, int by) {
        AbstractInsnNode current = anchor;
        int remaining = Math.abs(by);
        while (remaining > 0 && current != null) {
            current = by > 0 ? current.getNext() : current.getPrevious();
            if (current != null && isRealInstruction(current)) {
                remaining--;
            }
        }
        return current;
    }

    private static boolean isRealInstruction(AbstractInsnNode node) {
        return node != null && node.getOpcode() >= 0;
    }

    private void reportMissingInjectionPoint(MethodNode target, InjectPoint point) {
        System.err.println("[SeraMixin] @Inject point not found: " + point.position + " in "
                + target.name + target.desc + " for "
                + point.mixinClassName + "." + point.mixinMethodName + point.mixinMethodDesc);
    }

    private static boolean isConstructor(MethodNode target) {
        return "<init>".equals(target.name);
    }

    private static AbstractInsnNode findConstructorInitCall(MethodNode target, ClassNode classNode) {
        String self = classNode == null ? null : classNode.name;
        String superName = classNode == null ? null : classNode.superName;
        for (AbstractInsnNode node : target.instructions.toArray()) {
            if (node instanceof MethodInsnNode methodInsn
                    && methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(methodInsn.name)
                    && (methodInsn.owner.equals(self) || methodInsn.owner.equals(superName))) {
                return methodInsn;
            }
        }
        return null;
    }

    private static AbstractInsnNode findSuperConstructorInitCall(MethodNode target, ClassNode classNode) {
        String superName = classNode == null ? null : classNode.superName;
        for (AbstractInsnNode node : target.instructions.toArray()) {
            if (node instanceof MethodInsnNode methodInsn
                    && methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(methodInsn.name)
                    && methodInsn.owner.equals(superName)) {
                return methodInsn;
            }
        }
        return null;
    }

    private InjectHandlerCall ensureInjectHandlerMethod(InjectPoint point, InjectContext context, String mixinInternal,
                                                        boolean targetIsInstance) throws NoSuchMethodException, IOException {
        if (!targetIsInstance) {
            throw new IllegalStateException("@Inject handler must be static when target method is static: "
                    + point.mixinClassName + "." + point.mixinMethodName + point.mixinMethodDesc);
        }

        String key = point.mixinClassName + '\n' + point.mixinMethodName + point.mixinMethodDesc;
        InjectHandlerCall existing = context.injectionState.handlerCalls.get(key);
        if (existing != null) {
            return existing;
        }

        MethodNode mixinMethod = context.holder.rewrittenMethodCache.get(point.mixinMethodName + point.mixinMethodDesc);
        if (mixinMethod == null) {
            throw new NoSuchMethodException("Cannot find inject method: " + point.mixinMethodName + point.mixinMethodDesc);
        }

        MethodNode handler = context.holder.cloneMethod(mixinMethod);
        this.rewriteSelfReferences(handler, new SelfRewriteContext(mixinInternal, context.actualClassName,
                context.holder.shadowFields, context.holder.shadowMethods));
        handler.name = this.uniqueInjectHandlerName(point, handler.desc, context.classNode, context.injectionState.methodsToAdd);
        handler.access = (handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
                | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        handler.visibleAnnotations = null;
        handler.invisibleAnnotations = null;
        handler.visibleParameterAnnotations = null;
        handler.invisibleParameterAnnotations = null;
        context.injectionState.methodsToAdd.add(handler);
        this.copyLambdaMethods(point.mixinClassName, point.mixinMethodName,
                new MixinCopyContext(context.classNode, context.holder, context.actualClassName, mixinInternal,
                        context.injectionState.methodsToAdd));

        InjectHandlerCall call = new InjectHandlerCall(Opcodes.INVOKESPECIAL, context.actualClassName, handler.name,
                handler.desc, (context.classNode.access & Opcodes.ACC_INTERFACE) != 0, true);
        putValue(context.injectionState.handlerCalls, key, call);
        return call;
    }

    private String uniqueInjectHandlerName(InjectPoint point, String methodDesc, ClassNode targetClass, List<MethodNode> methodsToAdd) {
        String baseName = "sera$inject$" + sanitizeMethodName(point.mixinMethodName) + "$"
                + Integer.toHexString(Objects.hash(point.mixinClassName, point.mixinMethodName, point.mixinMethodDesc));
        String candidate = baseName;
        int suffix = 0;
        while (this.hasMethod(targetClass, methodsToAdd, candidate, methodDesc)) {
            suffix++;
            candidate = baseName + "$" + suffix;
        }
        return candidate;
    }

    private MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private boolean hasMethod(ClassNode classNode, List<MethodNode> pendingMethods, String name, String desc) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        for (MethodNode method : pendingMethods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static int sanitizeBridgeAccess(int access) {
        return (access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) | Opcodes.ACC_SYNTHETIC;
    }

    private static int bridgeLocalSlots(String methodDesc, boolean bridgeStatic) {
        int slots = bridgeStatic ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(methodDesc)) {
            slots += arg.getSize();
        }
        return slots;
    }

    private static AbstractInsnNode returnInsn(Type type) {
        return new InsnNode(type.getOpcode(Opcodes.IRETURN));
    }

    private static boolean isSameStackType(Type bridgeType, Type targetType) {
        if (bridgeType.getSort() == Type.VOID || targetType.getSort() == Type.VOID) {
            return bridgeType.getSort() == targetType.getSort();
        }
        if (isReferenceType(bridgeType) && isReferenceType(targetType)) {
            return true;
        }
        return bridgeType.getSort() == targetType.getSort();
    }

    private static boolean isSameStackValueType(Type first, Type second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getSort() == Type.VOID || second.getSort() == Type.VOID) {
            return first.getSort() == second.getSort();
        }
        if (isIntLikeType(first) && isIntLikeType(second)) {
            return true;
        }
        if (isReferenceType(first) && isReferenceType(second)) {
            return true;
        }
        return first.getSort() == second.getSort();
    }

    private static boolean isIntLikeType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> true;
            default -> false;
        };
    }

    private static boolean isReferenceType(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private Type optionalType(String desc) {
        if (desc == null || desc.isEmpty() || "V".equals(desc)) {
            return null;
        }
        return Type.getType(desc);
    }

    private static AbstractInsnNode pushIntConstant(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    private static String checkCastDescriptor(Type type) {
        return type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
    }

    private void reportInvalidBridge(String annotationName, String methodName, String methodDesc, String reason) {
        System.err.println("[SeraMixin] Invalid " + annotationName + " bridge "
                + methodName + methodDesc + ": " + reason);
    }

    private boolean hasField(ClassNode classNode, String name, String desc) {
        for (FieldNode field : classNode.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeMethodName(String methodName) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '_' || c == '$' ? c : '$');
        }
        return builder.length() == 0 || builder.charAt(0) == '<' ? "handler" : builder.toString();
    }

    private boolean applyReturnFields(ClassNode classNode, ClassLoader loader, List<ReturnFieldPoint> points) {
        if (points.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (method.instructions == null) {
                continue;
            }
            changed |= this.applyReturnFieldsToMethod(method, loader, points);
        }
        return changed;
    }

    private boolean applyReturnFieldsToMethod(MethodNode method, ClassLoader loader, List<ReturnFieldPoint> points) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode fieldInsn) {
                changed |= this.applyReturnFieldInstruction(method, fieldInsn, loader, points);
            }
        }
        return changed;
    }

    private boolean applyReturnFieldInstruction(MethodNode method, FieldInsnNode fieldInsn,
                                                ClassLoader loader, List<ReturnFieldPoint> points) {
        ReturnFieldPoint point = this.matchReturnField(fieldInsn, loader, points);
        if (point == null) {
            return false;
        }

        int opcode = fieldInsn.getOpcode();
        if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
            this.applyReturnFieldRead(method, fieldInsn, point);
            return true;
        }
        if (opcode == Opcodes.PUTFIELD) {
            this.applyReturnFieldInstanceWrite(method, fieldInsn, point);
            return true;
        }
        if (opcode == Opcodes.PUTSTATIC) {
            method.instructions.insertBefore(fieldInsn, this.invokeReturnFieldHandler(point));
            return true;
        }
        return false;
    }

    private void applyReturnFieldRead(MethodNode method, FieldInsnNode fieldInsn, ReturnFieldPoint point) {
        if (!point.isStatic) {
            method.instructions.insertBefore(fieldInsn, new InsnNode(Opcodes.DUP));
        }
        method.instructions.insert(fieldInsn, this.invokeReturnFieldHandler(point));
    }

    private void applyReturnFieldInstanceWrite(MethodNode method, FieldInsnNode fieldInsn, ReturnFieldPoint point) {
        Type fieldType = Type.getType(fieldInsn.desc);
        int tempLocal = Math.max(method.maxLocals, 0);
        method.maxLocals = tempLocal + fieldType.getSize();
        method.instructions.insertBefore(fieldInsn, this.returnFieldInstanceWriteReplacement(point, fieldType, tempLocal));
    }

    private InsnList returnFieldInstanceWriteReplacement(ReturnFieldPoint point, Type fieldType, int tempLocal) {
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ISTORE), tempLocal));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ILOAD), tempLocal));
        replacement.add(this.newReturnFieldHandlerCall(point));
        if (point.returnCastType != null) {
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, point.returnCastType));
        }
        return replacement;
    }

    private InsnList invokeReturnFieldHandler(ReturnFieldPoint point) {
        InsnList replacement = new InsnList();
        replacement.add(this.newReturnFieldHandlerCall(point));
        if (point.returnCastType != null) {
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, point.returnCastType));
        }
        return replacement;
    }

    private MethodInsnNode newReturnFieldHandlerCall(ReturnFieldPoint point) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC,
                point.handlerOwnerInternalName,
                point.handlerMethodName,
                point.handlerMethodDesc,
                false);
    }

    private ReturnFieldPoint matchReturnField(FieldInsnNode fieldInsn, ClassLoader loader, List<ReturnFieldPoint> points) {
        int opcode = fieldInsn.getOpcode();
        if (opcode != Opcodes.GETFIELD && opcode != Opcodes.GETSTATIC
                && opcode != Opcodes.PUTFIELD && opcode != Opcodes.PUTSTATIC) {
            return null;
        }
        boolean isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        boolean isRead = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;

        for (ReturnFieldPoint point : points) {
            if (point.isStatic != isStatic || (isRead && !point.read) || (!isRead && !point.write)) {
                continue;
            }
            if (!point.fieldName.equals(fieldInsn.name) || !point.fieldDesc.equals(fieldInsn.desc)) {
                continue;
            }
            if (point.ownerInternalName.equals(fieldInsn.owner)
                    || (!isStatic && this.hierarchyResolver.isSubclassASM(fieldInsn.owner, point.ownerInternalName, loader))) {
                return point;
            }
        }
        return null;
    }

    private AbstractInsnNode getLoadInsn(Type type, int index) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new VarInsnNode(Opcodes.ILOAD, index);
            case Type.LONG -> new VarInsnNode(Opcodes.LLOAD, index);
            case Type.FLOAT -> new VarInsnNode(Opcodes.FLOAD, index);
            case Type.DOUBLE -> new VarInsnNode(Opcodes.DLOAD, index);
            default -> new VarInsnNode(Opcodes.ALOAD, index);
        };
    }

    private AbstractInsnNode getStoreInsn(Type type, int index) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new VarInsnNode(Opcodes.ISTORE, index);
            case Type.LONG -> new VarInsnNode(Opcodes.LSTORE, index);
            case Type.FLOAT -> new VarInsnNode(Opcodes.FSTORE, index);
            case Type.DOUBLE -> new VarInsnNode(Opcodes.DSTORE, index);
            default -> new VarInsnNode(Opcodes.ASTORE, index);
        };
    }

    private int allocateLocal(MethodNode method, Type type) {
        int local = Math.max(method.maxLocals, 0);
        method.maxLocals = local + type.getSize();
        return local;
    }

    private AbstractInsnNode getDefaultInsn(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new InsnNode(Opcodes.ICONST_0);
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            default -> new InsnNode(Opcodes.ACONST_NULL);
        };
    }

    private void appendBox(InsnList list, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.CHAR -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.BYTE -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.SHORT -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.LONG -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.FLOAT -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.DOUBLE -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> {
            }
        }
    }

    private void appendUnboxOrCast(InsnList list, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.CHAR -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
            }
            case Type.BYTE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.SHORT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.INT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.LONG -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
            }
            case Type.FLOAT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.DOUBLE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
            }
            case Type.OBJECT -> {
                if (!"java/lang/Object".equals(type.getInternalName())) {
                    list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                }
            }
            case Type.ARRAY -> list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
            default -> {
            }
        }
    }

    private void appendReferenceCast(InsnList list, Type returnType, Type outputType) {
        if (outputType == null || !isReferenceType(outputType) || !isReferenceType(returnType)) {
            return;
        }
        if (outputType.getDescriptor().equals(returnType.getDescriptor())
                || "Ljava/lang/Object;".equals(outputType.getDescriptor())) {
            return;
        }
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, checkCastDescriptor(outputType)));
    }

    private InsnList unboxAndReturn(Type type) {
        InsnList list = new InsnList();
        switch (type.getSort()) {
            case Type.VOID -> {
                list.add(new InsnNode(Opcodes.POP));
                list.add(new InsnNode(Opcodes.RETURN));
            }
            case Type.BOOLEAN -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.CHAR -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.BYTE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.SHORT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.INT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                list.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
                list.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
                list.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
                list.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                list.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        return list;
    }

    private void insertBeforeReturns(MethodNode method, InsnList inject) {
        for (AbstractInsnNode node : method.instructions.toArray()) {
            int opcode = node.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                method.instructions.insertBefore(node, cloneInsnList(inject));
            }
        }
    }

    private static InsnList cloneInsnList(InsnList source) {
        InsnList copy = new InsnList();
        HashMap<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                putValue(labels, label, new LabelNode());
            }
        }
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            copy.add(insn.clone(labels));
        }
        return copy;
    }

    private static String rewriteText(String value, String mixinInternal, String targetInternal) {
        if (value == null || mixinInternal == null || targetInternal == null || !value.contains(mixinInternal)) {
            return value;
        }
        return value.replace(mixinInternal, targetInternal);
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

    static void fixMethodBounds(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            method.maxStack = Math.max(method.maxStack, 0);
            method.maxLocals = Math.max(method.maxLocals, 0);
            if (method.localVariables == null) {
                continue;
            }
            for (LocalVariableNode local : method.localVariables) {
                int requiredSize = local.index + 1;
                if ("J".equals(local.desc) || "D".equals(local.desc)) {
                    requiredSize = local.index + 2;
                }
                method.maxLocals = Math.max(method.maxLocals, requiredSize);
            }
        }
    }

    private record ResolvedInjectionPoint(AbstractInsnNode node, boolean before) {
    }

    private record ConstantInstruction(AbstractInsnNode node, Object value, Type type) {
    }

    private record VariableInstruction(VarInsnNode node, Type type, boolean load, boolean store) {
    }

    private record ValueHandlerTypes(Type argumentType, Type returnType) {
    }

    private static final class InjectionState {
        private final List<MethodNode> methodsToAdd;
        private final Map<String, InjectHandlerCall> handlerCalls = new HashMap<>();

        private InjectionState(List<MethodNode> methodsToAdd) {
            this.methodsToAdd = methodsToAdd;
        }
    }

    private static final class MixinCopyContext {
        private final ClassNode targetClass;
        private final TransformerHolder holder;
        private final String targetInternal;
        private final String mixinInternal;
        private final List<MethodNode> methodsToAdd;

        private MixinCopyContext(ClassNode targetClass, TransformerHolder holder, String targetInternal,
                                 String mixinInternal, List<MethodNode> methodsToAdd) {
            this.targetClass = targetClass;
            this.holder = holder;
            this.targetInternal = targetInternal;
            this.mixinInternal = mixinInternal;
            this.methodsToAdd = methodsToAdd;
        }

        private SelfRewriteContext selfRewriteContext() {
            return new SelfRewriteContext(this.mixinInternal, this.targetInternal, this.holder.shadowFields, this.holder.shadowMethods);
        }
    }

    private static final class SelfRewriteContext {
        private final String mixinInternal;
        private final String targetInternal;
        private final Map<String, ShadowFieldInfo> shadowFields;
        private final Map<String, ShadowMethodInfo> shadowMethods;

        private SelfRewriteContext(String mixinInternal, String targetInternal,
                                   Map<String, ShadowFieldInfo> shadowFields,
                                   Map<String, ShadowMethodInfo> shadowMethods) {
            this.mixinInternal = mixinInternal;
            this.targetInternal = targetInternal;
            this.shadowFields = shadowFields;
            this.shadowMethods = shadowMethods;
        }
    }

    private static final class InjectContext {
        private final TransformerHolder holder;
        private final ClassLoader loader;
        private final String actualClassName;
        private final ClassNode classNode;
        private final InjectionState injectionState;

        private InjectContext(TransformerHolder holder, ClassLoader loader, String actualClassName,
                              ClassNode classNode, InjectionState injectionState) {
            this.holder = holder;
            this.loader = loader;
            this.actualClassName = actualClassName;
            this.classNode = classNode;
            this.injectionState = injectionState;
        }
    }

    private static final class InjectionBuildState {
        private final InsnList inject = new InsnList();
        private int baseLocalSlots;
        private int localIndex;
        private int callbackVar = -1;
        private boolean hasCallback;
        private int targetArgIndex;
        private int mixinArgIndex;

        private InjectionBuildState(MethodNode target, boolean targetIsInstance) {
            this.baseLocalSlots = Type.getArgumentsAndReturnSizes(target.desc) >> 2;
            this.localIndex = targetIsInstance ? 1 : 0;
        }
    }
}
