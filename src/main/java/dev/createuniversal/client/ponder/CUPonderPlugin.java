package dev.createuniversal.client.ponder;

import dev.createuniversal.CreateUniversal;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** Los Ponder de Create Universal (beta.1, D65): mantener W sobre el objeto. */
public class CUPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return CreateUniversal.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(CreateUniversal.id("ship_assistant")).addStoryBoard("ship_assistant", CUPonderScenes::shipAssistant);
        helper.forComponents(CreateUniversal.id("map_table")).addStoryBoard("map_table", CUPonderScenes::mapTable);
        helper.forComponents(CreateUniversal.id("holo_projector")).addStoryBoard("holo_projector", CUPonderScenes::holoProjector);
        helper.forComponents(CreateUniversal.id("gravity_stabilizer")).addStoryBoard("gravity_stabilizer", CUPonderScenes::gravityStabilizer);
        helper.forComponents(CreateUniversal.id("cruise_drive")).addStoryBoard("cruise_drive", CUPonderScenes::cruiseDrive);
        helper.forComponents(CreateUniversal.id("space_suit_tank"), CreateUniversal.id("space_chestplate")).addStoryBoard("space_suit_tank", CUPonderScenes::suitTank);
    }
}
