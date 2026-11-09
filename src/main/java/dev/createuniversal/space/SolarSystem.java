package dev.createuniversal.space;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * El sistema solar de Create Universal (secciones 4.4 y 4.5, D11, D12, D73).
 *
 * <ul>
 *   <li>Escala D73: 1 bloque ≈ 6 km para tamaños y para el sistema Tierra–Luna; órbitas alrededor del Sol comprimidas
 *       (escala B) pero en su orden real. La Luna a 8,5 min yendo a 125 bloques/s.</li>
 *   <li>Tiempo: el {@code dayTime} del Overworld (24.000 ticks = 1 día de Minecraft), así dormir o {@code /time} mueven
 *       los planetas. Año terrestre = 100 días MC; el resto de periodos en proporción real (Kepler).</li>
 *   <li>Las lunas de los gigantes están más cerca de su planeta que en la realidad para que quepan entre órbitas: misma
 *       ordenación y proporciones aproximadas, periodos reales × 0,274 (como la Luna: 27,3 días → 7,48 días MC).</li>
 *   <li>Plano de las órbitas: horizontal, a la altura {@link #ECLIPTIC_Y}. +Y es el norte de la eclíptica y +X el punto
 *       vernal, así las estrellas del catálogo de la NASA quedan donde están de verdad.</li>
 * </ul>
 * Todo es determinista y se calcula igual en servidor y cliente a partir del tiempo, sin sincronizar nada.
 */
public final class SolarSystem {
    /** Altura del plano de las órbitas: deja la órbita baja de llegada sobre la Tierra cerca de Y = 56. */
    public static final double ECLIPTIC_Y = -1470.0;
    private static final double TICKS_PER_DAY = 24000.0;
    /** Periodo de las lunas: días reales × este factor (27,32 días de la Luna → 7,48 días MC). */
    private static final double MOON_PERIOD_FACTOR = 7.48 / 27.32;

    /**
     * @param id            nombre interno (y textura {@code textures/planet/<id>_hi.png})
     * @param parent        astro alrededor del que orbita (null = el Sol, en el centro)
     * @param orbitRadius   bloques
     * @param periodDays    días MC por vuelta (negativo = retrógrado)
     * @param radius        bloques
     * @param rotationDays  días MC por giro sobre sí mismo (negativo = retrógrado; 0 = cara fija hacia su planeta)
     * @param tiltDeg       inclinación del eje
     * @param orbitTiltDeg  inclinación del plano de su órbita
     * @param phase         posición inicial en la órbita (radianes)
     * @param atmoColor     color del halo de atmósfera (0 = sin atmósfera visible)
     * @param atmoStrength  intensidad del halo
     * @param nightLights   luces de ciudades en el lado de noche
     * @param clouds        capa de nubes que se mueve
     * @param rings         anillos (radios interior y exterior en radios del planeta)
     */
    public record Body(String id, String parent, double orbitRadius, double periodDays, double radius, double rotationDays,
                       double tiltDeg, double orbitTiltDeg, double phase, int atmoColor, float atmoStrength,
                       boolean nightLights, boolean clouds, float ringInner, float ringOuter) {
        public boolean isSun() {
            return parent == null && orbitRadius == 0.0;
        }

        public boolean hasRings() {
            return ringOuter > 0.0F;
        }
    }

    private static final List<Body> BODIES = new ArrayList<>();
    private static final Map<String, Body> BY_ID = new HashMap<>();

    static {
        // Sol (reducido: a escala real taparía medio cielo) y planetas. Radios de órbita y periodos: sección 4.4 (D73).
        add(new Body("sun", null, 0, 1, 6250, 25.4, 7.25, 0, 0, 0, 0, false, false, 0, 0));
        add(new Body("mercury", "sun", 93_750, 24.1, 405, 58.6, 0.03, 7.0, 1.1, 0, 0, false, false, 0, 0));
        add(new Body("venus", "sun", 187_500, 61.5, 1004, -243.0, 2.6, 3.4, 4.0, 0xe8d2a0, 0.55F, false, false, 0, 0));
        add(new Body("earth", "sun", 312_500, 100.0, 1056, 1.0, 23.44, 0.0, 0.0, 0x6fa8ff, 0.85F, true, true, 0, 0));
        add(new Body("mars", "sun", 468_750, 188.1, 562, 1.03, 25.2, 1.85, 2.3, 0xe0a07a, 0.35F, false, false, 0, 0));
        add(new Body("jupiter", "sun", 718_750, 1186.0, 11_594, 0.41, 3.1, 1.3, 5.2, 0xd9c7a8, 0.25F, false, false, 0, 0));
        add(new Body("saturn", "sun", 828_125, 2946.0, 9_657, 0.44, 26.7, 2.5, 0.7, 0xe6d6a8, 0.25F, false, false, 1.24F, 2.27F));
        add(new Body("uranus", "sun", 921_875, 8400.0, 4_206, -0.72, 97.8, 0.8, 3.3, 0x9fdce8, 0.45F, false, false, 0, 0));
        add(new Body("neptune", "sun", 1_000_000, 16480.0, 4_084, 0.67, 28.3, 1.8, 1.9, 0x5d84e6, 0.45F, false, false, 0, 0));

        // Lunas. rotationDays = 0: cara fija hacia su planeta (acoplamiento de marea).
        add(new Body("moon", "earth", 63_750, 7.48, 288, 0, 1.5, 5.1, 0.6, 0, 0, false, false, 0, 0));
        add(new Body("phobos", "mars", 1_555, 0.319 * MOON_PERIOD_FACTOR, 12, 0, 0, 1.1, 0.3, 0, 0, false, false, 0, 0));
        add(new Body("deimos", "mars", 3_891, 1.263 * MOON_PERIOD_FACTOR, 8, 0, 0, 1.8, 2.7, 0, 0, false, false, 0, 0));
        add(new Body("io", "jupiter", 18_090, 1.769 * MOON_PERIOD_FACTOR, 302, 0, 0, 3.1, 0.4, 0, 0, false, false, 0, 0));
        add(new Body("europa", "jupiter", 22_720, 3.551 * MOON_PERIOD_FACTOR, 259, 0, 0, 3.6, 2.1, 0, 0, false, false, 0, 0));
        add(new Body("ganymede", "jupiter", 30_140, 7.155 * MOON_PERIOD_FACTOR, 437, 0, 0, 3.3, 4.0, 0, 0, false, false, 0, 0));
        add(new Body("callisto", "jupiter", 45_000, 16.69 * MOON_PERIOD_FACTOR, 400, 0, 0, 3.4, 5.5, 0, 0, false, false, 0, 0));
        // Las de Saturno, por fuera de los anillos (que acaban a 2,27 radios = 21.900 bloques).
        add(new Body("mimas", "saturn", 23_400, 0.942 * MOON_PERIOD_FACTOR, 33, 0, 0, 26.7, 0.5, 0, 0, false, false, 0, 0));
        add(new Body("enceladus", "saturn", 25_600, 1.370 * MOON_PERIOD_FACTOR, 42, 0, 0, 26.7, 1.0, 0, 0, false, false, 0, 0));
        add(new Body("tethys", "saturn", 28_300, 1.888 * MOON_PERIOD_FACTOR, 88, 0, 0, 26.7, 2.6, 0, 0, false, false, 0, 0));
        add(new Body("dione", "saturn", 31_200, 2.737 * MOON_PERIOD_FACTOR, 93, 0, 0, 26.7, 4.2, 0, 0, false, false, 0, 0));
        add(new Body("rhea", "saturn", 34_600, 4.518 * MOON_PERIOD_FACTOR, 127, 0, 0, 26.7, 5.6, 0, 0, false, false, 0, 0));
        add(new Body("titan", "saturn", 40_500, 15.95 * MOON_PERIOD_FACTOR, 427, 0, 0, 26.7, 3.4, 0xd9a24a, 0.7F, false, false, 0, 0));
        add(new Body("iapetus", "saturn", 47_000, 79.32 * MOON_PERIOD_FACTOR, 122, 0, 0, 15.5, 0.9, 0, 0, false, false, 0, 0));
        add(new Body("miranda", "uranus", 9_970, 1.413 * MOON_PERIOD_FACTOR, 39, 0, 0, 97.8, 0.2, 0, 0, false, false, 0, 0));
        add(new Body("ariel", "uranus", 13_380, 2.520 * MOON_PERIOD_FACTOR, 96, 0, 0, 97.8, 1.5, 0, 0, false, false, 0, 0));
        add(new Body("umbriel", "uranus", 17_460, 4.144 * MOON_PERIOD_FACTOR, 97, 0, 0, 97.8, 3.0, 0, 0, false, false, 0, 0));
        add(new Body("titania", "uranus", 26_880, 8.706 * MOON_PERIOD_FACTOR, 131, 0, 0, 97.8, 4.4, 0, 0, false, false, 0, 0));
        add(new Body("oberon", "uranus", 34_910, 13.46 * MOON_PERIOD_FACTOR, 126, 0, 0, 97.8, 5.9, 0, 0, false, false, 0, 0));
        add(new Body("triton", "neptune", 24_500, -5.877 * MOON_PERIOD_FACTOR, 224, 0, 0, 157.0, 2.2, 0, 0, false, false, 0, 0));
    }

    private SolarSystem() {}

    private static void add(Body body) {
        BODIES.add(body);
        BY_ID.put(body.id(), body);
    }

    public static List<Body> bodies() {
        return Collections.unmodifiableList(BODIES);
    }

    public static Body get(String id) {
        return BY_ID.get(id);
    }

    /** Posición en la dimensión espacio en el instante {@code time} (dayTime del Overworld, con decimales). */
    public static Vec3 position(Body body, double time) {
        if (body.parent() == null) {
            return new Vec3(0.0, ECLIPTIC_Y, 0.0);
        }
        Vec3 parent = position(BY_ID.get(body.parent()), time);
        double angle = body.phase() + Math.PI * 2.0 * time / (body.periodDays() * TICKS_PER_DAY);
        // Sentido antihorario visto desde arriba (+Y = norte de la eclíptica), como en el sistema solar real.
        double x = Math.cos(angle) * body.orbitRadius();
        double z = -Math.sin(angle) * body.orbitRadius();
        double tilt = Math.toRadians(body.orbitTiltDeg());
        double y = -z * Math.sin(tilt);
        z = z * Math.cos(tilt);
        return parent.add(x, y, z);
    }

    public static Vec3 position(String id, double time) {
        return position(BY_ID.get(id), time);
    }

    /** Velocidad en bloques/segundo (derivada numérica de la órbita). */
    public static Vec3 velocity(Body body, double time) {
        return position(body, time + 1.0).subtract(position(body, time - 1.0)).scale(0.5 * 20.0);
    }

    /**
     * Zona de influencia: dentro, lo que flota se mueve con el astro (recibe su misma aceleración orbital, ver
     * {@link #frameAcceleration}). Planetas: 6 % de su órbita (Tierra 18.750 bloques); lunas: 15 % de la suya (Luna 9.560).
     */
    public static double influenceRadius(Body body) {
        if (body.parent() == null) {
            return Double.POSITIVE_INFINITY;
        }
        return body.orbitRadius() * ("sun".equals(body.parent()) ? 0.06 : 0.15);
    }

    /** El astro más pequeño en cuya zona de influencia está {@code pos}; null = ninguno (solo el Sol, que no se mueve). */
    public static Body frameBody(Vec3 pos, double time) {
        Body best = null;
        double bestRadius = Double.POSITIVE_INFINITY;
        for (Body body : BODIES) {
            double soi = influenceRadius(body);
            if (soi < bestRadius && position(body, time).distanceToSqr(pos) < soi * soi) {
                best = body;
                bestRadius = soi;
            }
        }
        return best;
    }

    /** Aceleración orbital del astro en bloques/s² (órbitas circulares: −ω²·r, sumando la de su planeta). */
    public static Vec3 acceleration(Body body, double time) {
        if (body == null || body.parent() == null) {
            return Vec3.ZERO;
        }
        Body parent = BY_ID.get(body.parent());
        Vec3 rel = position(body, time).subtract(position(parent, time));
        double omega = Math.PI * 2.0 / (Math.abs(body.periodDays()) * TICKS_PER_DAY / 20.0);
        return rel.scale(-omega * omega).add(acceleration(parent, time));
    }

    /**
     * Aceleración que reciben las naves y jugadores que flotan en {@code pos}: la del astro cuya zona ocupan. Sin gravedad
     * (eso llega con las órbitas de verdad), así quien se queda quieto junto a la Tierra o la Luna no se va quedando atrás
     * mientras el astro gira. Es diminuta (Luna: 0,03 bloques/s²), no se nota al pilotar.
     */
    public static Vec3 frameAcceleration(Vec3 pos, double time) {
        return acceleration(frameBody(pos, time), time);
    }

    /**
     * Ángulo de giro sobre su eje (radianes, entre 0 y 2π). Con el mapa equirectangular, la longitud 0 queda hacia −X
     * antes de girar. Las lunas con la cara fija miran siempre a su planeta con su cara visible (longitud 0). La Tierra
     * gira con el día de Minecraft: a mediodía (dayTime 6000) la longitud 0 mira al Sol, así el día y la noche del
     * Overworld coinciden con lo que se ve desde el espacio.
     */
    public static double spinAngle(Body body, double time) {
        double angle;
        if (body.rotationDays() == 0.0 && body.parent() != null) {
            Vec3 toParent = position(BY_ID.get(body.parent()), time).subtract(position(body, time));
            angle = Math.atan2(-toParent.z, toParent.x) + Math.PI;
        } else if ("earth".equals(body.id())) {
            Vec3 toSun = position(BY_ID.get("sun"), time).subtract(position(body, time));
            double dayPart = ((time - 6000.0) % TICKS_PER_DAY) / TICKS_PER_DAY;
            angle = Math.atan2(toSun.z, -toSun.x) + Math.PI * 2.0 * dayPart;
        } else {
            double turns = (time / (body.rotationDays() * TICKS_PER_DAY)) % 1.0;
            angle = Math.PI * 2.0 * turns;
        }
        angle %= Math.PI * 2.0;
        return angle < 0.0 ? angle + Math.PI * 2.0 : angle;
    }

    /**
     * Orientación del astro: eje inclinado hacia −Z (como el de la Tierra hacia la estrella Polar en este cielo) y giro
     * sobre ese eje. Lleva un punto del astro (en su espacio propio, radio 1) a la dirección en el espacio.
     */
    public static Quaterniond orientation(Body body, double time, boolean spin, double extraSpin) {
        Quaterniond q = new Quaterniond().rotationX(Math.toRadians(-body.tiltDeg()));
        if (spin) {
            q.rotateY((spinAngle(body, time) + extraSpin) % (Math.PI * 2.0));
        }
        return q;
    }

    /**
     * Punto del astro (radio 1, espacio propio) para una latitud y longitud en radianes. Mismo convenio que los mapas
     * equirectangulares: longitud 0 hacia −X, positiva hacia el este; latitud positiva hacia +Y (norte).
     */
    public static Vector3d surfacePoint(double lat, double lon) {
        double phi = lon + Math.PI;
        return new Vector3d(Math.cos(lat) * Math.cos(phi), Math.sin(lat), -Math.cos(lat) * Math.sin(phi));
    }

    /** Latitud y longitud (radianes) de una dirección en el espacio propio del astro. */
    public static double[] latLon(Vector3d local) {
        Vector3d n = new Vector3d(local).normalize();
        double lat = Math.asin(Math.max(-1.0, Math.min(1.0, n.y)));
        double lon = Math.atan2(-n.z, n.x) - Math.PI;
        if (lon < -Math.PI) {
            lon += Math.PI * 2.0;
        }
        return new double[] {lat, lon};
    }

    /**
     * Ejes del horizonte de alguien que está de pie en un astro, en coordenadas del espacio: este (+X de Minecraft),
     * arriba (+Y) y sur (+Z). Sirve para dibujar el cielo desde la superficie.
     */
    public static Vector3d[] horizonAxes(Body body, double time, double lat, double lon) {
        Quaterniond q = orientation(body, time, true, 0.0);
        Vector3d up = q.transform(surfacePoint(lat, lon));
        Vector3d axis = q.transform(new Vector3d(0, 1, 0));
        Vector3d north = new Vector3d(axis).sub(new Vector3d(up).mul(axis.dot(up)));
        if (north.lengthSquared() < 1e-12) {
            north = q.transform(new Vector3d(0, 0, -1));
        }
        north.normalize();
        Vector3d east = new Vector3d(north).cross(up).normalize();
        Vector3d south = new Vector3d(north).negate();
        return new Vector3d[] {east, up, south};
    }
}
