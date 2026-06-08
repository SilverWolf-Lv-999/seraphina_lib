#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 ColorModulator;
uniform float GlowIntensity;
uniform float GlowRadius;
uniform vec2 ScreenSize;

out vec2 texCoord0;
out vec2 quadCoord;
out vec4 vertexColor;
flat out int packedLight;

vec2 cornerFromVertexId() {
    int corner = gl_VertexID & 3;
    if (corner == 0) {
        return vec2(-1.0, -1.0);
    }
    if (corner == 1) {
        return vec2(-1.0, 1.0);
    }
    if (corner == 2) {
        return vec2(1.0, 1.0);
    }
    return vec2(1.0, -1.0);
}

void main() {
    vec2 corner = cornerFromVertexId();
    vec4 clipPosition = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec2 safeScreenSize = max(ScreenSize, vec2(1.0));
    clipPosition.xy += corner * GlowRadius * 2.0 / safeScreenSize * clipPosition.w;

    gl_Position = clipPosition;
    texCoord0 = UV0;
    quadCoord = corner;
    vertexColor = Color * ColorModulator;
    vertexColor.rgb *= max(1.0, GlowIntensity);
    packedLight = UV2.x + UV2.y;
}
