package dev.createuniversal.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RedstoneLampBlock;

/**
 * Escenas de Ponder (beta.1, D65). El texto en inglés va aquí (para el modo de edición de Ponder); el que se ve sale de
 * los archivos de idioma ({@code create_universal.ponder.<escena>.header / text_N}), en español e inglés.
 */
public final class CUPonderScenes {
    private CUPonderScenes() {}

    private static CreateSceneBuilder start(SceneBuilder builder, String id, String title) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);
        return scene;
    }

    public static void shipAssistant(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = start(builder, "ship_assistant", "The Ship Assistant");
        BlockPos block = util.grid().at(2, 1, 2);
        Selection shafts = util.select().fromTo(3, 1, 2, 4, 1, 2);
        Selection thrusters = util.select().fromTo(0, 1, 1, 0, 1, 3);

        scene.world().showSection(util.select().position(block), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(80).text("Placed on a ship, the Ship Assistant checks it every second: thrusters, fuel, energy, hull and air")
                .pointAt(util.vector().topOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(shafts, Direction.WEST);
        scene.idle(10);
        scene.world().setKineticSpeed(util.select().fromTo(2, 1, 2, 4, 1, 2), 64);
        scene.overlay().showText(70).text("Its gyroscope needs at least 64 RPM: with it, the assistant can turn the whole ship")
                .pointAt(util.vector().centerOf(3, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(80);

        scene.overlay().showControls(util.vector().blockSurface(block, Direction.UP), Pointing.DOWN, 60).rightClick();
        scene.overlay().showText(80).text("Right-click it to see the ship, set the top speed, fly to the map destination or explore")
                .pointAt(util.vector().topOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(thrusters, Direction.EAST);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.GREEN, "thrusters", thrusters, 80);
        scene.overlay().showText(80).text("The autopilot drives Create Propulsion thrusters. Touching their controls turns it off")
                .pointAt(util.vector().centerOf(0, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(2, 1, 0, 2, 1, 1), Direction.SOUTH);
        scene.idle(10);
        scene.world().modifyBlock(util.grid().at(2, 1, 0), s -> s.setValue(RedstoneLampBlock.LIT, true), false);
        scene.effects().indicateRedstone(util.grid().at(2, 1, 1));
        scene.overlay().showText(80).text("Its redstone output is the autopilot power: use it for engines it doesn't know (the \"Others\" list)")
                .pointAt(util.vector().centerOf(2, 1, 1)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        scene.markAsFinished();
    }

    public static void mapTable(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = start(builder, "map_table", "The Map Table");
        BlockPos center = util.grid().at(2, 1, 2);
        scene.world().showSection(util.select().position(center), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(70).text("The Map Table shows the solar system map on top")
                .pointAt(util.vector().topOf(center)).placeNearTarget().attachKeyFrame();
        scene.idle(80);
        scene.overlay().showControls(util.vector().blockSurface(center, Direction.UP), Pointing.DOWN, 60).rightClick();
        scene.overlay().showText(70).text("Right-click it to open the map: bodies, markers, debris, parked ships and your destination")
                .pointAt(util.vector().topOf(center)).placeNearTarget();
        scene.idle(80);
        scene.world().showSection(util.select().fromTo(1, 1, 1, 3, 1, 3).substract(util.select().position(center)), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(80).text("Tables placed in a square (up to 24x24) join into one big map")
                .pointAt(util.vector().topOf(1, 1, 1)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        scene.markAsFinished();
    }

    public static void holoProjector(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = start(builder, "holo_projector", "The Holographic Projector");
        BlockPos block = util.grid().at(2, 1, 2);
        scene.world().showSection(util.select().position(block), Direction.DOWN);
        scene.idle(20);
        scene.overlay().showText(70).text("The Holographic Projector shows the solar system in 3D above it")
                .pointAt(util.vector().topOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(80);
        scene.overlay().showControls(util.vector().blockSurface(block, Direction.UP), Pointing.DOWN, 60).rightClick();
        scene.overlay().showText(60).text("Right-click it to open the map")
                .pointAt(util.vector().topOf(block)).placeNearTarget();
        scene.idle(70);
        scene.overlay().showControls(util.vector().blockSurface(block, Direction.UP), Pointing.DOWN, 60).rightClick().whileSneaking();
        scene.overlay().showText(80).text("Shift + right-click changes its size, its view (map, whole system or around you) and the names")
                .pointAt(util.vector().topOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        scene.markAsFinished();
    }

    public static void gravityStabilizer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = start(builder, "gravity_stabilizer", "The Gravity Stabilizer");
        BlockPos block = util.grid().at(2, 1, 2);
        scene.world().showSection(util.select().position(block), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(80).text("The Gravity Stabilizer sticks to a face: gravity pulls towards that face. On a ship, it goes on the floor")
                .pointAt(util.vector().topOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        scene.world().setKineticSpeed(util.select().fromTo(2, 0, 2, 2, 1, 2), 128);
        scene.overlay().showText(80).text("It needs at least 128 RPM, from behind, and only works inside a closed room")
                .pointAt(util.vector().centerOf(2, 0, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        scene.overlay().showControls(util.vector().blockSurface(block, Direction.UP), Pointing.DOWN, 60).scroll();
        scene.overlay().showText(70).text("Scroll on it to set the strength, from 5 to 150 % of Earth's gravity")
                .pointAt(util.vector().topOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(80);
        scene.markAsFinished();
    }

    public static void cruiseDrive(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = start(builder, "cruise_drive", "The Cruise Drive");
        BlockPos block = util.grid().at(2, 1, 2);
        Selection thrusters = util.select().fromTo(0, 1, 1, 0, 1, 3);
        scene.world().showSection(util.select().position(block), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(3, 1, 2, 4, 1, 2), Direction.WEST);
        scene.idle(10);
        scene.world().setKineticSpeed(util.select().fromTo(2, 1, 2, 4, 1, 2), 128);
        scene.overlay().showText(70).text("The Cruise Drive is for long trips. It needs at least 128 RPM and a lot of stress")
                .pointAt(util.vector().topOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(80);
        scene.world().showSection(thrusters, Direction.EAST);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.GREEN, "thrusters", thrusters, 80);
        scene.overlay().showText(80).text("In space, far from bodies and rock fields, the ship's thrusters push four times harder")
                .pointAt(util.vector().centerOf(0, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        scene.overlay().showText(80).text("Near a planet, a moon or a rock field it stands by on its own: brake in time!")
                .pointAt(util.vector().topOf(block)).placeNearTarget().colored(PonderPalette.RED);
        scene.idle(90);
        scene.markAsFinished();
    }

    public static void suitTank(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = start(builder, "space_suit_tank", "Refilling the Space Suit");
        BlockPos block = util.grid().at(2, 1, 2);
        scene.world().showSection(util.select().position(block), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(70).text("The space suit chestplate is also its air tank, and it can be placed as a block")
                .pointAt(util.vector().centerOf(block)).placeNearTarget().attachKeyFrame();
        scene.idle(80);
        scene.world().showSection(util.select().fromTo(2, 2, 2, 2, 3, 2), Direction.DOWN);
        scene.idle(10);
        scene.world().setKineticSpeed(util.select().fromTo(2, 2, 2, 2, 3, 2), 64);
        scene.overlay().showText(80).text("With rotation from above it refills, like Create's backtank...")
                .pointAt(util.vector().centerOf(2, 2, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        scene.overlay().showText(80).text("...but only where there is air: on Earth or in a room with oxygen")
                .pointAt(util.vector().centerOf(block)).placeNearTarget().colored(PonderPalette.RED);
        scene.idle(90);
        scene.markAsFinished();
    }
}
