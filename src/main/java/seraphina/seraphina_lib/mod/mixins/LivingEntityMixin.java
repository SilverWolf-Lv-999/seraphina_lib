package seraphina.seraphina_lib.mod.mixins;

import net.minecraft.world.entity.LivingEntity;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.util.CallBackInfo;
import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.util.entity.EntityDataInject;

@SeraMixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(methodName = "defineSynchedData", desc = "()V", at = InsertPosition.LAST)
    private void defineSynchedData(CallBackInfo ci) {
        LivingEntity livingEntity = (LivingEntity)(Object)this;
        livingEntity.getEntityData().define(EntityDataInject.IS_DEF_ENTITY, false);
    }

    @Inject(methodName = "getHealth", desc = "()F", at = InsertPosition.HEAD)
    private void getHealth(CallBackInfo ci) {
        LivingEntity livingEntity = (LivingEntity)(Object)this;
        if (EntityDataInject.isDef(livingEntity))
            ci.setBackValue(livingEntity.getMaxHealth());
    }

    @Inject(methodName = "isAlive", desc = "()Z", at = InsertPosition.HEAD)
    private void isAlive(CallBackInfo ci) {
        LivingEntity livingEntity = (LivingEntity)(Object)this;
        if (EntityDataInject.isDef(livingEntity))
            ci.setBackValue(Boolean.TRUE);
    }

    @Inject(methodName = "isDeadOrDying", desc = "()Z", at = InsertPosition.HEAD)
    private void isDeadOrDying(CallBackInfo ci) {
        LivingEntity livingEntity = (LivingEntity)(Object)this;
        if (EntityDataInject.isDef(livingEntity))
            ci.setBackValue(Boolean.TRUE);
    }
}
