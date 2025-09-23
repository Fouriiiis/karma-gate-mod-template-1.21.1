#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;           // main sheet UV
in vec2 UV2;           // lightmap uv (ignored but required by format)

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vColor;
out vec2 vUv;

void main() {
    vColor = Color;
    vUv = UV0;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
