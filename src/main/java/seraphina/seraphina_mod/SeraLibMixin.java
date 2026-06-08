package seraphina.seraphina_mod;

import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;
import seraphina.seraphina_lib.service.ISeraMixin;

public class SeraLibMixin implements ISeraMixin {
    public static final Logger LOGGER = LoggerFactory.getLogger(SeraLibMixin.class);

    @Override
    public String getMixinPath() {
        return "seraphina.seraphina_mod.mixins";
    }

    @Override
    public void onLoad() {
        ISeraMixin.super.onLoad();
    }

    static {
        LOGGER.info(SeraLibMixin.class.getName()+" initialized");
    }
}
