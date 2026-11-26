package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Writes the "module is alive" marker that the manager app uses to detect an
 * active Zygisk hook.  The marker includes the current {@code boot_count}; the
 * app only considers the module active when the boot count matches, so a stale
 * value from a previous boot is ignored.
 */
public final class Status {

    private Status() {
    }

    public static void write() {
        Context context = Ctx.get();
        if (context == null) {
            return;
        }
        try {
            int bootCount = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            JSONObject json = new JSONObject();
            json.put("version", HookConfig.VERSION);
            json.put("boot", bootCount);
            json.put("time", System.currentTimeMillis());
            Settings.Global.putString(context.getContentResolver(), HookConfig.KEY_STATUS, json.toString());
            Logx.i("status written: " + json);
        } catch (Throwable t) {
            Logx.w("cannot write status", t);
        }
    }
}
