package io.github.nitsuya.donottryaccessibility.hook;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;

/**
 * Entry point of the injected code.  This class is loaded from the in-memory
 * dex by the native Zygisk module and invoked through JNI.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /**
     * Called from {@code nativeInit()} in {@code libdnta.so}.
     * Must never throw, otherwise system_server would abort.
     */
    public static void start() {
        try {
            Logx.i("bootstrap: initializing Pine");
            PineConfig.debug = false;
            PineConfig.debuggable = false;
            PineConfig.antiChecks = false;
            PineConfig.disableHooks = false;
            // libpine.a is linked directly into the Zygisk library.
            PineConfig.libLoader = new Pine.LibLoader() {
                @Override
                public void loadLib() {
                    // no-op
                }
            };
            Pine.ensureInitialized();
            Logx.i("bootstrap: Pine initialized (hook mode " + Pine.getHookMode() + ")");
            HookInstaller.install();
            Config.startObserver();
            Ctx.startPolling();
            Logx.i("bootstrap: done");
        } catch (Throwable t) {
            Logx.e("bootstrap failed", t);
        }
    }
}
