package com.mio.libpatcher;

import com.mio.libpatcher.transformer.*;
import com.mio.libpatcher.transformer.oshi.CentralProcessor;
import com.mio.libpatcher.transformer.oshi.ProcessorIdentifierTransformer;
import com.mio.libpatcher.util.LogUtil;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;

public class MainAgent {

    private static final List<String> classList = new ArrayList<>();

    public static void premain(String agentArgs, Instrumentation inst) {
        LogUtil.info("MioPatcher is running!");
        preloadPojavExec();
        addTransformer(inst, false);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        preloadPojavExec();
        addTransformer(inst, true);
    }

    /**
     * Load the launcher's native bridge into the game JVM as early as possible.
     * Mojang's NativeLibrariesBootstrap (e.g. VK.getVulkanDriverHandle on the
     * 26.x Vulkan path) runs before any LWJGL class init, so without this the
     * game-side natives resolve to UnsatisfiedLinkError. Failures are non-fatal:
     * later LWJGL class init (GLFW/CallbackBridge) loads the library anyway.
     */
    private static void preloadPojavExec() {
        try {
            System.loadLibrary("pojavexec");
            LogUtil.info("Preloaded pojavexec into the game JVM");
        } catch (UnsatisfiedLinkError e) {
            LogUtil.error("Failed to preload pojavexec: " + e.getMessage());
        }
    }

    private static void addTransformer(Instrumentation inst, boolean isAgentmain) {
        List<BaseTransformer> transformers = new ArrayList<>();
        transformers.add(new TTSTransformer());
        transformers.add(new LibraryTransformer());
        transformers.add(new SystemInfoTransformer());
        transformers.add(new RandomPatchesTransformer());
        transformers.add(new ProcessorIdentifierTransformer());
        transformers.add(new CentralProcessor());
        transformers.add(new SodiumLikeModTransformer());
        transformers.add(new SQLTransformer());
        transformers.add(new FabricLoaderTransformer());
        transformers.add(new ForgeModDirTransformer());
        transformers.add(new CreateTransformer());
        transformers.add(new SableRapierLibTransformer());
        transformers.add(new VeilImGuiTransformer());
        transformers.add(new ImGuiMoulberryTransformer());
        transformers.add(new InstrumentBootstrapTransformer());
        transformers.forEach(baseTransformer -> {
            inst.addTransformer(baseTransformer, true);
            if (isAgentmain) {
                String className = baseTransformer.getTargetClassName();
                if (!className.isEmpty()) {
                    classList.add(className);
                } else {
                    classList.addAll(baseTransformer.getTargetClassNames());
                }
            }
        });
        if (isAgentmain) {
            Class<?>[] classes = inst.getAllLoadedClasses();
            for (Class<?> aClass : classes) {
                if (classList.contains(aClass.getName())) {
                    LogUtil.info("Transform class:" + aClass.getName());
                    try {
                        inst.retransformClasses(aClass);
                    } catch (UnmodifiableClassException e) {
                        LogUtil.error(e.toString());
                    }
                }
            }
        }
    }

}
