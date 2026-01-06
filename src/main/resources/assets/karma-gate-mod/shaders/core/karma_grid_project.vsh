#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// Inverse of the view matrix you used on the CPU when baking positions into the buffer.
// This lets the shader recover true world-space position per-fragment (so rays stay straight).
uniform mat4 uInvViewMat;

out vec4 vColor;
out vec2 vWorldUV;     // kept for compatibility/debug
out vec3 vWorldPos;    // recovered world position

void main() {
    vColor   = Color;
    vWorldUV = UV0;

    // Position arriving here is *already* in view space (because the renderer bakes the view matrix into vertices).
    // Recover world-space position so we can do per-fragment ray projection without interpolation warping.
    vWorldPos = (uInvViewMat * vec4(Position, 1.0)).xyz;

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
