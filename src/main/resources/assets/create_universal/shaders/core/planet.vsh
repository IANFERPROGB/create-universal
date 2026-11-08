#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 Params;
uniform vec4 Ring;
uniform float FarDepth;

out vec3 objPos;
out vec2 texCoord;

void main() {
    int mode = int(Params.x + 0.5);
    objPos = Position;
    texCoord = UV0;
    if (mode == 7) {
        // Fondo negro a pantalla completa, en el plano lejano.
        gl_Position = vec4(Position.xy, 1.0, 1.0);
        return;
    }
    if (mode == 9) {
        // Destello de lente: cuadro en coordenadas de pantalla (Ring.xy = tamaño, Ring.zw = centro).
        gl_Position = vec4(Position.xy * Ring.xy + Ring.zw, 1.0, 1.0);
        return;
    }
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    if (FarDepth > 0.5) {
        // Con Iris se dibuja después del mundo: todo en el plano lejano, para que solo se vea donde hay cielo.
        gl_Position.z = gl_Position.w;
    }
}
