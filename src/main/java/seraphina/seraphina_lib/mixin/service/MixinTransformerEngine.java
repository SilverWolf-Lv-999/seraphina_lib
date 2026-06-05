package seraphina.seraphina_lib.mixin.service;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class MixinTransformerEngine {
    private final Map<String, List<ClassInfo>> registeredMixins;
    private final Map<String, TransformerHolder> activeTransformers = new ConcurrentHashMap<>();
    private final MixinClassProvider classProvider;
    private final MixinHierarchyResolver hierarchyResolver;
    private final ThreadLocal<Boolean> isSubclassMode;
    private final ThreadLocal<Boolean> hasPrintedShadowHeader;
    private final ThreadLocal<String> currentTargetClass;
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
    }

    boolean applyClassNodeTransform(ClassNode classNode, String internalName, ClassLoader loader) {
        boolean changed = false;
        List<ClassInfo> directInfos = this.registeredMixins.get(internalName);
        if (directInfos != null && !directInfos.isEmpty()) {
            this.isSubclassMode.set(false);
            changed |= this.applyMixins(classNode, directInfos, loader, internalName, false);
        } else {
            String parentTarget = this.hierarchyResolver.findParentTarget(internalName, classNode, loader);
            if (parentTarget != null) {
                List<ClassInfo> parentInfos = this.registeredMixins.get(parentTarget);
                if (parentInfos != null && !parentInfos.isEmpty()) {
                    this.isSubclassMode.set(true);
                    changed |= this.applyMixins(classNode, parentInfos, loader, internalName, true);
                }
            }
        }

        if (this.applyReturnFields(classNode, loader, this.collectReturnFieldPoints(loader))) {
            changed = true;
        }
        return changed;
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

    private boolean applyMixins(ClassNode classNode, List<ClassInfo> infos, ClassLoader loader, String actualClassName, boolean isSubclass) {
        boolean changed = false;
        for (ClassInfo info : infos) {
            try {
                TransformerHolder holder = this.getOrCreateTransformerHolder(info, loader, "Failed to scan mixin: ");
                if (info.hook != null && !info.hook.shouldApplyMixin(classNode, holder.mixinClassNode)) {
                    continue;
                }
                changed |= this.applyTransform(classNode, holder, loader, actualClassName, isSubclass);
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
            TransformerHolder created = new TransformerHolder(this.classProvider, this.hierarchyResolver, info.mixinClassName,
                    info.targetInternalName, loader, this.hasPrintedShadowHeader, this.currentTargetClass, info.mixinClassLoader);
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

    private boolean applyTransform(ClassNode classNode, TransformerHolder holder, ClassLoader loader, String actualClassName, boolean isSubclass) throws Exception {
        boolean anyMatched = false;
        String mixinInternal = holder.mixinClassName.replace('.', '/');
        String targetInternal = holder.targetInternalName;
        ArrayList<MethodNode> lambdaMethodsToAdd = new ArrayList<>();
        ArrayList<MethodNode> injectHandlerMethodsToAdd = new ArrayList<>();
        HashMap<String, InjectHandlerCall> injectHandlerCalls = new HashMap<>();
        boolean hasPrintedMethodHeader = false;

        for (MethodNode method : classNode.methods) {
            for (OverwritePoint point : holder.overwritePoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                if (!hasPrintedMethodHeader && !isSubclass) {
                    hasPrintedMethodHeader = true;
                }
                this.applyOverwrite(method, point, holder, targetInternal, mixinInternal, classNode, lambdaMethodsToAdd);
                anyMatched = true;
            }
        }
        if (!lambdaMethodsToAdd.isEmpty()) {
            classNode.methods.addAll(lambdaMethodsToAdd);
        }

        for (MethodNode method : classNode.methods) {
            for (InjectPoint point : holder.injectPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                if (!hasPrintedMethodHeader && !isSubclass) {
                    hasPrintedMethodHeader = true;
                }
                anyMatched = true;
                this.applyMixinInject(method, point, holder, loader, actualClassName, classNode,
                        injectHandlerMethodsToAdd, injectHandlerCalls);
            }
        }
        if (!injectHandlerMethodsToAdd.isEmpty()) {
            classNode.methods.addAll(injectHandlerMethodsToAdd);
        }

        for (MethodNode method : classNode.methods) {
            for (RedirectPoint point : holder.redirectPoints) {
                if (!point.matches(method.name, method.desc)) {
                    continue;
                }
                if (!hasPrintedMethodHeader && !isSubclass) {
                    hasPrintedMethodHeader = true;
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (!(insn instanceof MethodInsnNode methodInsn)) {
                        continue;
                    }
                    for (TargetCall targetCall : point.targetCalls) {
                        if (!targetCall.matches(methodInsn.owner, methodInsn.name, methodInsn.desc)) {
                            continue;
                        }
                        methodInsn.setOpcode(Opcodes.INVOKESTATIC);
                        methodInsn.owner = mixinInternal;
                        methodInsn.name = point.mixinMethodName;
                        methodInsn.desc = point.mixinMethodDesc;
                        methodInsn.itf = false;
                        anyMatched = true;
                    }
                }
            }
        }
        return anyMatched;
    }

    private void applyOverwrite(MethodNode target, OverwritePoint point, TransformerHolder holder, String targetInternal,
                                String mixinInternal, ClassNode targetClass, List<MethodNode> lambdaMethodsToAdd) throws IOException, NoSuchMethodException {
        MethodNode mixinMethod = holder.rewrittenMethodCache.get(point.mixinMethodName + point.mixinMethodDesc);
        if (mixinMethod == null) {
            throw new NoSuchMethodException("Cannot find overwrite method: " + point.mixinMethodName + point.mixinMethodDesc);
        }

        MethodNode cloned = holder.cloneMethod(mixinMethod);
        this.rewriteSelfReferences(cloned, mixinInternal, targetInternal, holder.shadowFields, holder.shadowMethods);
        target.instructions = cloned.instructions;
        target.tryCatchBlocks = cloned.tryCatchBlocks;
        target.localVariables = cloned.localVariables;
        target.maxStack = Math.max(cloned.maxStack, 0);
        target.maxLocals = Math.max(cloned.maxLocals, 0);
        this.copyLambdaMethods(point.mixinClassName, point.mixinMethodName, targetClass, holder, targetInternal, mixinInternal, lambdaMethodsToAdd);
    }

    private void copyLambdaMethods(String mixinClassName, String methodName, ClassNode targetClass, TransformerHolder holder,
                                   String targetInternal, String mixinInternal, List<MethodNode> lambdaMethodsToAdd) throws IOException {
        byte[] mixinBytes = this.classProvider.loadMixinBytes(mixinClassName, holder.mixinClassLoader);
        ClassNode mixinClass = new ClassNode();
        new ClassReader(mixinBytes).accept(mixinClass, ClassReader.EXPAND_FRAMES);
        String dotPrefix = "lambda." + methodName + ".";
        String dollarPrefix = "lambda$" + methodName + "$";

        for (MethodNode mixinMethod : mixinClass.methods) {
            boolean lambdaName = mixinMethod.name.startsWith(dotPrefix) || mixinMethod.name.startsWith(dollarPrefix);
            boolean exists = this.hasMethod(targetClass, lambdaMethodsToAdd, mixinMethod.name, mixinMethod.desc);
            if (!lambdaName || exists) {
                continue;
            }
            MethodNode clonedLambda = holder.cloneMethod(mixinMethod);
            this.rewriteSelfReferences(clonedLambda, mixinInternal, targetInternal, holder.shadowFields, holder.shadowMethods);
            clonedLambda.desc = clonedLambda.desc.replace(mixinInternal, targetInternal);
            clonedLambda.access = (clonedLambda.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;
            lambdaMethodsToAdd.add(clonedLambda);
        }
    }

    private void rewriteSelfReferences(MethodNode method, String mixinInternal, String targetInternal,
                                       Map<String, ShadowFieldInfo> shadowFields,
                                       Map<String, ShadowMethodInfo> shadowMethods) {
        if (method.desc.contains(mixinInternal)) {
            method.desc = method.desc.replace(mixinInternal, targetInternal);
        }
        InsnList instructions = method.instructions;
        if (instructions == null) {
            return;
        }

        for (AbstractInsnNode node : instructions.toArray()) {
            if (node instanceof FieldInsnNode field) {
                if (field.owner.equals(mixinInternal)) {
                    field.owner = targetInternal;
                }
                ShadowFieldInfo shadowField = shadowFields.get(field.name);
                if (shadowField != null) {
                    field.name = shadowField.targetFieldName;
                    field.owner = targetInternal;
                }
                continue;
            }
            if (node instanceof MethodInsnNode methodCall) {
                if (methodCall.owner.equals(mixinInternal)) {
                    ShadowMethodInfo shadowMethod = shadowMethods.get(methodCall.name + methodCall.desc);
                    if (shadowMethod != null) {
                        methodCall.name = shadowMethod.targetMethodName;
                        if (methodCall.getOpcode() == Opcodes.INVOKESTATIC) {
                            methodCall.setOpcode(Opcodes.INVOKEVIRTUAL);
                            instructions.insertBefore(methodCall, new VarInsnNode(Opcodes.ALOAD, 0));
                        }
                    } else if (methodCall.name.startsWith("lambda.") || methodCall.name.startsWith("lambda$")) {
                        methodCall.setOpcode(Opcodes.INVOKEVIRTUAL);
                    }
                    methodCall.owner = targetInternal;
                }
                continue;
            }
            if (node instanceof TypeInsnNode typeInsn) {
                if (typeInsn.desc.equals(mixinInternal)) {
                    typeInsn.desc = targetInternal;
                }
                continue;
            }
            if (node instanceof InvokeDynamicInsnNode indy) {
                this.rewriteInvokeDynamic(instructions, indy, mixinInternal, targetInternal);
            }
        }

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
            Object arg = indy.bsmArgs[i];
            if (arg instanceof Type type) {
                String oldDesc = type.getDescriptor();
                String rewritten = oldDesc.replace(mixinInternal, targetInternal);
                if (!oldDesc.equals(rewritten)) {
                    newBsmArgs[i] = type.getSort() == Type.METHOD ? Type.getMethodType(rewritten) : Type.getType(rewritten);
                } else {
                    newBsmArgs[i] = arg;
                }
            } else if (arg instanceof Handle handle && handle.getOwner().equals(mixinInternal)) {
                int newTag = handle.getTag();
                if ((newTag == Opcodes.H_INVOKESTATIC || newTag == Opcodes.H_NEWINVOKESPECIAL)
                        && (handle.getName().startsWith("lambda.") || handle.getName().startsWith("lambda$"))) {
                    newTag = Opcodes.H_INVOKEVIRTUAL;
                }
                newBsmArgs[i] = new Handle(newTag, targetInternal, handle.getName(),
                        handle.getDesc().replace(mixinInternal, targetInternal), handle.isInterface());
            } else {
                newBsmArgs[i] = arg;
            }
        }
        instructions.set(indy, new InvokeDynamicInsnNode(indy.name, newDesc, indy.bsm, newBsmArgs));
    }

    private void applyMixinInject(MethodNode target, InjectPoint point, TransformerHolder holder, ClassLoader loader,
                                  String actualClassName, ClassNode classNode, List<MethodNode> injectHandlerMethodsToAdd,
                                  Map<String, InjectHandlerCall> injectHandlerCalls) throws NoSuchMethodException, IOException {
        Type targetType = Type.getMethodType(target.desc);
        Type[] targetArgs = targetType.getArgumentTypes();
        boolean targetIsInstance = (target.access & Opcodes.ACC_STATIC) == 0;
        String mixinInternal = point.mixinClassName.replace('.', '/');
        InjectHandlerCall handlerCall = point.mixinMethodStatic
                ? new InjectHandlerCall(Opcodes.INVOKESTATIC, mixinInternal, point.mixinMethodName, point.mixinMethodDesc, false, false)
                : this.ensureInjectHandlerMethod(point, holder, classNode, actualClassName, mixinInternal,
                injectHandlerMethodsToAdd, injectHandlerCalls, targetIsInstance);
        Type mixinMethodType = Type.getMethodType(handlerCall.desc);
        Type[] mixinArgTypes = mixinMethodType.getArgumentTypes();
        boolean injectThis = false;
        if (targetIsInstance && mixinArgTypes.length > 0 && mixinArgTypes[0].getSort() == Type.OBJECT) {
            String firstArg = mixinArgTypes[0].getInternalName();
            injectThis = firstArg.equals(holder.targetInternalName) || this.hierarchyResolver.isSubclassASM(actualClassName, firstArg, loader);
        }

        InsnList inject = new InsnList();
        int baseLocalSlots = Type.getArgumentsAndReturnSizes(target.desc) >> 2;
        int localIndex = targetIsInstance ? 1 : 0;
        int callbackVar = -1;
        boolean hasCallback = false;
        int targetArgIndex = 0;
        int mixinArgIndex = 0;

        if (handlerCall.needsReceiver) {
            inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        if (injectThis) {
            inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
            mixinArgIndex++;
        }

        while (mixinArgIndex < mixinArgTypes.length) {
            Type mixinArg = mixinArgTypes[mixinArgIndex];
            String argInternal = mixinArg.getSort() == Type.OBJECT ? mixinArg.getInternalName() : "";
            if (MixinConstants.CALL_BACK_INFO.equals(argInternal)) {
                inject.add(new TypeInsnNode(Opcodes.NEW, MixinConstants.CALL_BACK_INFO));
                inject.add(new InsnNode(Opcodes.DUP));
                inject.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MixinConstants.CALL_BACK_INFO, "<init>", "()V", false));
                callbackVar = baseLocalSlots;
                baseLocalSlots++;
                inject.add(new VarInsnNode(Opcodes.ASTORE, callbackVar));
                inject.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
                hasCallback = true;
            } else if (targetArgIndex < targetArgs.length) {
                inject.add(this.getLoadInsn(targetArgs[targetArgIndex], localIndex));
                localIndex += targetArgs[targetArgIndex].getSize();
                targetArgIndex++;
            } else {
                inject.add(this.getDefaultInsn(mixinArg));
            }
            mixinArgIndex++;
        }

        inject.add(new MethodInsnNode(handlerCall.opcode, handlerCall.owner, handlerCall.name, handlerCall.desc, handlerCall.isInterface));

        if (hasCallback) {
            LabelNode continueLabel = new LabelNode();
            inject.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
            inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MixinConstants.CALL_BACK_INFO, "isBack", "()Z", false));
            inject.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
            Type returnType = targetType.getReturnType();
            if (returnType.getSort() != Type.VOID) {
                inject.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
                inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MixinConstants.CALL_BACK_INFO, "getBackValue", "()Ljava/lang/Object;", false));
                inject.add(this.unboxAndReturn(returnType));
            } else {
                inject.add(new InsnNode(Opcodes.RETURN));
            }
            inject.add(continueLabel);
        }

        switch (point.position) {
            case HEAD -> {
                AbstractInsnNode first = target.instructions.getFirst();
                if (first != null) {
                    target.instructions.insertBefore(first, inject);
                } else {
                    target.instructions.add(inject);
                }
            }
            case LAST -> this.insertBeforeReturns(target, inject);
            default -> {
                AbstractInsnNode first = target.instructions.getFirst();
                if (first != null) {
                    target.instructions.insertBefore(first, inject);
                } else {
                    target.instructions.add(inject);
                }
            }
        }

        target.maxLocals = Math.max(target.maxLocals, baseLocalSlots);
        target.maxStack = Math.max(target.maxStack, 0);
    }

    private InjectHandlerCall ensureInjectHandlerMethod(InjectPoint point, TransformerHolder holder, ClassNode targetClass,
                                                        String actualClassName, String mixinInternal,
                                                        List<MethodNode> methodsToAdd,
                                                        Map<String, InjectHandlerCall> handlerCalls,
                                                        boolean targetIsInstance) throws NoSuchMethodException, IOException {
        if (!targetIsInstance) {
            throw new IllegalStateException("@Inject handler must be static when target method is static: "
                    + point.mixinClassName + "." + point.mixinMethodName + point.mixinMethodDesc);
        }

        String key = point.mixinClassName + '\n' + point.mixinMethodName + point.mixinMethodDesc;
        InjectHandlerCall existing = handlerCalls.get(key);
        if (existing != null) {
            return existing;
        }

        MethodNode mixinMethod = holder.rewrittenMethodCache.get(point.mixinMethodName + point.mixinMethodDesc);
        if (mixinMethod == null) {
            throw new NoSuchMethodException("Cannot find inject method: " + point.mixinMethodName + point.mixinMethodDesc);
        }

        MethodNode handler = holder.cloneMethod(mixinMethod);
        this.rewriteSelfReferences(handler, mixinInternal, actualClassName, holder.shadowFields, holder.shadowMethods);
        handler.name = this.uniqueInjectHandlerName(point, handler.desc, targetClass, methodsToAdd);
        handler.access = (handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
                | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        handler.visibleAnnotations = null;
        handler.invisibleAnnotations = null;
        handler.visibleParameterAnnotations = null;
        handler.invisibleParameterAnnotations = null;
        methodsToAdd.add(handler);
        this.copyLambdaMethods(point.mixinClassName, point.mixinMethodName, targetClass, holder,
                actualClassName, mixinInternal, methodsToAdd);

        InjectHandlerCall call = new InjectHandlerCall(Opcodes.INVOKESPECIAL, actualClassName, handler.name,
                handler.desc, (targetClass.access & Opcodes.ACC_INTERFACE) != 0, true);
        handlerCalls.put(key, call);
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
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode fieldInsn)) {
                    continue;
                }
                ReturnFieldPoint point = this.matchReturnField(fieldInsn, loader, points);
                if (point == null) {
                    continue;
                }

                int opcode = fieldInsn.getOpcode();
                if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                    if (!point.isStatic) {
                        method.instructions.insertBefore(fieldInsn, new InsnNode(Opcodes.DUP));
                    }
                    method.instructions.insert(fieldInsn, this.invokeReturnFieldHandler(point));
                } else if (opcode == Opcodes.PUTFIELD) {
                    Type fieldType = Type.getType(fieldInsn.desc);
                    int tempLocal = Math.max(method.maxLocals, 0);
                    method.maxLocals = tempLocal + fieldType.getSize();

                    InsnList replacement = new InsnList();
                    replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ISTORE), tempLocal));
                    replacement.add(new InsnNode(Opcodes.DUP));
                    replacement.add(new VarInsnNode(fieldType.getOpcode(Opcodes.ILOAD), tempLocal));
                    replacement.add(this.newReturnFieldHandlerCall(point));
                    if (point.returnCastType != null) {
                        replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, point.returnCastType));
                    }
                    method.instructions.insertBefore(fieldInsn, replacement);
                } else if (opcode == Opcodes.PUTSTATIC) {
                    method.instructions.insertBefore(fieldInsn, this.invokeReturnFieldHandler(point));
                }
                changed = true;
            }
        }
        return changed;
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

    private AbstractInsnNode getDefaultInsn(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new InsnNode(Opcodes.ICONST_0);
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            default -> new InsnNode(Opcodes.ACONST_NULL);
        };
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
                labels.put(label, new LabelNode());
            }
        }
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            copy.add(insn.clone(labels));
        }
        return copy;
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
}
