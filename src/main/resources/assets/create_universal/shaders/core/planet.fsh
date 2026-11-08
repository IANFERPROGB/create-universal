#version 150

// Create Universal: planetas, nubes, atmósferas, anillos y el Sol (alpha.4).
// Todo en el espacio del objeto: el planeta mide 1 de radio y su centro está en el origen.
//
// Params.x = modo: 0 superficie, 1 nubes, 2 atmósfera, 3 Sol, 4 brillo del Sol, 5 anillos, 6 punto lejano
// Params.y = luz ambiente, Params.z = brillo del Sol en el agua, Params.w = luces de ciudades (1) / sombra de anillos (2)
// Ring     = radio interior y exterior de los anillos, mitad a dibujar (0 detrás, 1 delante), radio de la atmósfera
//            (en el brillo del Sol, Ring.x = tamaño del cuadro en radios del Sol)

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform vec3 SunDir;
uniform vec3 CamPos;
uniform vec4 AtmoColor;
uniform vec4 Params;
uniform vec4 Ring;

in vec3 objPos;
in vec2 texCoord;

out vec4 fragColor;

const vec3 TWILIGHT = vec3(1.0, 0.45, 0.2);

// Rayo (origen o, dirección d unitaria) contra la esfera de radio r en el origen. x > y si no la toca.
vec2 sphereHit(vec3 o, vec3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float h = b * b - c;
    if (h < 0.0) {
        return vec2(1.0, -1.0);
    }
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

float ringDensity(float r) {
    float u = (r - Ring.x) / (Ring.y - Ring.x);
    if (u < 0.0 || u > 1.0) {
        return 0.0;
    }
    return texture(Sampler1, vec2(u, 0.5)).a;
}

void surface() {
    vec3 n = normalize(objPos);
    vec3 v = normalize(CamPos - objPos);
    vec3 albedo = texture(Sampler0, texCoord).rgb;
    float ndl = dot(n, SunDir);
    float ndv = max(dot(n, v), 0.0);
    float lambert = max(ndl, 0.0);
    // Los cuerpos sin aire (Luna, Mercurio...) reflejan casi igual en todo el disco: Lommel-Seeliger.
    float ls = lambert > 0.0 ? min(2.0 * lambert / (lambert + ndv + 1e-4), 1.3) : 0.0;
    float diffuse = AtmoColor.a > 0.0 ? lambert : mix(lambert, ls, 0.6);

    if (Params.w > 1.5 && abs(SunDir.y) > 1e-4) {
        // Sombra de los anillos sobre el planeta.
        float t = -n.y / SunDir.y;
        if (t > 0.0) {
            vec3 q = n + SunDir * t;
            diffuse *= 1.0 - 0.8 * ringDensity(length(q.xz));
        }
    }

    vec3 col = albedo * (diffuse + Params.y);

    if (AtmoColor.a > 0.0) {
        // Luz rojiza en la línea entre el día y la noche.
        float dusk = smoothstep(-0.12, 0.0, ndl) * (1.0 - smoothstep(0.0, 0.18, ndl));
        col += albedo * TWILIGHT * dusk * 0.35 * AtmoColor.a;
    }
    if (Params.z > 0.0 && ndl > 0.0) {
        // Brillo del Sol en el mar: solo donde el mapa es azul oscuro.
        float water = smoothstep(0.03, 0.10, albedo.b - max(albedo.r, albedo.g)) * (1.0 - smoothstep(0.25, 0.45, albedo.b));
        vec3 h = normalize(SunDir + v);
        float spec = pow(max(dot(n, h), 0.0), 70.0) * water * Params.z;
        col += vec3(1.0, 0.93, 0.8) * spec * smoothstep(0.0, 0.1, ndl);
    }
    if (Params.w > 0.5 && Params.w < 1.5) {
        // Luces de las ciudades en el lado de noche.
        float night = 1.0 - smoothstep(-0.12, 0.06, ndl);
        col += texture(Sampler1, texCoord).rgb * night * 1.15;
    }
    fragColor = vec4(col, 1.0);
}

void clouds() {
    vec3 n = normalize(objPos);
    vec4 c = texture(Sampler0, texCoord);
    float ndl = dot(n, SunDir);
    float light = max(ndl, 0.0) + Params.y;
    vec3 tint = mix(TWILIGHT, vec3(1.0), smoothstep(0.0, 0.25, ndl));
    fragColor = vec4(c.rgb * tint * light, c.a);
}

void atmosphere() {
    float ra = Ring.w;
    vec3 p = objPos * ra;
    vec3 o = CamPos;
    vec3 d = normalize(p - o);
    vec2 shell = sphereHit(o, d, ra);
    if (shell.x > shell.y) {
        discard;
    }
    float t0 = max(shell.x, 0.0);
    float t1 = shell.y;
    vec2 ground = sphereHit(o, d, 1.0);
    if (ground.x <= ground.y && ground.x > 0.0) {
        t1 = min(t1, ground.x);
    }
    float len = max(t1 - t0, 0.0);
    float depth = clamp(len / (2.0 * sqrt(ra * ra - 1.0)), 0.0, 1.0);
    vec3 mid = o + d * (t0 + t1) * 0.5;
    float sun = dot(normalize(mid), SunDir);
    float light = smoothstep(-0.35, 0.3, sun);
    // Mirando hacia el Sol a través del borde, la atmósfera se enciende (dispersión hacia delante).
    light += pow(max(dot(d, SunDir), 0.0), 12.0) * smoothstep(-0.4, 0.0, sun) * 0.8;
    vec3 color = mix(TWILIGHT, AtmoColor.rgb, smoothstep(-0.1, 0.35, sun));
    float glow = pow(depth, 1.3) * light * AtmoColor.a;
    fragColor = vec4(color * glow, 1.0);
}

void sunSurface() {
    vec3 n = normalize(objPos);
    vec3 v = normalize(CamPos - objPos);
    float mu = max(dot(n, v), 0.0);
    vec3 col = texture(Sampler0, texCoord).rgb;
    // Oscurecimiento del borde del Sol.
    col *= (0.5 + 0.5 * pow(mu, 0.55)) * 1.35;
    fragColor = vec4(col, 1.0);
}

void sunGlow() {
    float d = length(texCoord * 2.0 - 1.0);
    float r = max(d * Ring.x - 1.0, 0.0);
    float g = 0.6 * exp(-r * 1.4) + 0.14 * exp(-r * 0.18);
    g *= 1.0 - smoothstep(0.75, 1.0, d);
    fragColor = vec4(vec3(1.0, 0.86, 0.62) * g * AtmoColor.a, 1.0);
}

void rings() {
    float side = dot(objPos, CamPos);
    if (Ring.z < 0.5 ? side > 0.0 : side <= 0.0) {
        discard;
    }
    float r = length(objPos.xz);
    float u = (r - Ring.x) / (Ring.y - Ring.x);
    if (u < 0.0 || u > 1.0) {
        discard;
    }
    vec4 c = texture(Sampler0, vec2(u, 0.5));
    // Sombra del planeta sobre los anillos.
    float sd = dot(objPos, SunDir);
    float perp = length(objPos - SunDir * sd);
    float shadow = sd < 0.0 ? mix(0.06, 1.0, smoothstep(0.97, 1.03, perp)) : 1.0;
    // Vistos desde la cara sin sol, los anillos solo dejan pasar un poco de luz.
    float lit = (SunDir.y > 0.0) == (CamPos.y > 0.0) ? 1.0 : 0.3;
    float sunAngle = 0.35 + 0.65 * sqrt(abs(SunDir.y));
    fragColor = vec4(c.rgb * (lit * sunAngle * shadow + Params.y), c.a);
}

void farDot() {
    float d = length(texCoord * 2.0 - 1.0);
    float g = exp(-d * d * 4.0) * (1.0 - smoothstep(0.8, 1.0, d));
    fragColor = vec4(AtmoColor.rgb * g * AtmoColor.a, 1.0);
}

void main() {
    int mode = int(Params.x + 0.5);
    if (mode == 0) {
        surface();
    } else if (mode == 1) {
        clouds();
    } else if (mode == 2) {
        atmosphere();
    } else if (mode == 3) {
        sunSurface();
    } else if (mode == 4) {
        sunGlow();
    } else if (mode == 5) {
        rings();
    } else {
        farDot();
    }
}
