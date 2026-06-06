package seraphina.seraphina_lib.util.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

public class EntityDataInject {
    public static EntityDataAccessor<Boolean> IS_DEF_ENTITY = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);

    public static boolean isDef(LivingEntity entity) {
        return entity.getEntityData().get(IS_DEF_ENTITY);
    }

    public static void setDef(LivingEntity entity, boolean value) {
        entity.getEntityData().set(IS_DEF_ENTITY, value);
    }
}
