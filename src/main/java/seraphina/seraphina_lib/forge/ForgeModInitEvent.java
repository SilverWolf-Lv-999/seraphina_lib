package seraphina.seraphina_lib.forge;

import lombok.Getter;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.eventbus.api.Event;

@Getter
public abstract class ForgeModInitEvent extends Event {
    final ForgeMod forgeMod;

    public ForgeModInitEvent(ForgeMod forgeMod) {
        this.forgeMod = forgeMod;
    }

    public static class Pre extends ForgeModInitEvent {
        public Pre(ForgeMod forgeMod) {
            super(forgeMod);
        }
    }

    public static class Post extends ForgeModInitEvent {
        public Post(ForgeMod forgeMod) {
            super(forgeMod);
        }
    }
}
