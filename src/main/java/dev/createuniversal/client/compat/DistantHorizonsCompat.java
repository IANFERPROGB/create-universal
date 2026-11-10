package dev.createuniversal.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import dev.createuniversal.CreateUniversal;
import dev.createuniversal.space.CUDimensions;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Distant Horizons en el espacio: allí no hay terreno lejano que dibujar, y DH pintaba una capa
 * azul como un mar hacia el horizonte. En el espacio no dibuja nada (ni su niebla) y no calcula nada: su generador de
 * terreno lejano se cambia por uno que devuelve vacío al momento. En la Tierra, la Luna y los planetas funciona normal.
 *
 * Solo se carga si DH está instalado ({@code ModList.isLoaded("distanthorizons")}).
 */
public final class DistantHorizonsCompat {
    private static final String SPACE = CUDimensions.SPACE.location().toString();

    private DistantHorizonsCompat() {}

    private static boolean inSpace() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == CUDimensions.SPACE;
    }

    public static void register() {
        DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                if (inSpace()) {
                    event.cancelEvent();
                }
            }
        });
        DhApiEventRegister.on(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiBeforeFogRenderEvent.EventParam> event) {
                if (inSpace()) {
                    event.cancelEvent();
                }
            }
        });
        DhApiEventRegister.on(DhApiBeforeGenericObjectRenderEvent.class, new DhApiBeforeGenericObjectRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiBeforeGenericObjectRenderEvent.EventParam> event) {
                if (inSpace()) {
                    event.cancelEvent();
                }
            }
        });
        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
                IDhApiLevelWrapper level = event.value.levelWrapper;
                if (level != null && SPACE.equals(level.getDimensionName())) {
                    var result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(level, new EmptyGenerator());
                    CreateUniversal.LOGGER.info("Distant Horizons en el espacio: generador vacío ({})",
                            result.success ? "puesto" : result.message);
                }
            }
        });
        CreateUniversal.LOGGER.info("Distant Horizons: desactivado en el espacio");
    }

    /** No genera nada: el espacio está vacío. */
    private static final class EmptyGenerator implements IDhApiWorldGenerator {
        @Override
        public EDhApiWorldGeneratorReturnType getReturnType() {
            return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
        }

        @Override
        public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detailLevel,
                                                   IDhApiFullDataSource pooledFullDataSource, EDhApiDistantGeneratorMode generatorMode,
                                                   ExecutorService worldGeneratorThreadPool, Consumer<IDhApiFullDataSource> resultConsumer) {
            resultConsumer.accept(pooledFullDataSource);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void preGeneratorTaskStart() {
        }

        @Override
        public void close() {
        }
    }
}
