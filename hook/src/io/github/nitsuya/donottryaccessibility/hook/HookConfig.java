package io.github.nitsuya.donottryaccessibility.hook;

/**
 * Constants that are shared with the manager app.  Keep them in sync with
 * {@code io.github.nitsuya.donottryaccessibility.ConfigStore}.
 */
public final class HookConfig {

    /** Version reported by the injected hook. */
    public static final String VERSION = "2.0.0";

    /** Settings.Global key that stores the JSON configuration. */
    public static final String KEY_CONFIG = "dnta_config";

    /** Settings.Global key that stores the "module is alive" heartbeat. */
    public static final String KEY_STATUS = "dnta_status";

    /** Package name of the manager app. */
    public static final String MANAGER_PACKAGE = "io.github.nitsuya.donottryaccessibility";

    private HookConfig() {
    }
}
