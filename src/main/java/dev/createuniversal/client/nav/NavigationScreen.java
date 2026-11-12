package dev.createuniversal.client.nav;

import dev.createuniversal.client.effects.DebrisMarkers;
import dev.createuniversal.client.sky.SpaceClock;
import dev.createuniversal.network.DebrisMarkersPayload;
import dev.createuniversal.network.NavEditPayload;
import dev.createuniversal.space.CUDimensions;
import dev.createuniversal.space.Navigation;
import dev.createuniversal.space.SolarSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * El mapa del sistema solar (alpha.11, D46–D49): visto desde arriba (norte = −Z arriba), con las órbitas, los astros en
 * su sitio de ahora, el jugador, sus marcadores y los restos de naves.
 *
 * <ul>
 *   <li>Rueda: zoom (hacia el cursor). Arrastrar: moverse. C: volver al jugador.</li>
 *   <li>Clic: elegir un astro o marcador (distancia, llegada a tu velocidad, próximo acercamiento).</li>
 *   <li>Clic derecho en un hueco: marcador nuevo. Supr: borrar el marcador elegido. Intro: fijarlo como destino.</li>
 * </ul>
 */
public class NavigationScreen extends Screen {
    private static final int BG = 0xF0060912;
    private static final int ORBIT = 0x55FFFFFF;
    private static final int MARKER = 0xFF58E1FF;
    private static final int DEBRIS = 0xFFFF8A3D;
    private static final int PLAYER = 0xFFFFFFFF;
    private static final int SELECT = 0xFFFFE066;
    private static final Map<String, Integer> COLORS = new HashMap<>();

    static {
        COLORS.put("sun", 0xFFFFD27A);
        COLORS.put("mercury", 0xFF9A9A9A);
        COLORS.put("venus", 0xFFE8D2A0);
        COLORS.put("earth", 0xFF6FA8FF);
        COLORS.put("moon", 0xFFCFCFCF);
        COLORS.put("mars", 0xFFD0704A);
        COLORS.put("jupiter", 0xFFD9C7A8);
        COLORS.put("saturn", 0xFFE6D6A8);
        COLORS.put("uranus", 0xFF9FDCE8);
        COLORS.put("neptune", 0xFF5D84E6);
        COLORS.put("titan", 0xFFD9A24A);
        COLORS.put("io", 0xFFE0D070);
    }

    /** Centro de la vista y escala (bloques por píxel). Se recuerdan entre aperturas. */
    private static double cx = Double.NaN;
    private static double cz;
    private static double scale = 40.0;
    private static boolean follow = true;

    // Movimiento suave (como el mapa de Xaero): zoom que se desliza, inercia al soltar y vuelta suave al jugador.
    private static double targetScale = 40.0;
    /** Ancho (píxeles) de la pantalla la última vez: con la escala da los bloques que abarca la vista (para la mesa). */
    private static double lastWidth = 427.0;
    private boolean zoomAnchored;
    private double anchorWorldX;
    private double anchorWorldZ;
    private double anchorPx;
    private double anchorPy;
    /** Inercia del arrastre (píxeles por segundo). */
    private double velX;
    private double velY;
    private double dragVX;
    private double dragVY;
    private long lastDragNanos;
    private boolean mouseDown;
    /** Volviendo al jugador (tras pulsar C): se acerca deslizándose y luego lo sigue. */
    private boolean catching;
    private long lastFrameNanos;

    private String selected;
    private String approachText;
    private String approachFor;
    private boolean dragged;
    private EditBox nameBox;
    private Button destinationButton;
    private Button deleteButton;

    /** Lo dibujado en este fotograma, para saber qué hay bajo el ratón. */
    private final List<Object[]> hits = new ArrayList<>();

    public NavigationScreen() {
        super(Component.translatable("create_universal.nav.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        double time = time();
        if (Double.isNaN(cx) || follow) {
            Vec3 p = NavClient.playerPosition(time);
            cx = p.x;
            cz = p.z;
        }
        nameBox = new EditBox(font, 10, 0, 150, 16, Component.translatable("create_universal.nav.marker_name"));
        nameBox.setMaxLength(32);
        nameBox.setVisible(false);
        addRenderableWidget(nameBox);
        destinationButton = addRenderableWidget(Button.builder(Component.translatable("create_universal.nav.set_destination"), b -> toggleDestination())
                .bounds(10, 0, 150, 18).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("create_universal.nav.delete_marker"), b -> deleteSelected())
                .bounds(10, 0, 150, 18).build());
        updateWidgets();
    }

    private static double time() {
        return SpaceClock.renderTime(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
    }

    // ---------------------------------------------------------------- coordenadas

    private double sx(double x) {
        return width / 2.0 + (x - cx) / scale;
    }

    private double sy(double z) {
        return height / 2.0 + (z - cz) / scale;
    }

    private double wx(double px) {
        return cx + (px - width / 2.0) * scale;
    }

    private double wz(double py) {
        return cz + (py - height / 2.0) * scale;
    }

    // ---------------------------------------------------------------- dibujo

    /** El mapa entero va en el fondo: así los botones quedan por encima. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
        drawMap(g);
    }

    private void drawMap(GuiGraphics g) {
        double time = time();
        Vec3 player = NavClient.playerPosition(time);
        animate(player);
        lastWidth = width;
        hits.clear();

        // Cinturón de asteroides (dos círculos tenues).
        Vec3 sun = SolarSystem.position(SolarSystem.get("sun"), time);
        dottedCircle(g, sun.x, sun.z, 546_875, 0x30C8A878);
        dottedCircle(g, sun.x, sun.z, 640_625, 0x30C8A878);

        for (SolarSystem.Body body : SolarSystem.bodies()) {
            if (body.parent() == null) {
                continue;
            }
            boolean known = NavClient.isDiscovered(body.id());
            double orbitPx = body.orbitRadius() / scale;
            if (orbitPx < 5.0) {
                continue;
            }
            if (known) {
                orbit(g, body, time, orbitPx);
            }
        }
        for (SolarSystem.Body body : SolarSystem.bodies()) {
            drawBody(g, body, time);
        }
        for (DebrisMarkersPayload.Marker m : DebrisMarkers.markers()) {
            if (m.dimension().equals(CUDimensions.SPACE.location()) && m.expires() > Minecraft.getInstance().level.getGameTime()) {
                int x = (int) sx(m.x());
                int y = (int) sy(m.z());
                g.drawCenteredString(font, "✕", x, y - 4, DEBRIS);
            }
        }
        for (Navigation.Marker m : NavClient.markers()) {
            int x = (int) sx(m.x());
            int y = (int) sy(m.z());
            String key = "marker:" + m.id();
            g.drawCenteredString(font, "◆", x, y - 4, key.equals(selected) ? SELECT : MARKER);
            g.drawString(font, m.name(), x + 6, y - 4, MARKER);
            hits.add(new Object[] {key, x, y, 6});
        }

        // El jugador y hacia dónde va (un minuto de camino).
        int px = (int) sx(player.x);
        int py = (int) sy(player.z);
        Vec3 v = NavClient.velocity();
        if (v.lengthSqr() > 1.0) {
            dottedLine(g, px, py, sx(player.x + v.x * 60.0), sy(player.z + v.z * 60.0), 0x99FFFFFF);
        }
        g.fill(px - 3, py, px + 4, py + 1, PLAYER);
        g.fill(px, py - 3, px + 1, py + 4, PLAYER);
        g.drawString(font, Component.translatable("create_universal.nav.you"), px + 5, py + 2, PLAYER);

        drawInfo(g, time, player);
        drawFooter(g);
    }

    private void drawBody(GuiGraphics g, SolarSystem.Body body, double time) {
        boolean known = NavClient.isDiscovered(body.id());
        if (body.parent() != null && body.orbitRadius() / scale < 5.0 && !body.parent().equals("sun")) {
            return;
        }
        Vec3 p = SolarSystem.position(body, time);
        double x = sx(p.x);
        double y = sy(p.z);
        int r = (int) Math.max(known ? 2 : 1, Math.min(2000, body.radius() / scale));
        if (x + r < 0 || y + r < 0 || x - r > width || y - r > height) {
            return;
        }
        String key = "body:" + body.id();
        int color = known ? COLORS.getOrDefault(body.id(), 0xFFB8B8B8) : 0xFF707070;
        disc(g, (int) x, (int) y, r, color);
        if (body.hasRings() && known && body.radius() * body.ringOuter() / scale > 4) {
            dottedCircle(g, p.x, p.z, body.radius() * body.ringInner(), 0x60E6D6A8);
            dottedCircle(g, p.x, p.z, body.radius() * body.ringOuter(), 0x60E6D6A8);
        }
        if (key.equals(selected)) {
            ring(g, (int) x, (int) y, r + 3, SELECT);
        }
        Component label = known ? Component.translatable("create_universal.body." + body.id()) : Component.literal("?");
        g.drawString(font, label, (int) x + r + 3, (int) y - 4, known ? 0xFFDDDDDD : 0xFF888888);
        hits.add(new Object[] {key, (int) x, (int) y, Math.max(6, r)});
    }

    /** La vista del mapa para la mesa: centro (x, z) y bloques que abarca de lado a lado. */
    public static double[] view(Vec3 player) {
        double x = follow || Double.isNaN(cx) ? player.x : cx;
        double z = follow || Double.isNaN(cx) ? player.z : cz;
        return new double[] {x, z, targetScale * lastWidth};
    }

    public static int colorOf(String body) {
        return COLORS.getOrDefault(body, 0xFFB8B8B8);
    }

    /** Un paso de la animación de la vista: zoom, inercia y seguir al jugador, según el tiempo real entre fotogramas. */
    private void animate(Vec3 player) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0.0 : Math.min(0.1, (now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;
        if (Math.abs(scale - targetScale) / targetScale > 1.0e-4) {
            double k = 1.0 - Math.exp(-dt * 14.0);
            scale = Math.exp(Math.log(scale) + (Math.log(targetScale) - Math.log(scale)) * k);
        } else {
            scale = targetScale;
            zoomAnchored = false;
        }
        if (zoomAnchored && !follow) {
            // Lo que había bajo el cursor sigue bajo el cursor mientras se desliza el zoom.
            cx = anchorWorldX - (anchorPx - width / 2.0) * scale;
            cz = anchorWorldZ - (anchorPy - height / 2.0) * scale;
        }
        if (!mouseDown && Math.abs(velX) + Math.abs(velY) > 2.0) {
            cx -= velX * dt * scale;
            cz -= velY * dt * scale;
            double decay = Math.exp(-dt * 4.5);
            velX *= decay;
            velY *= decay;
            if (zoomAnchored) {
                anchorWorldX -= velX * dt * scale;
                anchorWorldZ -= velY * dt * scale;
            }
        }
        if (follow) {
            if (catching) {
                double k = 1.0 - Math.exp(-dt * 9.0);
                cx += (player.x - cx) * k;
                cz += (player.z - cz) * k;
                if (Math.hypot(player.x - cx, player.z - cz) / scale < 0.5) {
                    catching = false;
                }
            } else {
                cx = player.x;
                cz = player.z;
            }
        }
    }

    /** La órbita: la posición del astro a lo largo de una vuelta (así sale con su inclinación de verdad). */
    private void orbit(GuiGraphics g, SolarSystem.Body body, double time, double orbitPx) {
        SolarSystem.Body parent = SolarSystem.get(body.parent());
        Vec3 parentNow = SolarSystem.position(parent, time);
        double period = Math.abs(body.periodDays()) * 24000.0;
        int n = (int) Math.max(24, Math.min(720, 2.0 * Math.PI * orbitPx / 3.0));
        for (int k = 0; k < n; k++) {
            double t = time + period * k / n;
            Vec3 rel = SolarSystem.position(body, t).subtract(SolarSystem.position(parent, t));
            int x = (int) sx(parentNow.x + rel.x);
            int y = (int) sy(parentNow.z + rel.z);
            if (x >= 0 && y >= 0 && x < width && y < height) {
                g.fill(x, y, x + 1, y + 1, ORBIT);
            }
        }
    }

    private void dottedCircle(GuiGraphics g, double wxc, double wzc, double radius, int color) {
        double rPx = radius / scale;
        if (rPx < 3) {
            return;
        }
        int n = (int) Math.max(24, Math.min(1440, 2.0 * Math.PI * rPx / 4.0));
        double x0 = sx(wxc);
        double y0 = sy(wzc);
        for (int k = 0; k < n; k++) {
            double a = 2.0 * Math.PI * k / n;
            int x = (int) (x0 + Math.cos(a) * rPx);
            int y = (int) (y0 + Math.sin(a) * rPx);
            if (x >= 0 && y >= 0 && x < width && y < height) {
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
    }

    private void dottedLine(GuiGraphics g, double x0, double y0, double x1, double y1, int color) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        int n = (int) Math.min(400, len / 3.0);
        for (int k = 0; k <= n; k++) {
            double f = n == 0 ? 0 : (double) k / n;
            int x = (int) (x0 + (x1 - x0) * f);
            int y = (int) (y0 + (y1 - y0) * f);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void disc(GuiGraphics g, int x, int y, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fill(x - w, y + dy, x + w + 1, y + dy + 1, color);
        }
    }

    private static void ring(GuiGraphics g, int x, int y, int r, int color) {
        int n = Math.max(16, r * 6);
        for (int k = 0; k < n; k++) {
            double a = 2.0 * Math.PI * k / n;
            int px = (int) (x + Math.cos(a) * r);
            int py = (int) (y + Math.sin(a) * r);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** Ficha de lo elegido: nombre, distancia, llegada y próximo acercamiento. */
    private void drawInfo(GuiGraphics g, double time, Vec3 player) {
        int y = 8;
        g.drawString(font, title, 10, y, 0xFFFFFFFF);
        y += 14;
        if (selected == null) {
            g.drawString(font, Component.translatable("create_universal.nav.hint_select"), 10, y, 0xFF9AA4B4);
            layoutWidgets(y + 14);
            return;
        }
        double[] t = NavClient.target(selected, time);
        if (t == null) {
            selected = null;
            updateWidgets();
            return;
        }
        g.drawString(font, NavClient.targetName(selected), 10, y, SELECT);
        y += 12;
        double[] a = NavClient.approach(player, NavClient.velocity(), t);
        g.drawString(font, Component.translatable("create_universal.nav.distance", NavClient.formatDistance(a[0])), 10, y, 0xFFDDDDDD);
        y += 10;
        g.drawString(font, Component.translatable("create_universal.nav.closing", String.format(Locale.ROOT, "%.1f", a[1])), 10, y, 0xFFDDDDDD);
        y += 10;
        g.drawString(font, Component.translatable("create_universal.nav.eta", NavClient.formatTime(a[2])), 10, y, 0xFFDDDDDD);
        y += 10;
        if (selected.startsWith("body:")) {
            String text = approach(selected.substring(5), time, player);
            if (text != null) {
                g.drawString(font, Component.literal(text), 10, y, 0xFFB8C8E0);
                y += 10;
            }
        }
        layoutWidgets(y + 6);
    }

    /** "Próximo acercamiento" entre el astro donde estás (o en cuya zona estás) y el elegido. Se calcula una vez. */
    private String approach(String target, double time, Vec3 player) {
        if (target.equals(approachFor)) {
            return approachText;
        }
        approachFor = target;
        approachText = null;
        Minecraft mc = Minecraft.getInstance();
        SolarSystem.Body home;
        if (mc.level != null && mc.level.dimension() == CUDimensions.SPACE) {
            home = SolarSystem.frameBody(player, time);
        } else {
            dev.createuniversal.space.surface.PlanetSurface s = mc.level != null
                    ? dev.createuniversal.space.surface.PlanetSurface.byDimension(mc.level.dimension()) : null;
            home = SolarSystem.get(s != null ? s.bodyId() : "earth");
        }
        SolarSystem.Body body = SolarSystem.get(target);
        if (home == null || body == null || home == body || body.isSun()) {
            return null;
        }
        double bestT = 0;
        double best = Double.POSITIVE_INFINITY;
        for (double d = 0; d <= 1200; d += 0.5) {
            double tt = time + d * 24000.0;
            double dist = SolarSystem.position(home, tt).distanceTo(SolarSystem.position(body, tt));
            if (dist < best) {
                best = dist;
                bestT = d;
            }
        }
        approachText = Component.translatable("create_universal.nav.next_approach",
                Component.translatable("create_universal.body." + home.id()), String.format(Locale.ROOT, "%.1f", bestT),
                String.format(Locale.ROOT, "%.0f", bestT * 20.0 / 60.0), NavClient.formatDistance(best)).getString();
        return approachText;
    }

    private void drawFooter(GuiGraphics g) {
        g.drawString(font, Component.translatable("create_universal.nav.help"), 10, height - 12, 0xFF7A8496);
        String sc = scale >= 1000 ? String.format(Locale.ROOT, "1 px = %.1f k bloques", scale / 1000.0)
                : String.format(Locale.ROOT, "1 px = %.0f bloques", scale);
        g.drawString(font, sc, width - font.width(sc) - 10, height - 12, 0xFF7A8496);
        if (follow) {
            String f = Component.translatable("create_universal.nav.following").getString();
            g.drawString(font, f, width - font.width(f) - 10, height - 24, 0xFF7A8496);
        }
    }

    // ---------------------------------------------------------------- botones

    private void layoutWidgets(int y) {
        boolean marker = selected != null && selected.startsWith("marker:");
        nameBox.setY(y);
        if (marker) {
            y += 20;
        }
        destinationButton.setY(y);
        deleteButton.setY(y + 22);
    }

    private void updateWidgets() {
        boolean marker = selected != null && selected.startsWith("marker:");
        nameBox.setVisible(marker);
        if (marker) {
            Navigation.Marker m = NavClient.marker(selected.substring(7));
            nameBox.setValue(m != null ? m.name() : "");
        }
        destinationButton.visible = selected != null || NavClient.destination() != null;
        destinationButton.setMessage(selected != null && selected.equals(NavClient.destination())
                ? Component.translatable("create_universal.nav.clear_destination")
                : selected != null ? Component.translatable("create_universal.nav.set_destination")
                : Component.translatable("create_universal.nav.clear_destination"));
        deleteButton.visible = marker;
    }

    private void toggleDestination() {
        if (selected == null || selected.equals(NavClient.destination())) {
            NavClient.setDestination(null);
        } else {
            NavClient.setDestination(selected);
        }
        updateWidgets();
    }

    private void deleteSelected() {
        if (selected != null && selected.startsWith("marker:")) {
            PacketDistributor.sendToServer(new NavEditPayload(NavEditPayload.REMOVE, selected.substring(7), "", 0, 0, 0));
            if (selected.equals(NavClient.destination())) {
                NavClient.setDestination(null);
            }
            selected = null;
            updateWidgets();
        }
    }

    // ---------------------------------------------------------------- ratón y teclado

    private String hitAt(double mx, double my) {
        String best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Object[] h : hits) {
            double d = Math.hypot(mx - (int) h[1], my - (int) h[2]);
            if (d <= (int) h[3] + 2 && d < bestD) {
                bestD = d;
                best = (String) h[0];
            }
        }
        return best;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        dragged = false;
        if (button == 0) {
            mouseDown = true;
            velX = 0.0;
            velY = 0.0;
        }
        if (button == 1) {
            String hit = hitAt(mx, my);
            if (hit != null && hit.startsWith("marker:")) {
                selected = hit;
            } else {
                String name = Component.translatable("create_universal.nav.new_marker", NavClient.markers().size() + 1).getString();
                PacketDistributor.sendToServer(new NavEditPayload(NavEditPayload.ADD, "", name, wx(mx), SolarSystem.ECLIPTIC_Y, wz(my)));
            }
            updateWidgets();
            return true;
        }
        return button == 0;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && !nameBox.isFocused()) {
            cx -= dx * scale;
            cz -= dy * scale;
            follow = false;
            catching = false;
            zoomAnchored = false;
            dragged = true;
            long now = System.nanoTime();
            double dt = lastDragNanos == 0 ? 0.016 : Math.max(0.001, (now - lastDragNanos) / 1.0e9);
            lastDragNanos = now;
            dragVX = dragVX * 0.6 + dx / dt * 0.4;
            dragVY = dragVY * 0.6 + dy / dt * 0.4;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            mouseDown = false;
            // Al soltar, el mapa sigue deslizándose (si se estaba moviendo hace un momento).
            boolean recent = System.nanoTime() - lastDragNanos < 60_000_000L;
            velX = dragged && recent ? dragVX : 0.0;
            velY = dragged && recent ? dragVY : 0.0;
            dragVX = 0.0;
            dragVY = 0.0;
            lastDragNanos = 0;
        }
        if (button == 0 && !dragged && !overWidget(mx, my)) {
            selected = hitAt(mx, my);
            updateWidgets();
        }
        dragged = false;
        return super.mouseReleased(mx, my, button);
    }

    private boolean overWidget(double mx, double my) {
        return (nameBox.isVisible() && nameBox.isMouseOver(mx, my)) || (destinationButton.visible && destinationButton.isMouseOver(mx, my))
                || (deleteButton.visible && deleteButton.isMouseOver(mx, my));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        // El zoom va hacia la nueva escala poco a poco (ver animate), con lo de bajo el cursor quieto.
        if (!zoomAnchored || Math.hypot(mx - anchorPx, my - anchorPy) > 2.0) {
            anchorWorldX = wx(mx);
            anchorWorldZ = wz(my);
            anchorPx = mx;
            anchorPy = my;
        }
        zoomAnchored = true;
        targetScale = Math.max(0.2, Math.min(6000.0, targetScale * Math.pow(0.8, dy)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (nameBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (selected != null && selected.startsWith("marker:")) {
                    PacketDistributor.sendToServer(new NavEditPayload(NavEditPayload.RENAME, selected.substring(7), nameBox.getValue(), 0, 0, 0));
                }
                nameBox.setFocused(false);
                return true;
            }
            return super.keyPressed(key, scan, modifiers);
        }
        if (key == GLFW.GLFW_KEY_C) {
            follow = true;
            catching = true;
            velX = 0.0;
            velY = 0.0;
            zoomAnchored = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            deleteSelected();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && selected != null) {
            toggleDestination();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
