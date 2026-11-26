package io.github.nitsuya.donottryaccessibility.hook;

import android.os.Binder;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Installs all Java hooks inside {@code system_server}.
 *
 * <p>Two layers are used, mirroring the original LSPosed module:</p>
 * <ul>
 *     <li>{@code AccessibilityManagerService}: makes accessibility look
 *     disabled for the blocked caller;</li>
 *     <li>{@code ContentProvider.Transport.call}: rewrites the values returned
 *     by the settings provider ({@code accessibility_enabled},
 *     {@code enabled_accessibility_services}).</li>
 * </ul>
 */
public final class HookInstaller {

    private static final List<MethodHook.Unhook> HOOKS = new ArrayList<>();

    private static final String SETTINGS_AUTHORITY = "settings";
    private static final String GET_SECURE = "GET_secure";
    private static final String VALUE = "value";
    private static final String GENERATION_INDEX = "_generation_index";

    private static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled";
    private static final String KEY_ACCESSIBILITY_SERVICES = "enabled_accessibility_services";

    private HookInstaller() {
    }

    public static void install(ClassLoader loader) {
        if (loader == null) {
            loader = HookInstaller.class.getClassLoader();
        }
        installAccessibilityManagerService(loader);
        installContentProviderTransport(loader);
    }

    private static void installAccessibilityManagerService(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(
                    "com.android.server.accessibility.AccessibilityManagerService", false, loader);
            for (Method method : clazz.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if ("addClient".equals(method.getName()) && params.length == 2
                        && params[1] == int.class) {
                    hookAddClient(method);
                } else if ("getEnabledAccessibilityServiceList".equals(method.getName())
                        && params.length == 2 && params[0] == int.class && params[1] == int.class) {
                    hookEnabledList(method);
                } else if ("getInstalledAccessibilityServiceList".equals(method.getName())
                        && params.length == 1 && params[0] == int.class) {
                    hookInstalledList(method);
                }
            }
        } catch (Throwable t) {
            Logx.e("cannot hook AccessibilityManagerService", t);
        }
    }

    private static void hookAddClient(Method method) {
        try {
            MethodHook.Unhook unhook = Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (!Policy.enterHook()) {
                        return;
                    }
                    try {
                        int uid = Binder.getCallingUid();
                        if (Policy.shouldBlock(uid)) {
                            // AccessibilityManager keeps mIsEnabled == false because
                            // the service never calls back into the client.
                            callFrame.setResult(0L);
                        }
                    } catch (Throwable t) {
                        Logx.w("addClient hook failed", t);
                    } finally {
                        Policy.exitHook();
                    }
                }
            }, false);
            HOOKS.add(unhook);
            Logx.i("hooked " + method);
        } catch (Throwable t) {
            Logx.e("cannot hook " + method, t);
        }
    }

    private static void hookEnabledList(Method method) {
        try {
            MethodHook.Unhook unhook = Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (!Policy.enterHook()) {
                        return;
                    }
                    try {
                        int uid = Binder.getCallingUid();
                        if (Policy.shouldBlock(uid)) {
                            callFrame.setResult(new ArrayList<Object>(0));
                        }
                    } catch (Throwable t) {
                        Logx.w("getEnabledAccessibilityServiceList hook failed", t);
                    } finally {
                        Policy.exitHook();
                    }
                }
            }, false);
            HOOKS.add(unhook);
            Logx.i("hooked " + method);
        } catch (Throwable t) {
            Logx.e("cannot hook " + method, t);
        }
    }

    private static volatile Object sEmptySlice;

    private static Object emptySlice() {
        Object cached = sEmptySlice;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> slice = Class.forName("android.content.pm.ParceledListSlice");
            Object value = slice.getMethod("emptyList").invoke(null);
            sEmptySlice = value;
            return value;
        } catch (Throwable t) {
            Logx.w("cannot create ParceledListSlice", t);
            return null;
        }
    }

    private static void hookInstalledList(Method method) {
        try {
            MethodHook.Unhook unhook = Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (!Policy.enterHook()) {
                        return;
                    }
                    try {
                        int uid = Binder.getCallingUid();
                        if (Policy.shouldBlock(uid)) {
                            Object empty = emptySlice();
                            if (empty != null) {
                                callFrame.setResult(empty);
                            }
                        }
                    } catch (Throwable t) {
                        Logx.w("getInstalledAccessibilityServiceList hook failed", t);
                    } finally {
                        Policy.exitHook();
                    }
                }
            }, false);
            HOOKS.add(unhook);
            Logx.i("hooked " + method);
        } catch (Throwable t) {
            Logx.e("cannot hook " + method, t);
        }
    }

    private static void installContentProviderTransport(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("android.content.ContentProvider$Transport", false, loader);
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"call".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                int count = params.length;
                if (count < 5) {
                    continue;
                }
                if (params[count - 4] != String.class || params[count - 3] != String.class
                        || params[count - 2] != String.class || params[count - 1] != Bundle.class) {
                    continue;
                }
                hookTransportCall(method, count);
            }
        } catch (Throwable t) {
            Logx.e("cannot hook ContentProvider.Transport", t);
        }
    }

    private static void hookTransportCall(Method method, final int count) {
        try {
            MethodHook.Unhook unhook = Pine.hook(method, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if (!Policy.enterHook()) {
                        return;
                    }
                    try {
                        Object result = callFrame.getResult();
                        if (!(result instanceof Bundle)) {
                            return;
                        }
                        Bundle bundle = (Bundle) result;
                        Object[] args = callFrame.args;
                        String authority = (String) args[count - 4];
                        String callMethod = (String) args[count - 3];
                        String key = (String) args[count - 2];
                        if (!SETTINGS_AUTHORITY.equals(authority)) {
                            return;
                        }
                        if (!GET_SECURE.equals(callMethod)) {
                            return;
                        }
                        String fakeValue;
                        if (KEY_ACCESSIBILITY_SERVICES.equals(key)) {
                            fakeValue = "";
                        } else if (KEY_ACCESSIBILITY_ENABLED.equals(key)) {
                            fakeValue = "0";
                        } else {
                            return;
                        }
                        int uid = Binder.getCallingUid();
                        if (!Policy.shouldBlock(uid)) {
                            return;
                        }
                        bundle.putString(VALUE, fakeValue);
                        // Force the client side NameValueCache to drop its cache.
                        bundle.putInt(GENERATION_INDEX, -1);
                        callFrame.setResult(bundle);
                    } catch (Throwable t) {
                        Logx.w("Transport.call hook failed", t);
                    } finally {
                        Policy.exitHook();
                    }
                }
            }, false);
            HOOKS.add(unhook);
            Logx.i("hooked " + method);
        } catch (Throwable t) {
            Logx.e("cannot hook " + method, t);
        }
    }
}

