package seraphina.seraphina_lib.mod;

import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;
import seraphina.seraphina_lib.service.ISeraMixin;

public class SeraLibMixin implements ISeraMixin {
    public static final Logger LOGGER = LoggerFactory.getLogger(SeraLibMixin.class);

    @Override
    public String getMixinPath() {
        return "seraphina.seraphina_lib.mod.mixins";
    }

    @Override
    public void onLoad() {
        for (int i = 0; i <= 100; i++) {
            LOGGER.info("1");
        }
    }

    static {
        LOGGER.info(SeraLibMixin.class.getName()+" initialized");
    }
}
