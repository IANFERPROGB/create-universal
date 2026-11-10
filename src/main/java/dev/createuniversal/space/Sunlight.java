package dev.createuniversal.space;

import dev.createuniversal.space.surface.PlanetSurface;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * La luz del Sol real en el espacio y en los astros, para las placas solares de otros mods (Create Power Grid). Minecraft solo tiene un sol que va de este a oeste según la hora; aquí sale de dónde está el Sol de verdad:
 *
 * <ul>
 *   <li><b>Dirección</b> en los ejes de Minecraft (X = este, Y = arriba, Z = sur). En el espacio, hacia el Sol desde ese
 *       punto; en un astro, en el cielo de ese sitio (latitud, longitud y hora del astro).</li>
 *   <li><b>Fuerza</b> en W/m²: 1.361 a la distancia de la Tierra, con la distancia real de cada planeta (las órbitas del
 *       mod están comprimidas, D73, así que se pasa a unidades astronómicas reales por tramos).</li>
 *   <li><b>Aire:</b> con la fórmula de masa de aire de Power Grid y lo que deja pasar cada atmósfera (Venus casi nada,
 *       Titán poco, Marte casi todo). Sin aire no se pierde nada.</li>
 *   <li><b>Sombra:</b> de noche, o si un planeta o una luna tapa el Sol (eclipse), cero.</li>
 * </ul>
 */
public final class Sunlight {
    public static final double SOLAR_CONSTANT = 1361.0;

    /** @param atmosphere hay cielo que dispersa luz; @param ground hay suelo que refleja */
    public record Sun(Vector3d dir, double watts, boolean atmosphere, boolean ground) {}

    /** Órbitas del mod (bloques) y su distancia real al Sol (unidades astronómicas). */
    private static final double[][] AU = {
            {93_750, 0.387}, {187_500, 0.723}, {312_500, 1.0}, {468_750, 1.524},
            {718_750, 5.203}, {828_125, 9.537}, {921_875, 19.19}, {1_000_000, 30.07}};

    private Sunlight() {}

    /** ¿Es una dimensión del mod (espacio o un astro)? En el resto se deja la luz de Minecraft. */
    public static boolean handles(Level level) {
        return level.dimension() == CUDimensions.SPACE || PlanetSurface.byDimension(level.dimension()) != null;
    }

    /** El Sol visto desde ese punto (coordenadas del mundo, fuera de las naves), o null fuera del espacio y los astros. */
    public static Sun at(Level level, Vec3 pos) {
        double time = SpaceLayout.time(level);
        SolarSystem.Body sunBody = SolarSystem.get("sun");
        Vec3 sunPos = SolarSystem.position(sunBody, time);
        if (level.dimension() == CUDimensions.SPACE) {
            Vec3 d = sunPos.subtract(pos);
            double watts = SOLAR_CONSTANT / sq(au(d.length()));
            if (eclipsed(pos, sunPos, null, time)) {
                watts = 0.0;
            }
            Vec3 n = d.normalize();
            return new Sun(new Vector3d(n.x, n.y, n.z), watts, false, false);
        }
        PlanetSurface surface = PlanetSurface.byDimension(level.dimension());
        if (surface == null) {
            return null;
        }
        SolarSystem.Body body = surface.body();
        Vec3 center = SolarSystem.position(body, time);
        Vector3d[] axes = SolarSystem.horizonAxes(body, time, surface.latitude(pos.z), surface.longitude(pos.x));
        Vec3 toSun = sunPos.subtract(center).normalize();
        Vector3d dir = new Vector3d(dot(toSun, axes[0]), dot(toSun, axes[1]), dot(toSun, axes[2]));
        SolarSystem.Body planet = body.parent() == null || "sun".equals(body.parent()) ? body : SolarSystem.get(body.parent());
        double watts = SOLAR_CONSTANT / sq(au(SolarSystem.position(planet, time).distanceTo(sunPos)));
        boolean air = !surface.airless();
        double sinElevation = dir.y;
        if (sinElevation <= 0.0) {
            watts = 0.0;
        } else if (air) {
            watts *= Math.pow(clearness(surface), Math.pow(airMass(sinElevation), 0.678));
        }
        Vec3 up = new Vec3(axes[1].x, axes[1].y, axes[1].z);
        if (watts > 0.0 && eclipsed(center.add(up.scale(body.radius() * 1.001)), sunPos, body, time)) {
            watts = 0.0;
        }
        return new Sun(dir, watts, air, true);
    }

    /**
     * El ángulo del sol de Minecraft que más se le parece en ese sitio (0 = cénit, positivo hacia el oeste), para lo que
     * solo entiende el sol de Minecraft: la altura del Sol es la buena y el lado (este u oeste) también.
     */
    public static double vanillaAngle(Sun sun) {
        double fromZenith = Math.acos(Mth.clamp(sun.dir().y, -1.0, 1.0));
        return sun.dir().x > 0.0 ? -fromZenith : fromZenith;
    }

    /** Masa de aire (Kasten y Young, la misma que usa Power Grid). */
    private static double airMass(double sinElevation) {
        double elevationDeg = Math.toDegrees(Math.asin(sinElevation));
        return Math.max(1.0, 1.0 / (sinElevation + 0.50572 * Math.pow(elevationDeg + 6.07995, -1.6364)));
    }

    /** Qué parte de la luz deja pasar la atmósfera con el Sol en el cénit. */
    private static double clearness(PlanetSurface surface) {
        return switch (surface.bodyId()) {
            case "venus" -> 0.03;
            case "titan" -> 0.1;
            case "mars" -> 0.9;
            default -> 0.7;
        };
    }

    /** ¿Tapa algún astro el Sol visto desde {@code from}? */
    private static boolean eclipsed(Vec3 from, Vec3 sunPos, SolarSystem.Body self, double time) {
        Vec3 d = sunPos.subtract(from);
        double length = d.length();
        Vec3 n = d.scale(1.0 / length);
        for (SolarSystem.Body b : SolarSystem.bodies()) {
            if (b == self || b.parent() == null) {
                continue;
            }
            Vec3 c = SolarSystem.position(b, time).subtract(from);
            double t = Mth.clamp(c.dot(n), 0.0, length);
            if (c.subtract(n.scale(t)).lengthSqr() < b.radius() * b.radius()) {
                return true;
            }
        }
        return false;
    }

    /** Distancia del mod (bloques) a distancia real (unidades astronómicas), por tramos entre órbitas. */
    static double au(double blocks) {
        if (blocks <= AU[0][0]) {
            return Math.max(0.05, AU[0][1] * blocks / AU[0][0]);
        }
        for (int i = 1; i < AU.length; i++) {
            if (blocks <= AU[i][0]) {
                double f = (blocks - AU[i - 1][0]) / (AU[i][0] - AU[i - 1][0]);
                return AU[i - 1][1] + f * (AU[i][1] - AU[i - 1][1]);
            }
        }
        double[] last = AU[AU.length - 1];
        return last[1] * blocks / last[0];
    }

    private static double dot(Vec3 a, Vector3d b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    private static double sq(double v) {
        return v * v;
    }
}
