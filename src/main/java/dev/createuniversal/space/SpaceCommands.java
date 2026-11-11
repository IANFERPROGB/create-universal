package dev.createuniversal.space;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * Comandos para probar el sistema solar (necesitan permisos de operador):
 * <ul>
 *   <li>{@code /cu goto <astro>}: te lleva al espacio, cerca del astro y por su lado de día, moviéndote con él.</li>
 *   <li>{@code /cu look <astro>}: gira tu vista hacia el astro y dice a qué distancia está.</li>
 *   <li>{@code /cu land <astro> [latitud longitud]}: te deja en la superficie de la Luna o de Marte.</li>
 * </ul>
 */
public final class SpaceCommands {
    private SpaceCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        for (String root : new String[] {"cu", "createuniversal"}) {
            d.register(Commands.literal(root).requires(s -> s.hasPermission(2))
                    .then(Commands.literal("goto").then(Commands.argument("body", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(SolarSystem.bodies().stream().map(SolarSystem.Body::id), b))
                            .executes(SpaceCommands::goTo)))
                    .then(Commands.literal("look").then(Commands.argument("body", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(SolarSystem.bodies().stream().map(SolarSystem.Body::id), b))
                            .executes(SpaceCommands::look)))
                    .then(Commands.literal("rocks").then(Commands.argument("field", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(SpaceRocks.fields().stream().map(SpaceRocks.Field::id), b))
                            .executes(SpaceCommands::rocks)))
                    .then(Commands.literal("sendship").then(Commands.argument("dimension", net.minecraft.commands.arguments.DimensionArgument.dimension())
                            .executes(SpaceCommands::sendShip)))
                    .then(Commands.literal("land").then(Commands.argument("body", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                    dev.createuniversal.space.surface.PlanetSurface.ALL.stream().map(p -> p.bodyId()), b))
                            .executes(c -> land(c, Double.NaN, Double.NaN))
                            .then(Commands.argument("latitude", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-90, 90))
                                    .then(Commands.argument("longitude", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-180, 180))
                                            .executes(c -> land(c, com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "latitude"),
                                                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "longitude"))))))));
        }
    }

    private static SolarSystem.Body body(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "body").toLowerCase(Locale.ROOT);
        SolarSystem.Body body = SolarSystem.get(id);
        if (body == null) {
            c.getSource().sendFailure(Component.translatable("create_universal.command.unknown_body", id));
        }
        return body;
    }

    private static int goTo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        SolarSystem.Body body = body(c);
        ServerLevel space = player.server.getLevel(CUDimensions.SPACE);
        if (body == null || space == null) {
            return 0;
        }
        double time = SpaceLayout.time(space);
        Vec3 center = SolarSystem.position(body, time);
        Vec3 sun = SolarSystem.position(SolarSystem.get("sun"), time);
        // Por el lado de día, un poco girado para ver la línea entre el día y la noche, y algo por encima del ecuador.
        Vec3 toSun = body.isSun() ? new Vec3(1, 0, 0) : sun.subtract(center).normalize();
        double a = Math.toRadians(40.0);
        Vec3 dir = new Vec3(toSun.x * Math.cos(a) - toSun.z * Math.sin(a), 0.25, toSun.x * Math.sin(a) + toSun.z * Math.cos(a)).normalize();
        double distance = body.isSun() ? body.radius() * 4.0 : Math.max(body.radius() * 3.2, body.radius() + 400.0);
        Vec3 target = center.add(dir.scale(distance));
        Vec3 look = center.subtract(target);
        float yaw = yaw(look);
        float pitch = pitch(look);
        Vec3 motion = SolarSystem.velocity(body, time).scale(1.0 / 20.0);
        if (player.isPassenger()) {
            player.stopRiding();
        }
        SpaceGate.travel(() -> player.changeDimension(new DimensionTransition(space, target, motion, yaw, pitch, DimensionTransition.DO_NOTHING)));
        player.setDeltaMovement(motion);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        c.getSource().sendSuccess(() -> Component.translatable("create_universal.command.goto",
                Component.translatable("create_universal.body." + body.id()), Math.round(distance - body.radius())), false);
        return 1;
    }

    /**
     * {@code /cu rocks <campo>}: para probar la alpha.10. Te deja 20 bloques por encima de la roca más grande que haya
     * cerca del centro del campo (en tu lado del Sol o de Saturno), yendo ya a su velocidad.
     */
    private static int rocks(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(c, "field");
        SpaceRocks.Field field = SpaceRocks.fields().stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
        ServerLevel space = player.server.getLevel(CUDimensions.SPACE);
        if (field == null || space == null) {
            c.getSource().sendFailure(Component.literal("Campo desconocido: " + id));
            return 0;
        }
        double time = SpaceLayout.time(space);
        Vec3 probe = SpaceRocks.probePoint(field, time);
        SpaceRocks.Rock[] best = {null};
        SpaceRocks.near(probe, 600.0, time, rock -> {
            if (best[0] == null || rock.a() * rock.b() * rock.c() > best[0].a() * best[0].b() * best[0].c()) {
                best[0] = rock;
            }
        });
        if (best[0] == null) {
            c.getSource().sendFailure(Component.literal("No hay rocas por ahí (¿densidad 0?)"));
            return 0;
        }
        SpaceRocks.Rock rock = best[0];
        Vec3 pos = SpaceRocks.position(rock, time);
        Vec3 target = pos.add(0.0, Math.max(rock.a(), Math.max(rock.b(), rock.c())) + 20.0, 0.0);
        Vec3 motion = SpaceRocks.velocityOf(rock, time).scale(1.0 / 20.0);
        if (player.isPassenger()) {
            player.stopRiding();
        }
        SpaceGate.travel(() -> player.changeDimension(new DimensionTransition(space, target, motion, player.getYRot(), 90.0F,
                DimensionTransition.DO_NOTHING)));
        player.setDeltaMovement(motion);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        c.getSource().sendSuccess(() -> Component.literal("Encima de una roca de " + field.id() + " (radio " + Math.round(rock.a())
                + "), a " + Math.round(motion.length() * 20.0) + " b/s"), false);
        return 1;
    }

    private static int look(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        SolarSystem.Body body = body(c);
        if (body == null) {
            return 0;
        }
        if (player.level().dimension() != CUDimensions.SPACE) {
            c.getSource().sendFailure(Component.translatable("create_universal.command.not_in_space"));
            return 0;
        }
        double time = SpaceLayout.time(player.level());
        Vec3 eye = player.getEyePosition();
        Vec3 to = SolarSystem.position(body, time).subtract(eye);
        Vec3 motion = player.getDeltaMovement();
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw(to), pitch(to));
        player.setDeltaMovement(motion);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        double surface = to.length() - body.radius();
        double minutes = surface / 125.0 / 60.0;
        double degrees = Math.toDegrees(2.0 * Math.asin(Math.min(1.0, body.radius() / to.length())));
        c.getSource().sendSuccess(() -> Component.translatable("create_universal.command.look",
                Component.translatable("create_universal.body." + body.id()), Math.round(surface),
                String.format(Locale.ROOT, "%.1f", minutes), String.format(Locale.ROOT, "%.2f", degrees)), false);
        return 1;
    }

    /**
     * {@code /cu land <astro> [latitud longitud]}: te deja sobre la superficie, en el suelo real de ese punto. Sin
     * coordenadas, en el punto del astro que mira a la Tierra (en la Luna, la cara visible).
     */
    private static int land(CommandContext<CommandSourceStack> c, double latDeg, double lonDeg) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(c, "body").toLowerCase(Locale.ROOT);
        dev.createuniversal.space.surface.PlanetSurface surface = dev.createuniversal.space.surface.PlanetSurface.byBody(id);
        if (surface == null) {
            c.getSource().sendFailure(Component.translatable("create_universal.command.no_surface", Component.translatable("create_universal.body." + id)));
            return 0;
        }
        ServerLevel level = player.server.getLevel(surface.dimension());
        if (level == null) {
            return 0;
        }
        double lat = Double.isNaN(latDeg) ? 0.0 : Math.toRadians(latDeg);
        double lon = Double.isNaN(lonDeg) ? 0.0 : Math.toRadians(lonDeg);
        double x = surface.x(lon);
        double z = surface.z(lat);
        double y = dev.createuniversal.space.surface.PlanetTerrain.get(surface).surfaceY(x, z) + 3.0;
        if (player.isPassenger()) {
            player.stopRiding();
        }
        SpaceGate.travel(() -> player.changeDimension(new DimensionTransition(level, new Vec3(x + 0.5, y, z + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(),
                DimensionTransition.DO_NOTHING)));
        c.getSource().sendSuccess(() -> Component.translatable("create_universal.command.land", Component.translatable("create_universal.body." + id),
                String.format(Locale.ROOT, "%.2f", Math.toDegrees(lat)), String.format(Locale.ROOT, "%.2f", Math.toDegrees(lon))), false);
        return 1;
    }

    /** Prueba: manda la nave más cercana (a menos de 32 bloques) a otra dimensión, como si subiera o bajara. */
    private static int sendShip(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerLevel target = net.minecraft.commands.arguments.DimensionArgument.getDimension(c, "dimension");
        ServerLevel level = player.serverLevel();
        dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        dev.ryanhcode.sable.sublevel.ServerSubLevel best = null;
        double bestDist = 32.0;
        if (container != null) {
            for (dev.ryanhcode.sable.sublevel.ServerSubLevel ship : container.getAllSubLevels()) {
                var b = ship.boundingBox();
                Vec3 center = new Vec3((b.minX() + b.maxX()) * 0.5, (b.minY() + b.maxY()) * 0.5, (b.minZ() + b.maxZ()) * 0.5);
                double d = center.distanceTo(player.position());
                if (!ship.isRemoved() && d < bestDist) {
                    best = ship;
                    bestDist = d;
                }
            }
        }
        if (best == null) {
            c.getSource().sendFailure(Component.literal("No hay ninguna nave a menos de 32 bloques"));
            return 0;
        }
        double time = SpaceLayout.time(target);
        Vec3 pos = target.dimension() == CUDimensions.SPACE ? SpaceLayout.arrivalPoint(time).add(0, 30, 0)
                : new Vec3(player.getX(), Math.min(target.getMaxBuildHeight() - 40, player.getY() + 20), player.getZ());
        boolean ok = ShipTransfer.transfer(best, target, pos, new net.minecraft.nbt.CompoundTag());
        dev.ryanhcode.sable.sublevel.ServerSubLevel moved = best;
        c.getSource().sendSuccess(() -> Component.literal("Nave " + moved.getUniqueId() + (ok ? " movida a " : " NO movida a ") + target.dimension().location()), false);
        return ok ? 1 : 0;
    }

    private static float yaw(Vec3 v) {
        return (float) (Math.toDegrees(Math.atan2(v.z, v.x)) - 90.0);
    }

    private static float pitch(Vec3 v) {
        return (float) -Math.toDegrees(Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z)));
    }
}
