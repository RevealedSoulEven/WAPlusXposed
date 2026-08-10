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
        val mode = when (lpparam.packageName) {
            "com.whatsapp" -> ScanMode.WHATSAPP
            "com.instagram.android" -> ScanMode.INSTAGRAM
            "com.facebook.katana" -> ScanMode.FACEBOOK
            else -> return
        }

        XposedBridge.log("WAP: ${lpparam.packageName} loaded (mode=$mode)")

        val moduleVersion = BuildConfig.VERSION_CODE
        val appVersion: Long = CacheManager.getAppVersion(lpparam)

        if (appVersion == 0L) {
            XposedBridge.log("WAP: App version cannot be determined. Terminating.")
            return
        }

        try {
            val cache = CacheManager.loadCache(lpparam.packageName)

            if (cache != null &&
                cache.waVersion == appVersion &&
                cache.moduleVersion == moduleVersion
            ) {
                XposedBridge.log("WAP: Using cache")
                try {
                    hookFromCache(cache, lpparam)
                    XposedBridge.log("WAP: Cache hook success")
                    return
                } catch (t: Throwable) {
                    XposedBridge.log("WAP: Cache failed : ${t.message}")
                    CacheManager.deleteCache(lpparam.packageName)
                }
            }

            XposedBridge.log("WAP: Running DexKit scan")
            val newCache = CacheManager.populateBenefitKeys(
                CacheManager.scan(lpparam, appVersion, mode),
                mode
            )
            CacheManager.saveCache(newCache, lpparam.packageName)
            hookFromCache(newCache, lpparam)
            XposedBridge.log("WAP: Fresh scan complete")

        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
        }
    }

    /**
     * Hook everything provided by the Cache data.
     * - WhatsApp: (featureEnum) -> boolean and () -> boolean premium methods,
     *   plus the entitlement provider's master "Plus active" field forced to true.
     * - Instagram/Facebook: zero-arg Boolean verified-badge getters -> true.
     */
    private fun hookFromCache(cache: Cache, lpparam: LoadPackageParam) {
        val loader = lpparam.classLoader
        val enumClazz = if (cache.enumClass.isEmpty()) {
            null
        } else {
            XposedHelpers.findClass(cache.enumClass, loader)
        }

        cache.classes.forEach { clazz ->
            if (enumClazz != null) {
                clazz.enumMethods.forEach { method ->
                    XposedBridge.log("WAP: Hook ${clazz.name}.$method(enum)")
                    XposedHelpers.findAndHookMethod(
                        clazz.name,
                        loader,
                        method,
                        enumClazz,
                        TRUE_HOOK
                    )
                }
            }

            clazz.plainMethods.forEach { method ->
                XposedBridge.log("WAP: Hook ${clazz.name}.$method()")
                XposedHelpers.findAndHookMethod(
                    clazz.name,
                    loader,
                    method,
                    TRUE_HOOK
                )
            }

            clazz.stringMethods.forEach { method ->
                // (String) -> boolean benefit checks, e.g. Facebook's X.9Sb.A03("CUSTOM_APP_ICON").
                XposedBridge.log("WAP: Hook ${clazz.name}.$method(String)")
                XposedHelpers.findAndHookMethod(
                    clazz.name,
                    loader,
                    method,
                    String::class.java,
                    TRUE_HOOK
                )
            }

            clazz.setArgMethods.forEach { method ->
                // (Set) -> void listener callbacks (e.g. FB's X.UCG.D3x(Set), IG's
                // X.7uv.Elp(Set)) that receive the active-benefit set and re-lock features.
                // Inject every benefit key into the Set argument before the original runs.
                XposedBridge.log("WAP: Hook ${clazz.name}.$method(Set)")
                XposedHelpers.findAndHookMethod(
                    clazz.name,
                    loader,
                    method,
                    java.util.Set::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val set = param.args[0] as? java.util.Set<String>
                                if (set != null) {
                                    // The incoming set may be immutable; hand the original a
                                    // fresh mutable set that also contains every benefit key.
                                    val injected = java.util.HashSet(set)
                                    injected.addAll(cache.benefitKeys)
                                    param.args[0] = injected
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("WAP: inject set failed: ${t.message}")
                            }
                        }
                    }
                )
            }

            clazz.objectArgMethods.forEach { method ->
                // Instagram app-icon cell: the method takes the icon object as
                // arg[objectArgIndex] and compares its PRa state field against
                // IG_PLUS_LOCKED (server data) to decide locked vs unlocked UI.
                // Rewrite the field to IG_PLUS_AVAILABLE before the cell renders so
                // every icon behaves as unlocked (badge hidden, tap selects instead
                // of showing the IG Plus upsell).
                val argIndex = clazz.objectArgIndex
                val fieldName = clazz.objectArgField
                if (fieldName != null) {
                    XposedBridge.log("WAP: Hook ${clazz.name}.$method force arg[$argIndex].$fieldName = available")
                    val clazzObj = XposedHelpers.findClass(clazz.name, loader)
                    XposedBridge.hookAllMethods(clazzObj, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (param.args.size <= argIndex) return
                                val arg = param.args[argIndex] ?: return
                                val field = arg.javaClass.getDeclaredField(fieldName)
                                field.isAccessible = true
                                val enumType = field.type
                                // Only rewrite when the field is the icon-state enum; resolve
                                // the constant by its stable name (not obfuscated field names).
                                if (!enumType.isEnum) return
                                val constants = enumType.enumConstants ?: return
                                @Suppress("UNCHECKED_CAST")
                                val available = constants.firstOrNull {
                                    (it as? Enum<*>)?.name == "IG_PLUS_AVAILABLE"
                                } ?: constants.firstOrNull {
                                    (it as? Enum<*>)?.name == "DEFAULT"
                                } ?: return
                                field.set(arg, available)
                            } catch (t: Throwable) {
                                XposedBridge.log("WAP: objectArgHook ${clazz.name}.$method failed: ${t.message}")
                            }
                        }
                    })
                }
            }

            clazz.setField?.let { fieldName ->
                // Facebook: the benefit provider (e.g. X.7sF) holds a java.util.Set (A01)
                // of active benefit keys. The UI reads it DIRECTLY (listener receives the
                // set and checks set.contains("CUSTOM_APP_ICON")), so method hooks are not
                // enough. We populate the set with every Plus benefit key right after each
                // construction, like the WhatsApp master field approach.
                XposedBridge.log("WAP: Hook ${clazz.name}.<init> -> $fieldName += benefits")
                val clazzObj = XposedHelpers.findClass(clazz.name, loader)
                XposedBridge.hookAllConstructors(clazzObj, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val set = XposedHelpers.getObjectField(param.thisObject, fieldName) as? java.util.Set<String>
                            if (set != null) {
                                if (set is java.util.HashSet<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    (set as java.util.HashSet<String>).addAll(cache.benefitKeys)
                                } else {
                                    // The field may hold an unmodifiable view; replace the
                                    // field with a fresh mutable set holding original + keys.
                                    val fresh = java.util.HashSet(set)
                                    fresh.addAll(cache.benefitKeys)
                                    XposedHelpers.setObjectField(param.thisObject, fieldName, fresh)
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("WAP: setField $fieldName failed: ${t.message}")
                        }
                    }
                })
            }

            clazz.masterField?.let { fieldName ->
                // Master "Plus active" toggle on the entitlement provider (e.g. X.0l4.A06).
                // Written only in the constructor; UI reads it directly as a field, so we
                // force it to true right after every construction.
                XposedBridge.log("WAP: Hook ${clazz.name}.<init> -> $fieldName = true")
                val clazzObj = XposedHelpers.findClass(clazz.name, loader)
                XposedBridge.hookAllConstructors(clazzObj, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, fieldName, true)
                        } catch (t: Throwable) {
                            XposedBridge.log("WAP: setBooleanField $fieldName failed: ${t.message}")
                        }
                    }
                })
            }
        }
    }

    private val TRUE_HOOK = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = true
        }
    }
}
