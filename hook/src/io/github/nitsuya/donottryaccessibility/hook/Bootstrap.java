package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;

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
     * Called from {@code nativeInit()} in {@code libdnta.so}.  Pine is
     * initialized immediately, but the hooks are installed only after
     * system_server is ready: at {@code postServerSpecialize} time the settings
     * provider and services.jar classes do not exist yet.
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

            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context context = Ctx.awaitSystemReady();
                        if (context == null) {
                            Logx.e("bootstrap: system_server never became ready", null);
                            return;
                        }
                        Logx.i("bootstrap: system_server ready, installing hooks");
                        HookInstaller.install(context.getClassLoader());
                        Config.startObserver();
                        AutoFill.registerObservers();
                        Status.write();
                        Status.writeAfterBootCompleted();
                        Logx.i("bootstrap: done");
                    } catch (Throwable t) {
                        Logx.e("bootstrap: deferred init failed", t);
                    }
                }
            }, "dnta-bootstrap");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            Logx.e("bootstrap failed", t);
        }
    }
}
