package seraphina.seraphina_mod.client.svg;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import seraphina.seraphina_mod.client.image.ClientImageUtil;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

final class SvgTextureFrames {
    private final ResourceLocation svgLocation;
    private final ResourceLocation textureLocation;
    private final int textureWidth;
    private final int textureHeight;
    private final Map<Long, ResourceLocation> animationFrameLocations = new HashMap<>();
    private DynamicTexture texture;

    SvgTextureFrames(ResourceLocation svgLocation, ResourceLocation textureLocation, int textureWidth, int textureHeight) {
        this.svgLocation = svgLocation;
        this.textureLocation = textureLocation;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    DynamicTexture texture() {
        return texture;
    }

    ResourceLocation uploadRepeatingFrame(BufferedImage image) {
        NativeImage nativeImage = ClientImageUtil.toNativeImage(image);
        ClientImageUtil.executeRenderCall(() -> {
            if (texture == null) {
                texture = ClientImageUtil.createAndRegisterTexture(textureLocation, nativeImage);
            } else {
                ClientImageUtil.updateTexture(texture, nativeImage);
            }
        });
        return textureLocation;
    }

    ResourceLocation cachedFrameLocation(long frameKey) {
        return animationFrameLocations.get(frameKey);
    }

    ResourceLocation uploadCachedFrame(long frameKey, BufferedImage image) {
        ResourceLocation cachedLocation = animationFrameLocations.get(frameKey);
        if (cachedLocation != null) {
            return cachedLocation;
        }

        ResourceLocation frameLocation = animationFrameTextureLocation(frameKey);
        NativeImage nativeImage = ClientImageUtil.toNativeImage(image);
        ClientImageUtil.executeRenderCall(() -> ClientImageUtil.createAndRegisterTexture(frameLocation, nativeImage));
        animationFrameLocations.put(frameKey, frameLocation);
        return frameLocation;
    }

    private ResourceLocation animationFrameTextureLocation(long frameKey) {
        return ResourceLocation.fromNamespaceAndPath(
                svgLocation.getNamespace(),
                "svg/generated/" + ClientImageUtil.sanitizePath(svgLocation.getPath()) + "_" + textureWidth + "x" + textureHeight
                        + "/frame_" + SvgAnimationFrameKey.frameName(frameKey)
        );
    }
}
