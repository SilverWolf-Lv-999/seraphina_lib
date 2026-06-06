package seraphina.seraphina_lib.mixin.service;

import org.objectweb.asm.Type;
import seraphina.seraphina_lib.mixin.annotation.Accessor;
import seraphina.seraphina_lib.mixin.annotation.ASM;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.InjectPoint;
import seraphina.seraphina_lib.mixin.annotation.Invoker;
import seraphina.seraphina_lib.mixin.annotation.ModifyArgs;
import seraphina.seraphina_lib.mixin.annotation.ModifyConstant;
import seraphina.seraphina_lib.mixin.annotation.ModifyVariable;
import seraphina.seraphina_lib.mixin.annotation.NoReMapping;
import seraphina.seraphina_lib.mixin.annotation.Overwrite;
import seraphina.seraphina_lib.mixin.annotation.Redirect;
import seraphina.seraphina_lib.mixin.annotation.ReturnField;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.annotation.Shadow;
import seraphina.seraphina_lib.mixin.util.Args;
import seraphina.seraphina_lib.mixin.util.CallBackInfo;
import seraphina.seraphina_lib.service.ISeraMixin;

final class MixinConstants {
    static final String SERVICE_FILE = "META-INF/services/" + ISeraMixin.class.getName();
    static final String SHADOW_CLASS = Type.getDescriptor(Shadow.class);
    static final String ACCESSOR_CLASS = Type.getDescriptor(Accessor.class);
    static final String INVOKER_CLASS = Type.getDescriptor(Invoker.class);
    static final String INJECT_CLASS = Type.getDescriptor(Inject.class);
    static final String INJECT_POINT_CLASS = Type.getDescriptor(InjectPoint.class);
    static final String ASM_CLASS = Type.getDescriptor(ASM.class);
    static final String REDIRECT_CLASS = Type.getDescriptor(Redirect.class);
    static final String MODIFY_CONSTANT_CLASS = Type.getDescriptor(ModifyConstant.class);
    static final String MODIFY_ARGS_CLASS = Type.getDescriptor(ModifyArgs.class);
    static final String MODIFY_VARIABLE_CLASS = Type.getDescriptor(ModifyVariable.class);
    static final String OVERWRITE_CLASS = Type.getDescriptor(Overwrite.class);
    static final String RETURN_FIELD_CLASS = Type.getDescriptor(ReturnField.class);
    static final String SERA_MIXIN_CLASS = Type.getDescriptor(SeraMixin.class);
    static final String NO_REMAPPING_CLASS = Type.getDescriptor(NoReMapping.class);
    static final String CALL_BACK_INFO = Type.getInternalName(CallBackInfo.class);
    static final String ARGS_CLASS = Type.getInternalName(Args.class);
    static final String NO_PARENT_TARGET = "<NO_PARENT>";

    private MixinConstants() {
    }

    static boolean isPlatformOrLoaderClass(String internalName) {
        if (internalName == null) {
            return true;
        }
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/")
                || internalName.startsWith("org/w3c/")
                || internalName.startsWith("org/xml/")
                || internalName.startsWith("org/objectweb/asm/")
                || internalName.startsWith("cpw/mods/securejarhandler/")
                || internalName.startsWith("cpw/mods/modlauncher/")
                || internalName.startsWith("seraphina/seraphina_lib/mixin/service/");
    }
}
