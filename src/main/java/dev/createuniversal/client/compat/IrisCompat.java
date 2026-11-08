package dev.createuniversal.client.compat;

import dev.createuniversal.CreateUniversal;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Iris con un paquete de shaders (Complementary, BSL...) no deja dibujar con shaders de otros mods mientras pinta el mundo
 * (con {@code allowUnknownShaders=false}, su valor por defecto, les apaga la escritura de color) y además repinta el cielo
 * con el suyo. Por eso, con un paquete activo, el cielo del espacio se dibuja después de que Iris termine
 * ({@code GameRendererSpaceSkyMixin}), solo donde no hay nada delante. Para saber dónde hay algo se usa la profundidad sin
 * translúcidos de Iris ({@code depthtex1}): así los planetas se ven también a través de los cristales de la nave.
 *
 * Todo por reflexión: Iris es opcional.
 */
public final class IrisCompat {
    private static boolean initialized;
    private static Object api;
    private static Method isShaderPackInUse;
    private static Method getPipelineManager;
    private static Method getPipelineNullable;
    private static Field renderTargets;
    private static Method getDepthNoTranslucents;
    private static Method getTextureId;
    private static boolean warned;

    private IrisCompat() {}

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded("iris")) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = apiClass.getMethod("getInstance").invoke(null);
            isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");
        } catch (ReflectiveOperationException | RuntimeException e) {
            CreateUniversal.LOGGER.warn("No se pudo enlazar con la API de Iris: el cielo del espacio no se adaptará a los shaders", e);
            api = null;
        }
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            getPipelineManager = iris.getMethod("getPipelineManager");
            getPipelineNullable = Class.forName("net.irisshaders.iris.pipeline.PipelineManager").getMethod("getPipelineNullable");
            Class<?> pipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            renderTargets = pipeline.getDeclaredField("renderTargets");
            renderTargets.setAccessible(true);
            getDepthNoTranslucents = Class.forName("net.irisshaders.iris.targets.RenderTargets").getMethod("getDepthTextureNoTranslucents");
            getTextureId = Class.forName("net.irisshaders.iris.targets.DepthTexture").getMethod("getTextureId");
        } catch (ReflectiveOperationException | RuntimeException e) {
            CreateUniversal.LOGGER.warn("No se pudo leer la profundidad de Iris: con shaders los planetas no se verán a través de los cristales", e);
            renderTargets = null;
        }
    }

    /** ¿Hay un paquete de shaders de Iris activo ahora mismo? */
    public static boolean shaderPackActive() {
        init();
        if (api == null) {
            return false;
        }
        try {
            return (Boolean) isShaderPackInUse.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Textura de profundidad de Iris sin translúcidos, o -1 si no se puede obtener. */
    public static int depthWithoutTranslucents() {
        init();
        if (renderTargets == null) {
            return -1;
        }
        try {
            Object pipeline = getPipelineNullable.invoke(getPipelineManager.invoke(null));
            if (pipeline == null || !renderTargets.getDeclaringClass().isInstance(pipeline)) {
                return -1;
            }
            Object targets = renderTargets.get(pipeline);
            Object depth = getDepthNoTranslucents.invoke(targets);
            return depth == null ? -1 : (Integer) getTextureId.invoke(depth);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                CreateUniversal.LOGGER.warn("No se pudo leer la profundidad de Iris", e);
            }
            return -1;
        }
    }
}
