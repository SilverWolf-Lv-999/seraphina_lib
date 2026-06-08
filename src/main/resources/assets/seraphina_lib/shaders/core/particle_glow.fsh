#version 150

uniform sampler2D Sampler0;
uniform float GlowIntensity;
uniform float GlowRadius;

in vec2 texCoord0;
in vec2 quadCoord;
in vec4 vertexColor;
flat in int packedLight;

out vec4 fragColor;

vec4 glowSample(vec2 offset, float weight) {
    vec4 sampleColor = texture(Sampler0, texCoord0 + offset) * vertexColor;
    sampleColor.rgb *= sampleColor.a;
    return sampleColor * weight;
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;

    vec2 texel = GlowRadius / vec2(textureSize(Sampler0, 0));
    vec4 glow = vec4(0.0);
    glow += glowSample(vec2(texel.x, 0.0), 0.12);
    glow += glowSample(vec2(-texel.x, 0.0), 0.12);
    glow += glowSample(vec2(0.0, texel.y), 0.12);
    glow += glowSample(vec2(0.0, -texel.y), 0.12);
    glow += glowSample(vec2(texel.x, texel.y), 0.08);
    glow += glowSample(vec2(-texel.x, texel.y), 0.08);
    glow += glowSample(vec2(texel.x, -texel.y), 0.08);
    glow += glowSample(vec2(-texel.x, -texel.y), 0.08);

    vec2 farTexel = texel * 2.0;
    glow += glowSample(vec2(farTexel.x, 0.0), 0.045);
    glow += glowSample(vec2(-farTexel.x, 0.0), 0.045);
    glow += glowSample(vec2(0.0, farTexel.y), 0.045);
    glow += glowSample(vec2(0.0, -farTexel.y), 0.045);
    glow += glowSample(vec2(farTexel.x, farTexel.y), 0.025);
    glow += glowSample(vec2(-farTexel.x, farTexel.y), 0.025);
    glow += glowSample(vec2(farTexel.x, -farTexel.y), 0.025);
    glow += glowSample(vec2(-farTexel.x, -farTexel.y), 0.025);

    glow.rgb = glow.a > 0.0001 ? glow.rgb / glow.a : vec3(0.0);
    glow.a = clamp(glow.a, 0.0, 1.0);

    float edgeDistance = max(abs(quadCoord.x), abs(quadCoord.y));
    float innerFade = smoothstep(0.12, 0.72, edgeDistance);
    float outerFade = 1.0 - smoothstep(0.72, 1.0, edgeDistance);
    float expandedHalo = innerFade * outerFade;

    if (color.a <= 0.01 && glow.a <= 0.01 && expandedHalo <= 0.01) {
        discard;
    }

    float lightKeepAlive = float(packedLight) * 0.0;
    float strength = max(0.0, GlowIntensity - 1.0);
    color.rgb *= max(1.0, GlowIntensity);
    vec3 sourceColor = color.a > 0.01 ? color.rgb / max(color.a, 0.001) : glow.rgb;
    vec3 bloomColor = (glow.rgb * glow.a + sourceColor * expandedHalo) * strength * 1.35;
    float bloomAlpha = max(glow.a, expandedHalo) * min(1.0, 0.35 + strength * 0.25);

    fragColor = vec4(color.rgb + bloomColor + lightKeepAlive, max(color.a, bloomAlpha));
}
