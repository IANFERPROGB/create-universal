#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float FarDepth;

out vec2 texCoord;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    if (FarDepth > 0.5) {
        gl_Position.z = gl_Position.w;
    }
    texCoord = UV0;
    vertexColor = Color;
}
