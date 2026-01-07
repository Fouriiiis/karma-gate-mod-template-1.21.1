#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;  // Set to identity by Java side
uniform mat4 ProjMat;

// View matrix passed directly as uniform - we control this completely
uniform mat4 uViewMat;

out vec4 vColor;
out vec2 vWorldUV;     // kept for compatibility/debug
out vec3 vWorldPos;    // world position for per-fragment projection

void main() {
    vColor   = Color;
    vWorldUV = UV0;

    // Position is in WORLD SPACE - pass it directly to fragment shader
    vWorldPos = Position;

    // Transform world position to clip space using our controlled matrices
    // uViewMat is set by Java and contains the proper view transformation
    // ModelViewMat should be identity (set by Java), ProjMat is our custom projection
    vec4 viewPos = uViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * viewPos;
}
