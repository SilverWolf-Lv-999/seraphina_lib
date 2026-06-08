var Opcodes = Java.type("org.objectweb.asm.Opcodes");
var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");
var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
var LdcInsnNode = Java.type("org.objectweb.asm.tree.LdcInsnNode");
var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
var TryCatchBlockNode = Java.type("org.objectweb.asm.tree.TryCatchBlockNode");
var TypeInsnNode = Java.type("org.objectweb.asm.tree.TypeInsnNode");
var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");

var BOOTSTRAP_CLASS = "seraphina.seraphina_lib.mixin.service.SeraMixinTransformationService";
var BOOTSTRAP_METHOD = "bootstrapFromCoremod";

function initializeCoreMod() {
    return {
        "seraphina_mixin_client_main": {
            "target": {
                "type": "CLASS",
                "name": "net.minecraft.client.main.Main"
            },
            "transformer": transformMainClass
        },
        "seraphina_mixin_server_main": {
            "target": {
                "type": "CLASS",
                "name": "net.minecraft.server.Main"
            },
            "transformer": transformMainClass
        },
        "seraphina_mixin_data_main": {
            "target": {
                "type": "CLASS",
                "name": "net.minecraft.data.Main"
            },
            "transformer": transformMainClass
        }
    };
}

function transformMainClass(classNode) {
    for (var index = 0; index < classNode.methods.size(); index++) {
        var method = classNode.methods.get(index);
        if (method.name === "main" && method.desc === "([Ljava/lang/String;)V") {
            injectBootstrap(method);
            break;
        }
    }
    return classNode;
}

function injectBootstrap(method) {
    var start = new LabelNode();
    var end = new LabelNode();
    var handler = new LabelNode();
    var done = new LabelNode();
    var instructions = new InsnList();

    instructions.add(start);
    instructions.add(new LdcInsnNode(BOOTSTRAP_CLASS));
    instructions.add(new InsnNode(Opcodes.ICONST_1));
    instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
    instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false));
    instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
    instructions.add(new LdcInsnNode(BOOTSTRAP_METHOD));
    instructions.add(new InsnNode(Opcodes.ICONST_0));
    instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
    instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
    instructions.add(new InsnNode(Opcodes.ACONST_NULL));
    instructions.add(new InsnNode(Opcodes.ICONST_0));
    instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
    instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
    instructions.add(new InsnNode(Opcodes.POP));
    instructions.add(end);
    instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
    instructions.add(handler);
    instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
    instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
    instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false));
    instructions.add(done);

    method.instructions.insert(instructions);
    method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    method.maxLocals = Math.max(method.maxLocals, 2);
    method.maxStack = Math.max(method.maxStack, 5);
}
