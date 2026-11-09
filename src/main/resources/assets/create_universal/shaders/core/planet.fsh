#version 150

// Create Universal: planetas, nubes, atmósferas, anillos, el Sol y el cielo.
// Todo en el espacio del objeto: el astro mide 1 de radio y su centro está en el origen.
//
// Params   = modo, luz ambiente, brillo del agua, banderas (F_*)
//            modos: 0 superficie, 1 nubes, 2 atmósfera, 3 Sol, 4 corona, 5 anillos, 6 punto lejano,
//                   7 fondo negro, 8 Vía Láctea, 9 destello de lente
// Ring     = radio interior y exterior de los anillos, mitad a dibujar (0 detrás, 1 delante) / tamaños de los cuadros
// ScatterR = dispersión Rayleigh por radio del astro (RGB) y su altura de escala
// ScatterM = dispersión Mie, su altura de escala, asimetría g y radio del borde de la atmósfera (0 = sin atmósfera)
// Occluder = astro que puede hacer sombra (eclipses): centro y radio, en radios de este astro
// Anim     = segundos de juego, desplazamiento de las nubes, cizalla de las bandas, radio aparente del Sol (rad)
// Tint     = color y brillo para puntos, corona, destellos y exposición

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform vec3 SunDir;
uniform vec3 CamPos;
uniform vec4 Params;
uniform vec4 Ring;
uniform vec4 ScatterR;
uniform vec4 ScatterM;
uniform vec4 Occluder;
uniform vec4 Anim;
uniform vec4 Tint;

in vec3 objPos;
in vec2 texCoord;

out vec4 fragColor;

const int F_NIGHT = 1;
const int F_RING_SHADOW = 2;
const int F_CLOUDS = 4;
const int F_NORMAL = 8;
const int F_BUMP = 16;
const int F_BANDS = 32;
const int F_AURORA = 64;
const int F_RED_ECLIPSE = 128;

const float PI = 3.14159265;
const float SUN_I = 16.0;

bool has(int flag) {
    return (int(Params.w + 0.5) & flag) != 0;
}

// ---------------------------------------------------------------- ruido

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x),
                   mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x),
                   mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * noise(p);
        p *= 2.03;
        a *= 0.5;
    }
    return s;
}

// ---------------------------------------------------------------- utilidades

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

// Coordenadas del mapa calculadas por píxel desde la normal (sin la "estrella" de los polos de la malla), y sus
// derivadas corregidas en la costura de la longitud 180° para elegir bien el mipmap.
vec2 gDx;
vec2 gDy;

vec2 sphereUV(vec3 n) {
    return vec2(fract(atan(-n.z, n.x) / (2.0 * PI)), acos(clamp(n.y, -1.0, 1.0)) / PI);
}

void setGrad(vec2 uv) {
    gDx = dFdx(uv);
    gDy = dFdy(uv);
    gDx.x -= floor(gDx.x + 0.5);
    gDy.x -= floor(gDy.x + 0.5);
}

vec4 T(sampler2D tex, vec2 uv) {
    return textureGrad(tex, uv, gDx, gDy);
}

// Punto de la esfera para unas coordenadas del mapa (u = longitud hacia el este, v = de norte a sur).
vec3 spherePoint(vec2 uv) {
    float phi = uv.x * 2.0 * PI;
    float theta = uv.y * PI;
    return vec3(sin(theta) * cos(phi), cos(theta), -sin(theta) * sin(phi));
}

// Luz del Sol que llega al suelo tras cruzar la atmósfera (masa de aire de Kasten-Young): atardeceres rojizos.
vec3 sunTransmittance(float mu) {
    if (ScatterM.w <= 1.0) {
        return vec3(1.0);
    }
    float z = degrees(acos(clamp(mu, -1.0, 1.0)));
    float airmass = 1.0 / (max(mu, 0.0) + 0.50572 * pow(max(96.07995 - z, 0.01), -1.6364));
    vec3 tau = ScatterR.rgb * ScatterR.w + vec3(ScatterM.x * ScatterM.y * 1.1);
    return exp(-tau * min(airmass, 60.0));
}

// Sombra de otro astro (eclipses), con penumbra según el tamaño aparente del Sol.
vec3 eclipse(vec3 p, vec3 L) {
    if (Occluder.w <= 0.0) {
        return vec3(1.0);
    }
    vec3 toOcc = Occluder.xyz - p;
    float t = dot(toOcc, L);
    if (t <= 0.0) {
        return vec3(1.0);
    }
    float d = length(toOcc - L * t);
    float pen = max(t * Anim.w, 1e-4);
    float lit = smoothstep(Occluder.w - pen, Occluder.w + pen, d);
    // En un eclipse de Luna la Tierra deja pasar luz roja por su atmósfera.
    vec3 umbra = has(F_RED_ECLIPSE) ? vec3(0.32, 0.08, 0.03) * 0.5 : vec3(0.0);
    return mix(umbra, vec3(1.0), lit);
}

float ringDensity(sampler2D tex, float r) {
    float u = (r - Ring.x) / (Ring.y - Ring.x);
    if (u < 0.0 || u > 1.0) {
        return 0.0;
    }
    return texture(tex, vec2(u, 0.5)).a;
}

// Nubes que cambian con el tiempo: la cobertura del mapa crece y mengua por zonas.
float cloudCoverage(sampler2D tex, vec2 uv) {
    float a = T(tex, uv).a;
    vec3 p = spherePoint(uv);
    float grow = fbm(p * 4.0 + vec3(Anim.x * 0.0035, -Anim.x * 0.002, Anim.x * 0.0025));
    return clamp(a * (0.55 + 0.95 * grow), 0.0, 1.0);
}

// ---------------------------------------------------------------- superficie

void surface() {
    vec3 n = normalize(objPos);
    vec3 east = normalize(vec3(n.z, 0.0, -n.x) + vec3(1e-6, 0.0, 0.0));
    vec3 north = cross(n, east);
    vec3 v = normalize(CamPos - objPos);
    vec3 L = SunDir;
    vec2 uv = sphereUV(n);
    setGrad(uv);
    float lat = asin(clamp(n.y, -1.0, 1.0));

    if (has(F_BANDS)) {
        // Rotación diferencial de los gigantes gaseosos: el ecuador y cada chorro van a su velocidad.
        uv.x += Anim.z * (0.6 * exp(-lat * lat / 0.03) + 0.25 * sin(lat * 14.0));
    }
    vec3 albedo = T(Sampler0, uv).rgb;
    if (has(F_BANDS)) {
        albedo *= 0.97 + 0.06 * noise(vec3(lat * 300.0, n.x * 4.0, n.z * 4.0));
    }

    vec3 nb = n;
    if (has(F_NORMAL)) {
        vec3 tn = T(Sampler2, uv).xyz * 2.0 - 1.0;
        nb = normalize(east * tn.x + north * tn.y + n * tn.z);
    } else if (has(F_BUMP)) {
        vec2 ts = max(1.0 / vec2(textureSize(Sampler0, 0)), abs(gDx) + abs(gDy));
        float hx = dot(T(Sampler0, uv + vec2(ts.x, 0.0)).rgb - T(Sampler0, uv - vec2(ts.x, 0.0)).rgb, vec3(0.333));
        float hy = dot(T(Sampler0, uv - vec2(0.0, ts.y)).rgb - T(Sampler0, uv + vec2(0.0, ts.y)).rgb, vec3(0.333));
        nb = normalize(n - (east * hx + north * hy) * 1.4);
    }

    float geo = dot(n, L);
    float ndl = dot(nb, L);
    float ndv = max(dot(nb, v), 0.0);
    float lambert = max(ndl, 0.0) * smoothstep(-0.02, 0.06, geo);
    float diffuse = lambert;
    if (ScatterM.w <= 1.0) {
        // Sin aire (Luna, Mercurio...): Lommel-Seeliger y el brillo extra de la Luna llena.
        float ls = lambert > 0.0 ? min(2.0 * lambert / (lambert + ndv + 1e-3), 1.4) : 0.0;
        float surge = 1.0 + 0.25 * pow(max(dot(L, v), 0.0), 30.0);
        diffuse = mix(lambert, ls, 0.6) * surge;
    }
    vec3 light = vec3(diffuse) * sunTransmittance(geo) * eclipse(n, L);

    if (has(F_RING_SHADOW) && abs(L.y) > 1e-4) {
        float t = -n.y / L.y;
        if (t > 0.0) {
            vec3 q = n + L * t;
            light *= 1.0 - 0.8 * ringDensity(Sampler1, length(q.xz));
        }
    }

    float cloudHere = 0.0;
    if (has(F_CLOUDS)) {
        vec2 cuv = uv + vec2(Anim.y, 0.0);
        cloudHere = cloudCoverage(Sampler2, cuv);
        // Sombra de las nubes: donde el rayo de sol cruza la capa de nubes.
        vec3 Lt = L - n * geo;
        float off = 0.006 / max(geo, 0.15);
        vec2 suv = cuv + vec2(dot(Lt, east) * off / (2.0 * PI * max(cos(lat), 0.1)), -dot(Lt, north) * off / PI);
        light *= 1.0 - 0.55 * cloudCoverage(Sampler2, suv);
    }

    vec3 col = albedo * (light + Params.y);

    if (Params.z > 0.0) {
        // Mar: reflejo del Sol (nítido y uno más ancho) y el cielo reflejado al mirar de lado.
        float water = smoothstep(0.03, 0.10, albedo.b - max(albedo.r, albedo.g)) * (1.0 - smoothstep(0.25, 0.45, albedo.b));
        vec3 h = normalize(L + v);
        float nh = max(dot(n, h), 0.0);
        float spec = pow(nh, 1400.0) * 3.0 + pow(nh, 90.0) * 0.18;
        float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
        col += water * Params.z * (spec * light * (1.0 - cloudHere) * vec3(1.0, 0.95, 0.85) + fres * vec3(0.05, 0.11, 0.24) * light);
    }
    if (has(F_NIGHT)) {
        // Luces de las ciudades en el lado de noche, tapadas en parte por las nubes.
        float night = 1.0 - smoothstep(-0.10, 0.05, geo);
        col += T(Sampler1, uv).rgb * night * 1.25 * (1.0 - 0.85 * cloudHere);
    }
    fragColor = vec4(col, 1.0);
}

void clouds() {
    vec3 n = normalize(objPos);
    vec3 L = SunDir;
    vec2 uv = sphereUV(n);
    setGrad(uv);
    float a = cloudCoverage(Sampler0, uv);
    float geo = dot(n, L);
    vec3 light = vec3(max(geo * 0.85 + 0.15, 0.0) * smoothstep(-0.12, 0.08, geo)) * sunTransmittance(geo) * eclipse(n, L);
    vec3 col = vec3(0.97, 0.98, 1.0) * (light + Params.y) * (0.82 + 0.18 * a);
    fragColor = vec4(col, a);
}

// ---------------------------------------------------------------- atmósfera (dispersión simple de Rayleigh y Mie)

vec3 auroraAt(vec3 p, float h) {
    float hn = h / (ScatterM.w - 1.0);
    if (hn < 0.2 || hn > 0.95) {
        return vec3(0.0);
    }
    vec3 n = normalize(p);
    float night = 1.0 - smoothstep(-0.25, 0.05, dot(n, SunDir));
    float lat = abs(asin(clamp(n.y, -1.0, 1.0)));
    float band = exp(-pow((lat - 1.17) / 0.07, 2.0));
    if (band < 0.01 || night < 0.01) {
        return vec3(0.0);
    }
    float lon = atan(n.z, n.x);
    float curtain = fbm(vec3(lon * 9.0, lat * 25.0, Anim.x * 0.06));
    curtain = pow(smoothstep(0.42, 0.78, curtain), 2.0);
    vec3 c = mix(vec3(0.15, 1.0, 0.4), vec3(0.85, 0.15, 0.35), smoothstep(0.55, 0.95, hn));
    return c * band * curtain * night * (1.0 - hn) * 14.0;
}

void atmosphere() {
    float top = ScatterM.w;
    vec3 o = CamPos;
    vec3 d = normalize(objPos * top - o);
    vec2 shell = sphereHit(o, d, top);
    if (shell.x > shell.y || shell.y < 0.0) {
        discard;
    }
    float t0 = max(shell.x, 0.0);
    float t1 = shell.y;
    vec2 ground = sphereHit(o, d, 1.0);
    if (ground.x <= ground.y && ground.x > 0.0) {
        t1 = min(t1, ground.x);
    }
    vec3 L = SunDir;
    vec3 bR = ScatterR.rgb;
    float hR = ScatterR.w;
    float bM = ScatterM.x;
    float hM = ScatterM.y;
    const int STEPS = 12;
    const int LIGHT_STEPS = 4;
    float ds = (t1 - t0) / float(STEPS);
    float odR = 0.0;
    float odM = 0.0;
    vec3 sumR = vec3(0.0);
    vec3 sumM = vec3(0.0);
    vec3 aurora = vec3(0.0);
    bool auroras = has(F_AURORA);
    for (int i = 0; i < STEPS; i++) {
        vec3 p = o + d * (t0 + (float(i) + 0.5) * ds);
        float h = length(p) - 1.0;
        float dr = exp(-h / hR) * ds;
        float dm = exp(-h / hM) * ds;
        odR += dr;
        odM += dm;
        vec2 lg = sphereHit(p, L, 1.0);
        if (!(lg.x <= lg.y && lg.x > 0.0)) {
            float lds = sphereHit(p, L, top).y / float(LIGHT_STEPS);
            float lR = 0.0;
            float lM = 0.0;
            for (int j = 0; j < LIGHT_STEPS; j++) {
                float lh = length(p + L * (float(j) + 0.5) * lds) - 1.0;
                lR += exp(-lh / hR) * lds;
                lM += exp(-lh / hM) * lds;
            }
            vec3 att = exp(-(bR * (odR + lR) + bM * 1.1 * (odM + lM))) * eclipse(p, L);
            sumR += att * dr;
            sumM += att * dm;
        }
        if (auroras) {
            aurora += auroraAt(p, h) * ds * exp(-bR * odR);
        }
    }
    float mu = dot(d, L);
    float g = ScatterM.z;
    float pR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float pM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu)) / ((2.0 + g * g) * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
    vec3 inscatter = SUN_I * (sumR * bR * pR + sumM * bM * pM) * Tint.a + aurora;
    inscatter = 1.0 - exp(-inscatter);
    vec3 trans = exp(-(bR * odR + bM * 1.1 * odM));
    // Mezcla: fondo × transmitancia + luz dispersada (función de mezcla ONE, SRC_ALPHA).
    fragColor = vec4(inscatter, dot(trans, vec3(0.3333)));
}

// ---------------------------------------------------------------- Sol

void sunSurface() {
    vec3 n = normalize(objPos);
    vec3 v = normalize(CamPos - objPos);
    float mu = max(dot(n, v), 0.0);
    vec2 uv = sphereUV(n);
    setGrad(uv);
    vec3 a = T(Sampler0, uv + vec2(Anim.x * 0.0004, 0.0)).rgb;
    vec3 b = T(Sampler0, uv + vec2(-Anim.x * 0.0003, 0.013)).rgb;
    vec3 col = mix(a, b, 0.5 + 0.5 * sin(Anim.x * 0.25));
    col *= 0.85 + 0.3 * fbm(n * 45.0 + vec3(Anim.x * 0.05));
    float limb = 1.0 - 0.4 * (1.0 - pow(mu, 0.6));
    col = col * limb * vec3(1.0, 0.92 + 0.08 * mu, 0.8 + 0.2 * mu) * 2.2 + vec3(0.25, 0.22, 0.15) * mu;
    fragColor = vec4(col, 1.0);
}

void sunCorona() {
    vec2 q = texCoord * 2.0 - 1.0;
    float d = length(q);
    float r = max(d * Ring.x - 1.0, 0.0);
    float ang = atan(q.y, q.x);
    float streak = 0.7 + 0.6 * fbm(vec3(cos(ang) * 3.0, sin(ang) * 3.0, Anim.x * 0.02));
    float corona = 1.4 * exp(-r * 2.0) * streak + 0.3 * exp(-r * 0.35) + 0.06 * exp(-r * 0.06);
    // Rayos de difracción, como en las fotos del Sol desde el espacio.
    float spikes = pow(abs(cos(ang * 2.0)), 60.0) + pow(abs(cos(ang * 2.0 + 0.785)), 90.0) * 0.5;
    corona += spikes * 0.5 * exp(-r * 0.25);
    corona *= 1.0 - smoothstep(0.8, 1.0, d);
    fragColor = vec4(vec3(1.0, 0.85, 0.62) * corona * Tint.a, 1.0);
}

// ---------------------------------------------------------------- anillos

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
    vec3 L = SunDir;
    float sd = dot(objPos, L);
    float perp = length(objPos - L * sd);
    float shadow = sd < 0.0 ? mix(0.05, 1.0, smoothstep(0.97, 1.03, perp)) : 1.0;
    vec3 v = normalize(CamPos - objPos);
    float lit;
    if ((L.y > 0.0) == (CamPos.y > 0.0)) {
        lit = 0.35 + 0.65 * sqrt(abs(L.y));
    } else {
        // Cara sin sol: solo la luz que atraviesa, más fuerte a contraluz.
        lit = (0.15 + 0.6 * pow(max(dot(-v, L), 0.0), 3.0)) * (1.0 - c.a * 0.6);
    }
    fragColor = vec4(c.rgb * (lit * shadow * 1.6 + Params.y), c.a);
}

// ---------------------------------------------------------------- cielo y efectos

void farDot() {
    float d = length(texCoord * 2.0 - 1.0);
    float g = exp(-d * d * 4.0) * (1.0 - smoothstep(0.8, 1.0, d));
    fragColor = vec4(Tint.rgb * g * Tint.a, 1.0);
}

void milkyWay() {
    vec3 d = normalize(objPos);
    // Minecraft (y = norte de la eclíptica) -> eclíptica -> ecuatorial (oblicuidad 23,44°).
    vec3 ecl = vec3(d.x, -d.z, d.y);
    const float E = 0.40910518;
    vec3 eq = vec3(ecl.x, ecl.y * cos(E) - ecl.z * sin(E), ecl.y * sin(E) + ecl.z * cos(E));
    float ra = atan(eq.y, eq.x);
    float dec = asin(clamp(eq.z, -1.0, 1.0));
    vec2 uv = vec2(fract(0.5 - ra / (2.0 * PI)), 0.5 - dec / PI);
    float lod = log2(max(fwidth(uv.y) * float(textureSize(Sampler0, 0).y), 1.0));
    vec3 c = textureLod(Sampler0, uv, lod).rgb;
    fragColor = vec4(c * Tint.a, 1.0);
}

void lensFlare() {
    vec2 q = texCoord * 2.0 - 1.0;
    float d = length(q);
    float shape = smoothstep(1.0, 0.75, d) * (0.35 + 0.65 * smoothstep(0.2, 0.95, d));
    fragColor = vec4(Tint.rgb * shape * Tint.a, 1.0);
}

// Nave lejana vista desde un planeta: caja iluminada por el Sol (cada cara con su luz).
void solidBox() {
    vec3 a = abs(objPos);
    vec3 n = a.x > a.y && a.x > a.z ? vec3(sign(objPos.x), 0.0, 0.0) : (a.y > a.z ? vec3(0.0, sign(objPos.y), 0.0) : vec3(0.0, 0.0, sign(objPos.z)));
    float l = max(dot(n, SunDir), 0.0);
    fragColor = vec4(Tint.rgb * (l * 0.95 + 0.04) * Tint.a, 1.0);
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
        sunCorona();
    } else if (mode == 5) {
        rings();
    } else if (mode == 6) {
        farDot();
    } else if (mode == 7) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else if (mode == 8) {
        milkyWay();
    } else if (mode == 10) {
        solidBox();
    } else {
        lensFlare();
    }
}
