#version 150

// Estrellas del catálogo de la NASA: punto redondo y suave, sin parpadeo (en el espacio no hay aire que las haga titilar).

uniform float Brightness;

in vec2 texCoord;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 q = texCoord * 2.0 - 1.0;
    float g = exp(-dot(q, q) * 3.5) * (1.0 - smoothstep(0.85, 1.0, length(q)));
    fragColor = vec4(vertexColor.rgb * g * Brightness, 1.0);
}
