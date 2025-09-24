#version 150

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec2 vUv;
out vec4 vColor;
out vec2 vScreenUV;

void main() {
    vec4 worldPos = ModelViewMat * vec4(Position, 1.0);
    vec4 clipPos  = ProjMat * worldPos;
    gl_Position   = clipPos;

    vUv    = UV0;
    vColor = Color;

    // Stable screen-space UV (0..1) derived from clip space.
    vec2 ndc = clipPos.xy / max(clipPos.w, 1e-6);
    vScreenUV = ndc * 0.5 + 0.5;
}
