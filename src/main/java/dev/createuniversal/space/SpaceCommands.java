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
                            .executes(SpaceCommands::look))));
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
        player.changeDimension(new DimensionTransition(space, target, motion, yaw, pitch, DimensionTransition.DO_NOTHING));
        player.setDeltaMovement(motion);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        c.getSource().sendSuccess(() -> Component.translatable("create_universal.command.goto",
                Component.translatable("create_universal.body." + body.id()), Math.round(distance - body.radius())), false);
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

    private static float yaw(Vec3 v) {
        return (float) (Math.toDegrees(Math.atan2(v.z, v.x)) - 90.0);
    }

    private static float pitch(Vec3 v) {
        return (float) -Math.toDegrees(Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z)));
    }
}
