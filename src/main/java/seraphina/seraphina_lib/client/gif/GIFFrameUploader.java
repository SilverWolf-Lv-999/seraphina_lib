package seraphina.seraphina_lib.client.gif;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import seraphina.seraphina_lib.client.image.ClientImageUtil;

import java.util.ArrayList;
import java.util.List;

final class GIFFrameUploader {
    private GIFFrameUploader() {
    }

    static GIFFrameTextures upload(ResourceLocation sourceLocation, List<GIFFrame> frames) {
        List<ResourceLocation> locations = new ArrayList<>(frames.size());
        List<DynamicTexture> textures = new ArrayList<>(frames.size());

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            ResourceLocation frameLocation = frameTextureLocation(sourceLocation, frameIndex);
            NativeImage nativeImage = ClientImageUtil.toNativeImage(frames.get(frameIndex).image());
            ClientImageUtil.executeRenderCall(() ->
                    textures.add(ClientImageUtil.createAndRegisterTexture(frameLocation, nativeImage))
            );
            locations.add(frameLocation);
        }

        return new GIFFrameTextures(locations, textures);
    }

    private static ResourceLocation frameTextureLocation(ResourceLocation sourceLocation, int frameIndex) {
        return ResourceLocation.fromNamespaceAndPath(
                sourceLocation.getNamespace(),
                "gif/generated/" + ClientImageUtil.sanitizePath(sourceLocation.getPath()) + "/frame_" + frameIndex
        );
    }
}
