package org.lsposed.lspd.util;

import static org.lsposed.lspd.core.ApplicationServiceClient.serviceClient;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.webkit.WebViewDelegate;
import android.webkit.WebViewFactory;
import android.webkit.WebViewFactoryProvider;

import org.lsposed.lspd.ILSPManagerService;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cn.lony.android.rovox.RX_MethodHook;
import cn.lony.android.rovox.RX_MethodReplacement;
import cn.lony.android.rovox.RovoxBridge;
import cn.lony.android.rovox.RovoxHelpers;
import hidden.HiddenApiBridge;

public class ParasiticManagerHooker {
    private static final String CHROMIUM_WEBVIEW_FACTORY_METHOD = "create";

    private static PackageInfo managerPkgInfo = null;
    private static int managerFd = -1;
    private final static Map<String, Bundle> states = new ConcurrentHashMap<>();
    private final static Map<String, PersistableBundle> persistentStates = new ConcurrentHashMap<>();

    private synchronized static PackageInfo getManagerPkgInfo(ApplicationInfo appInfo) {
        if (managerPkgInfo == null && appInfo != null) {
            try {
                Context ctx = ActivityThread.currentActivityThread().getSystemContext();
                var sourceDir = "/proc/self/fd/" + managerFd;
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    var dstDir = appInfo.dataDir + "/cache/lsposed.apk";
                    try (var inStream = new FileInputStream(sourceDir); var outStream = new FileOutputStream(dstDir)) {
                        FileChannel inChannel = inStream.getChannel();
                        FileChannel outChannel = outStream.getChannel();
                        inChannel.transferTo(0, inChannel.size(), outChannel);
                        sourceDir = dstDir;
                    } catch (Throwable e) {
                        Hookers.logE("copy apk", e);
                    }
                }
                managerPkgInfo = ctx.getPackageManager().getPackageArchiveInfo(sourceDir, PackageManager.GET_ACTIVITIES);
                var newAppInfo = managerPkgInfo.applicationInfo;
                newAppInfo.sourceDir = sourceDir;
                newAppInfo.publicSourceDir = sourceDir;
                newAppInfo.nativeLibraryDir = appInfo.nativeLibraryDir;
                newAppInfo.packageName = appInfo.packageName;
                newAppInfo.dataDir = HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(appInfo);
                newAppInfo.deviceProtectedDataDir = appInfo.deviceProtectedDataDir;
                newAppInfo.processName = appInfo.processName;
                HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(newAppInfo, HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(appInfo));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HiddenApiBridge.ApplicationInfo_overlayPaths(newAppInfo, HiddenApiBridge.ApplicationInfo_overlayPaths(appInfo));
                }
                HiddenApiBridge.ApplicationInfo_resourceDirs(newAppInfo, HiddenApiBridge.ApplicationInfo_resourceDirs(appInfo));
                newAppInfo.uid = appInfo.uid;
                // FIXME: It seems the parsed flags is incorrect (0) on A14 QPR3
                newAppInfo.flags = newAppInfo.flags | ApplicationInfo.FLAG_HAS_CODE;
            } catch (Throwable e) {
                Utils.logE("get manager pkginfo", e);
            }
        }
        return managerPkgInfo;
    }

    private static void sendBinderToManager(final ClassLoader classLoader, IBinder binder) {
        try {
            var clazz = RovoxHelpers.findClass("org.lsposed.manager.Constants", classLoader);
            var ok = (boolean) RovoxHelpers.callStaticMethod(clazz, "setBinder",
                    new Class[]{IBinder.class}, binder);
            if (ok) return;
            throw new RuntimeException("setBinder: " + false);
        } catch (Throwable t) {
            Utils.logW("Could not send binder to LSPosed Manager", t);
        }
    }

    private static void hookForManager(ILSPManagerService managerService) {
        var managerApkHooker = new RX_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Hookers.logD("ActivityThread#handleBindApplication() starts");
                Object bindData = param.args[0];
                ApplicationInfo appInfo = (ApplicationInfo) RovoxHelpers.getObjectField(bindData, "appInfo");
                RovoxHelpers.setObjectField(bindData, "appInfo", getManagerPkgInfo(appInfo).applicationInfo);
            }
        };
        RovoxHelpers.findAndHookMethod(ActivityThread.class,
                "handleBindApplication",
                "android.app.ActivityThread$AppBindData",
                managerApkHooker);

        var unhooks = new RX_MethodHook.Unhook[]{null};
        unhooks[0] = RovoxHelpers.findAndHookMethod(
                LoadedApk.class, "getClassLoader", new RX_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        var pkgInfo = getManagerPkgInfo(null);
                        if (pkgInfo != null && RovoxHelpers.getObjectField(param.thisObject, "mApplicationInfo") == pkgInfo.applicationInfo) {
                            var sSourceDir = pkgInfo.applicationInfo.sourceDir;
                            var pathClassLoader = param.getResult();

                            Hookers.logD("LoadedApk getClassLoader " + pathClassLoader);
                            var pathList = RovoxHelpers.getObjectField(pathClassLoader, "pathList");
                            List<String> lstDexPath = (List<String>) RovoxHelpers.callMethod(pathList, "getDexPaths");
                            if (!lstDexPath.contains(sSourceDir)) {
                                Utils.logW("Could not find manager apk injected in classloader");
                                RovoxHelpers.callMethod(pathClassLoader, "addDexPath", sSourceDir);
                            }
                            sendBinderToManager((ClassLoader) pathClassLoader, managerService.asBinder());
                            unhooks[0].unhook();
                        }
                    }
                });

        var activityClientRecordClass = RovoxHelpers.findClass("android.app.ActivityThread$ActivityClientRecord", ActivityThread.class.getClassLoader());
        var activityHooker = new RX_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                for (var i = 0; i < param.args.length; ++i) {
                    if (param.args[i] instanceof ActivityInfo) {
                        var aInfo = (ActivityInfo) param.args[i];
                        var pkgInfo = getManagerPkgInfo(aInfo.applicationInfo);
                        if (pkgInfo == null) return;
                        for (var activity : pkgInfo.activities) {
                            if ("org.lsposed.manager.ui.activity.MainActivity".equals(activity.name)) {
                                activity.applicationInfo = pkgInfo.applicationInfo;
                                param.args[i] = activity;
                            }
                        }
                    }
                    if (param.args[i] instanceof Intent) {
                        var intent = (Intent) param.args[i];
                        intent.setComponent(new ComponentName(intent.getComponent().getPackageName(), "org.lsposed.manager.ui.activity.MainActivity"));
                    }
                }
                if (param.method.getName().equals("scheduleLaunchActivity")) {
                    ActivityInfo aInfo = null;
                    var parameters = ((Method) param.method).getParameterTypes();
                    for (var i = 0; i < parameters.length; ++i) {
                        if (parameters[i] == ActivityInfo.class) {
                            aInfo = (ActivityInfo) param.args[i];
                            Hookers.logD("loading state of " + aInfo.name);
                        } else if (parameters[i] == Bundle.class && aInfo != null) {
                            final int idx = i;
                            states.computeIfPresent(aInfo.name, (k, v) -> {
                                param.args[idx] = v;
                                return v;
                            });
                        } else if (parameters[i] == PersistableBundle.class && aInfo != null) {
                            final int idx = i;
                            persistentStates.computeIfPresent(aInfo.name, (k, v) -> {
                                param.args[idx] = v;
                                return v;
                            });
                        }
                    }

                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                for (var i = 0; i < param.args.length && activityClientRecordClass.isInstance(param.thisObject); ++i) {
                    if (param.args[i] instanceof ActivityInfo) {
                        var aInfo = (ActivityInfo) param.args[i];
                        Hookers.logD("loading state of " + aInfo.name);
                        states.computeIfPresent(aInfo.name, (k, v) -> {
                            RovoxHelpers.setObjectField(param.thisObject, "state", v);
                            return v;
                        });
                        persistentStates.computeIfPresent(aInfo.name, (k, v) -> {
                            RovoxHelpers.setObjectField(param.thisObject, "persistentState", v);
                            return v;
                        });
                    }
                }
            }
        };
        RovoxBridge.hookAllConstructors(activityClientRecordClass, activityHooker);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            RovoxBridge.hookAllMethods(RovoxHelpers.findClass("android.app.ActivityThread$ApplicationThread", ActivityThread.class.getClassLoader()), "scheduleLaunchActivity", activityHooker);
        }

        RovoxBridge.hookAllMethods(ActivityThread.class, "handleReceiver", new RX_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                for (var arg : param.args) {
                    if (arg instanceof BroadcastReceiver.PendingResult) {
                        ((BroadcastReceiver.PendingResult) arg).finish();
                    }
                }
                return null;
            }
        });

        RovoxBridge.hookAllMethods(ActivityThread.class, "installProvider", new RX_MethodHook() {
            private Context originalContext = null;

            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Hookers.logD("before install provider");
                Context ctx = null;
                ProviderInfo info = null;
                int ctxIdx = -1;
                for (var i = 0; i < param.args.length; ++i) {
                    var arg = param.args[i];
                    if (arg instanceof Context) {
                        ctx = (Context) arg;
                        ctxIdx = i;
                    } else if (arg instanceof ProviderInfo) info = (ProviderInfo) arg;
                }
                var pkgInfo = getManagerPkgInfo(null);
                if (ctx != null && info != null && pkgInfo != null) {
                    var packageName = pkgInfo.applicationInfo.packageName;
                    if (!info.applicationInfo.packageName.equals(packageName)) return;
                    if (originalContext == null) {
                        info.applicationInfo.packageName = packageName + ".origin";
                        var originalPkgInfo = ActivityThread.currentActivityThread().getPackageInfoNoCheck(info.applicationInfo, HiddenApiBridge.Resources_getCompatibilityInfo(ctx.getResources()));
                        RovoxHelpers.setObjectField(originalPkgInfo, "mPackageName", packageName);
                        originalContext = (Context) RovoxHelpers.callStaticMethod(RovoxHelpers.findClass("android.app.ContextImpl", null),
                                "createAppContext", ActivityThread.currentActivityThread(), originalPkgInfo);
                        info.applicationInfo.packageName = packageName;
                    }
                    param.args[ctxIdx] = originalContext;
                } else {
                    Hookers.logE("Failed to reload provider", new RuntimeException());
                }
            }
        });

        RovoxHelpers.findAndHookMethod(WebViewFactory.class, "getProvider", new RX_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                var sProviderInstance = RovoxHelpers.getStaticObjectField(WebViewFactory.class, "sProviderInstance");
                if (sProviderInstance != null) return sProviderInstance;
                //noinspection unchecked
                var providerClass = (Class<WebViewFactoryProvider>) RovoxHelpers.callStaticMethod(WebViewFactory.class, "getProviderClass");
                Method staticFactory = null;
                try {
                    staticFactory = providerClass.getMethod(
                            CHROMIUM_WEBVIEW_FACTORY_METHOD, WebViewDelegate.class);
                } catch (Exception e) {
                    Hookers.logE("error instantiating provider with static factory method", e);
                }

                try {
                    var webViewDelegateConstructor = WebViewDelegate.class.getDeclaredConstructor();
                    webViewDelegateConstructor.setAccessible(true);
                    if (staticFactory != null) {
                        sProviderInstance = staticFactory.invoke(null, webViewDelegateConstructor.newInstance());
                    }
                    RovoxHelpers.setStaticObjectField(WebViewFactory.class, "sProviderInstance", sProviderInstance);
                    Hookers.logD("Loaded provider: " + sProviderInstance);
                    return sProviderInstance;
                } catch (Exception e) {
                    Hookers.logE("error instantiating provider", e);
                    throw new AndroidRuntimeException(e);
                }
            }
        });
        var stateHooker = new RX_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    var record = param.args[0];
                    if (record instanceof IBinder) {
                        record = ((ArrayMap<?, ?>) RovoxHelpers.getObjectField(param.thisObject, "mActivities")).get(record);
                        if (record == null) return;
                    }
                    RovoxHelpers.callMethod(param.thisObject, Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? "callActivityOnSaveInstanceState" : "callCallActivityOnSaveInstanceState", record);
                    var state = (Bundle) RovoxHelpers.getObjectField(record, "state");
                    var persistentState = (PersistableBundle) RovoxHelpers.getObjectField(record, "persistentState");
                    var aInfo = (ActivityInfo) RovoxHelpers.getObjectField(record, "activityInfo");
                    states.compute(aInfo.name, (k, v) -> state);
                    persistentStates.compute(aInfo.name, (k, v) -> persistentState);
                    Hookers.logD("saving state of " + aInfo.name);
                } catch (Throwable e) {
                    Hookers.logE("save state", e);
                }
            }
        };
        RovoxBridge.hookAllMethods(ActivityThread.class, "performStopActivityInner", stateHooker);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1)
            RovoxHelpers.findAndHookMethod(ActivityThread.class, "performDestroyActivity", IBinder.class, boolean.class, int.class, boolean.class, stateHooker);
    }


    static public boolean start() {
        List<IBinder> binder = new ArrayList<>(1);
        try (var managerParcelFd = serviceClient.requestInjectedManagerBinder(binder)) {
            if (binder.size() > 0 && binder.get(0) != null && managerParcelFd != null) {
                managerFd = managerParcelFd.detachFd();
                var managerService = ILSPManagerService.Stub.asInterface(binder.get(0));
                hookForManager(managerService);
                Utils.logD("injected manager");
                return true;
            } else {
                // Not manager
                return false;
            }
        } catch (Throwable e) {
            Utils.logE("failed to inject manager", e);
            return false;
        }
    }
}
