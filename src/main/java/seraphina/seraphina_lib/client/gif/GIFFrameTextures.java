package seraphina.seraphina_lib.client.gif;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

record GIFFrameTextures(List<ResourceLocation> locations, List<DynamicTexture> textures) {
}
