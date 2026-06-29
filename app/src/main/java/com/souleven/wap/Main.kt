package com.souleven.wap

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class Main : IXposedHookLoadPackage {

    companion object {
        init {
            System.loadLibrary("dexkit")
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "com.whatsapp") return

        XposedBridge.log("WAP: WhatsApp loaded")

        val moduleVersion = BuildConfig.VERSION_CODE
        val waVersion: Long = CacheManager.getWhatsAppVersion(lpparam)

        if (waVersion == 0L) {
            XposedBridge.log("WAP: Whatsapp Version cannot be determined. Terminating.")
            return
        }

        try {
            val cache = CacheManager.loadCache()

            if (cache != null &&
                cache.waVersion == waVersion &&
                cache.moduleVersion == moduleVersion
            ) {
                XposedBridge.log("WAP: Using cache")
                try {
                    hookFromCache(cache, lpparam)
                    XposedBridge.log("WAP: Cache hook success")
                    return
                } catch (t: Throwable) {
                    XposedBridge.log("WAP: Cache failed : ${t.message}")
                    CacheManager.deleteCache()
                }
            }

            XposedBridge.log("WAP: Running DexKit scan")
            val newCache = CacheManager.scan(lpparam, waVersion)
            CacheManager.saveCache(newCache)
            hookFromCache(newCache, lpparam)
            XposedBridge.log("WAP: Fresh scan complete")

        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
        }
    }

    /**
     * Hook everything provided by the Cache data.
     */
    private fun hookFromCache(cache: Cache, lpparam: LoadPackageParam) {
        val loader = lpparam.classLoader
        val enumClazz = XposedHelpers.findClass(cache.enumClass, loader)

        cache.classes.forEach { clazz ->
            clazz.methods.forEach { method ->
                XposedBridge.log("WAP: Hook ${clazz.name}.$method")

                XposedHelpers.findAndHookMethod(
                    clazz.name,
                    loader,
                    method,
                    enumClazz,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            }
        }
    }
}