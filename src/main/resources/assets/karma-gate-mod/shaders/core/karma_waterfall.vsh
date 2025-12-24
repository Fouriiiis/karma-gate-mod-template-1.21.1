#version 150

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec2 vLocalUV;
out float vFlow;
out vec4 vColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vLocalUV = UV0;
    vFlow = clamp(Color.a, 0.0, 1.0);
    vColor = Color;
}
