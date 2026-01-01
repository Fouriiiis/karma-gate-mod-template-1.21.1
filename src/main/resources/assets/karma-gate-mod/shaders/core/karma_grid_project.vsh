#version 150

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec4 vColor;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    vec4 clipPos = ProjMat * viewPos;
    gl_Position = clipPos;

    vColor = Color;
}
