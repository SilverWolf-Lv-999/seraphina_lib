package seraphina.seraphina_mod.client.particle;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import seraphina.seraphina_lib.LIBSource;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

public final class ParticleUtil {
    public static final int MAX_LIGHT_LEVEL = 15;
    public static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    public static final Vec3 ZERO_SPEED = Vec3.ZERO;

    private static final String GLOW_SHADER_NAME = LIBSource.MOD_ID + ":particle_glow";
    private static final float DEFAULT_GLOW_INTENSITY = 1.75F;
    private static final float DEFAULT_GLOW_RADIUS = 10.0F;
    private static final RandomSource RANDOM = RandomSource.createThreadSafe();
    private static final Deque<RenderState> RENDER_STATES = new ArrayDeque<>();

    @Getter
    private static ShaderInstance glowShader;

    /**
     * Particle render type that keeps the whole particle batch in the glow shader.
     *
     * <p>For particles that draw with the vanilla sheet render path, overriding
     * {@code getRenderType()} with this render type is the most stable way to
     * keep the shader active until the batched vertices are flushed.</p>
     */
    public static final ParticleRenderType PARTICLE_SHEET_GLOW = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder buffer, TextureManager textureManager) {
            textureManager.bindForSetup(TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            ParticleUtil.begin();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(Tesselator tesselator) {
            tesselator.end();
            ParticleUtil.end();
        }

        @Override
        public String toString() {
            return "PARTICLE_SHEET_GLOW";
        }
    };

    private ParticleUtil() {
    }

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), GLOW_SHADER_NAME, DefaultVertexFormat.PARTICLE),
                    shader -> glowShader = shader);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load particle glow shader: " + GLOW_SHADER_NAME, exception);
        }
    }

    public static void begin() {
        begin(DEFAULT_GLOW_INTENSITY, DEFAULT_GLOW_RADIUS);
    }

    public static void begin(float intensity) {
        begin(intensity, DEFAULT_GLOW_RADIUS);
    }

    public static void begin(float intensity, float radius) {
        float[] shaderColor = RenderSystem.getShaderColor();
        boolean particleBatchOpen = closeCurrentParticleBatch();
        RENDER_STATES.push(new RenderState(RenderSystem.getShader(), shaderColor.clone(), particleBatchOpen));

        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE
        );
        RenderSystem.setShader(() -> glowShader != null ? glowShader : GameRenderer.getParticleShader());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        setGlowUniforms(intensity, radius);
        if (particleBatchOpen) {
            openParticleBatch();
        }
    }

    public static void end() {
        if (RENDER_STATES.isEmpty()) {
            restoreDefaultParticleState();
            return;
        }

        RenderState state = RENDER_STATES.pop();
        if (state.particleBatchOpen()) {
            closeCurrentParticleBatch();
        }

        RenderSystem.setShaderColor(state.shaderColor()[0], state.shaderColor()[1], state.shaderColor()[2], state.shaderColor()[3]);
        if (state.shader() != null) {
            RenderSystem.setShader(state::shader);
        } else {
            RenderSystem.setShader(GameRenderer::getParticleShader);
        }
        restoreDefaultParticleState();
        if (state.particleBatchOpen()) {
            openParticleBatch();
        }
    }

    public static void renderWithGlow(Runnable renderCall) {
        renderWithGlow(DEFAULT_GLOW_INTENSITY, renderCall);
    }

    public static void renderWithGlow(float intensity, Runnable renderCall) {
        renderWithGlow(intensity, DEFAULT_GLOW_RADIUS, renderCall);
    }

    public static void renderWithGlow(float intensity, float radius, Runnable renderCall) {
        Objects.requireNonNull(renderCall, "renderCall");
        begin(intensity, radius);
        try {
            renderCall.run();
        } finally {
            end();
        }
    }

    public static int fullBrightLight() {
        return FULL_BRIGHT;
    }

    public static int packedLight(int blockLight, int skyLight) {
        return LightTexture.pack(Mth.clamp(blockLight, 0, MAX_LIGHT_LEVEL), Mth.clamp(skyLight, 0, MAX_LIGHT_LEVEL));
    }

    public static int glowLight(int packedLight, float glow) {
        float amount = clamp01(glow);
        int blockLight = Mth.lerpInt(amount, LightTexture.block(packedLight), MAX_LIGHT_LEVEL);
        int skyLight = Mth.lerpInt(amount, LightTexture.sky(packedLight), MAX_LIGHT_LEVEL);
        return packedLight(blockLight, skyLight);
    }

    public static int glowLight(float glow) {
        return glowLight(0, glow);
    }

    public static void spawn(ClientLevel level, ParticleOptions options, Vec3 position) {
        spawn(level, options, position, ZERO_SPEED);
    }

    public static void spawn(ClientLevel level, ParticleOptions options, Vec3 position, Vec3 speed) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(speed, "speed");
        level.addParticle(options, position.x, position.y, position.z, speed.x, speed.y, speed.z);
    }

    public static void spawnAlwaysVisible(ClientLevel level, ParticleOptions options, Vec3 position) {
        spawnAlwaysVisible(level, options, position, ZERO_SPEED);
    }

    public static void spawnAlwaysVisible(ClientLevel level, ParticleOptions options, Vec3 position, Vec3 speed) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(speed, "speed");
        level.addAlwaysVisibleParticle(options, position.x, position.y, position.z, speed.x, speed.y, speed.z);
    }

    public static boolean spawnInCurrentLevel(ParticleOptions options, Vec3 position) {
        return spawnInCurrentLevel(options, position, ZERO_SPEED);
    }

    public static boolean spawnInCurrentLevel(ParticleOptions options, Vec3 position, Vec3 speed) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }

        spawn(level, options, position, speed);
        return true;
    }

    public static Particle spawnLocal(ParticleOptions options, Vec3 position, Vec3 speed) {
        return spawnLocal(options, position, speed, null);
    }

    public static Particle spawnLocal(ParticleOptions options, Vec3 position, Vec3 speed, Consumer<Particle> configurator) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(speed, "speed");

        ParticleEngine particleEngine = Minecraft.getInstance().particleEngine;
        Particle particle = particleEngine.createParticle(options, position.x, position.y, position.z, speed.x, speed.y, speed.z);
        if (particle == null) {
            return null;
        }

        if (configurator != null) {
            configurator.accept(particle);
        }

        particleEngine.add(particle);
        return particle;
    }

    public static int spawnLine(ClientLevel level, ParticleOptions options, Vec3 from, Vec3 to, int count) {
        return spawnLine(level, options, from, to, count, ZERO_SPEED);
    }

    public static int spawnLine(ClientLevel level, ParticleOptions options, Vec3 from, Vec3 to, int count, Vec3 speed) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(speed, "speed");

        int safeCount = Math.max(0, count);
        if (safeCount == 0) {
            return 0;
        }

        Vec3 delta = to.subtract(from);
        for (int index = 0; index < safeCount; index++) {
            double progress = safeCount == 1 ? 0.0D : (double) index / (safeCount - 1);
            spawn(level, options, from.add(delta.scale(progress)), speed);
        }

        return safeCount;
    }

    public static int spawnRing(ClientLevel level, ParticleOptions options, Vec3 center, double radius, int count) {
        return spawnRing(level, options, center, radius, count, 0.0D);
    }

    public static int spawnRing(ClientLevel level, ParticleOptions options, Vec3 center, double radius, int count, double outwardSpeed) {
        Objects.requireNonNull(center, "center");

        int safeCount = Math.max(0, count);
        if (safeCount == 0) {
            return 0;
        }

        double safeRadius = Math.max(0.0D, radius);
        for (int index = 0; index < safeCount; index++) {
            double angle = Mth.TWO_PI * index / safeCount;
            double x = Math.cos(angle);
            double z = Math.sin(angle);
            Vec3 offset = new Vec3(x * safeRadius, 0.0D, z * safeRadius);
            Vec3 speed = outwardSpeed == 0.0D ? ZERO_SPEED : new Vec3(x * outwardSpeed, 0.0D, z * outwardSpeed);
            spawn(level, options, center.add(offset), speed);
        }

        return safeCount;
    }

    public static int spawnSphere(ClientLevel level, ParticleOptions options, Vec3 center, double radius, int count) {
        return spawnSphere(level, options, center, radius, count, false, 0.0D);
    }

    public static int spawnSphere(ClientLevel level, ParticleOptions options, Vec3 center,
                                  double radius, int count, boolean surfaceOnly, double outwardSpeed) {
        Objects.requireNonNull(center, "center");

        int safeCount = Math.max(0, count);
        if (safeCount == 0) {
            return 0;
        }

        double safeRadius = Math.max(0.0D, radius);
        for (int index = 0; index < safeCount; index++) {
            Vec3 direction = randomUnitVector(RANDOM);
            double distance = surfaceOnly ? safeRadius : safeRadius * Math.cbrt(RANDOM.nextDouble());
            Vec3 speed = outwardSpeed == 0.0D ? ZERO_SPEED : direction.scale(outwardSpeed);
            spawn(level, options, center.add(direction.scale(distance)), speed);
        }

        return safeCount;
    }

    public static int spawnBox(ClientLevel level, ParticleOptions options, AABB bounds, int count) {
        return spawnBox(level, options, bounds, count, ZERO_SPEED);
    }

    public static int spawnBox(ClientLevel level, ParticleOptions options, AABB bounds, int count, Vec3 speed) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(speed, "speed");

        int safeCount = Math.max(0, count);
        for (int index = 0; index < safeCount; index++) {
            spawn(level, options, randomPoint(bounds, RANDOM), speed);
        }

        return safeCount;
    }

    public static int spawnBurst(ClientLevel level, ParticleOptions options, Vec3 center, int count, double speed) {
        Objects.requireNonNull(center, "center");

        int safeCount = Math.max(0, count);
        for (int index = 0; index < safeCount; index++) {
            spawn(level, options, center, randomUnitVector(RANDOM).scale(speed));
        }

        return safeCount;
    }

    public static int spawnAround(Entity entity, ParticleOptions options, int count, double inflate, double speed) {
        Objects.requireNonNull(entity, "entity");

        if (!(entity.level() instanceof ClientLevel level)) {
            return 0;
        }

        int safeCount = Math.max(0, count);
        AABB bounds = entity.getBoundingBox().inflate(Math.max(0.0D, inflate));
        for (int index = 0; index < safeCount; index++) {
            spawn(level, options, randomPoint(bounds, RANDOM), randomUnitVector(RANDOM).scale(speed));
        }

        return safeCount;
    }

    public static <T extends Particle> T tint(T particle, int rgb) {
        Objects.requireNonNull(particle, "particle");
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return tint(particle, red, green, blue);
    }

    public static <T extends Particle> T tint(T particle, int red, int green, int blue) {
        return tint(particle, red / 255.0F, green / 255.0F, blue / 255.0F);
    }

    public static <T extends Particle> T tint(T particle, float red, float green, float blue) {
        Objects.requireNonNull(particle, "particle");
        particle.setColor(clamp01(red), clamp01(green), clamp01(blue));
        return particle;
    }

    public static <T extends Particle> T lifetime(T particle, int lifetime) {
        Objects.requireNonNull(particle, "particle");
        particle.setLifetime(Math.max(1, lifetime));
        return particle;
    }

    public static <T extends Particle> T scale(T particle, float scale) {
        Objects.requireNonNull(particle, "particle");
        particle.scale(Math.max(0.0F, scale));
        return particle;
    }

    public static <T extends Particle> T speed(T particle, Vec3 speed) {
        Objects.requireNonNull(particle, "particle");
        Objects.requireNonNull(speed, "speed");
        particle.setParticleSpeed(speed.x, speed.y, speed.z);
        return particle;
    }

    public static Vec3 randomUnitVector(RandomSource random) {
        Objects.requireNonNull(random, "random");
        double y = random.nextDouble() * 2.0D - 1.0D;
        double angle = random.nextDouble() * Mth.TWO_PI;
        double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
        return new Vec3(Math.cos(angle) * horizontal, y, Math.sin(angle) * horizontal);
    }

    public static Vec3 randomPoint(AABB bounds, RandomSource random) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(random, "random");
        return new Vec3(
                Mth.lerp(random.nextDouble(), bounds.minX, bounds.maxX),
                Mth.lerp(random.nextDouble(), bounds.minY, bounds.maxY),
                Mth.lerp(random.nextDouble(), bounds.minZ, bounds.maxZ)
        );
    }

    public static float clamp01(float value) {
        return Mth.clamp(value, 0.0F, 1.0F);
    }

    private static void setGlowUniforms(float intensity, float radius) {
        if (glowShader == null) {
            return;
        }

        Uniform intensityUniform = glowShader.getUniform("GlowIntensity");
        if (intensityUniform != null) {
            intensityUniform.set(Math.max(0.0F, intensity));
        }

        Uniform radiusUniform = glowShader.getUniform("GlowRadius");
        if (radiusUniform != null) {
            radiusUniform.set(Math.max(0.0F, radius));
        }
    }

    private static void restoreDefaultParticleState() {
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }

    private static boolean closeCurrentParticleBatch() {
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        if (!buffer.building()) {
            return false;
        }

        if (buffer.isCurrentBatchEmpty()) {
            buffer.endOrDiscardIfEmpty();
        } else {
            Tesselator.getInstance().end();
        }
        return true;
    }

    private static void openParticleBatch() {
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
        Tesselator.getInstance().getBuilder().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
    }

    private record RenderState(ShaderInstance shader, float[] shaderColor, boolean particleBatchOpen) {
    }
}
