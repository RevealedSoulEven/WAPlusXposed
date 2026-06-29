package com.souleven.wap

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File

object CacheManager {

    private val CACHE_PATH = "/data/data/com.whatsapp/files/wap.cache"

    /**
     * Scan APK using DexKit to find matching classes and methods.
     */
    fun scan(lpparam: LoadPackageParam, waVersion: Long): Cache {
        val apkPath = lpparam.appInfo.sourceDir
        val loader = lpparam.classLoader
        val classes = mutableListOf<CachedClass>()

        DexKitBridge.create(apkPath).use { bridge ->
            val enumClass = bridge.findClass {
                matcher {
                    modifiers = 0x4000  // ENUM
                    usingStrings("APP_THEMES", "APP_ICONS", "STICKERS")
                }
            }.single()

            val enumName = enumClass.name
            XposedBridge.log("WAP: " + "Enum = $enumName")

            val candidates = bridge.findClass {
                matcher {
                    usingStrings("FREE_TRIAL")
                }
            }
            XposedBridge.log("WAP: " + "Candidates = ${candidates.size}")

            candidates.forEach { classData ->
                val clazz = classData.getInstance(loader)
                val methods = mutableListOf<String>()

                clazz.declaredMethods.forEach { method ->
                    if (method.returnType != Boolean::class.javaPrimitiveType) return@forEach
                    if (method.parameterTypes.size != 1) return@forEach
                    if (method.parameterTypes[0].name != enumName) return@forEach

                    XposedBridge.log("WAP: " + "Found ${clazz.name}.${method.name}")
                    methods += method.name
                }

                if (methods.isNotEmpty()) {
                    classes += CachedClass(clazz.name, methods)
                }
            }

            return Cache(
                waVersion = waVersion,
                moduleVersion = BuildConfig.VERSION_CODE,
                enumClass = enumName,
                classes = classes
            )
        }
    }

    fun loadCache(): Cache? {
        try {
            val file = File(CACHE_PATH)
            if (!file.exists()) {
                XposedBridge.log("WAP: " + "Cache doesn't exist")
                return null
            }

            val json = JSONObject(file.readText())
            val classes = mutableListOf<CachedClass>()
            val arr = json.getJSONArray("classes")

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val methods = mutableListOf<String>()
                val methodArray = obj.getJSONArray("methods")

                for (j in 0 until methodArray.length()) {
                    methods += methodArray.getString(j)
                }
                classes += CachedClass(obj.getString("name"), methods)
            }

            return Cache(
                waVersion = json.getLong("waVersion"),
                moduleVersion = json.getInt("moduleVersion"),
                enumClass = json.getString("enumClass"),
                classes = classes
            )
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
            return null
        }
    }

    fun saveCache(cache: Cache) {
        try {
            val root = JSONObject()
            root.put("waVersion", cache.waVersion)
            root.put("moduleVersion", cache.moduleVersion)
            root.put("enumClass", cache.enumClass)

            val classArray = JSONArray()
            cache.classes.forEach { clazz ->
                val obj = JSONObject()
                obj.put("name", clazz.name)
                val methods = JSONArray()
                clazz.methods.forEach { methods.put(it) }
                obj.put("methods", methods)
                classArray.put(obj)
            }
            root.put("classes", classArray)

            File(CACHE_PATH).writeText(root.toString())
            XposedBridge.log("WAP: " + "Cache saved")
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
        }
    }

    fun deleteCache() {
        try {
            File(CACHE_PATH).delete()
            XposedBridge.log("WAP: " + "Cache deleted")
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
        }
    }

    fun getWhatsAppVersion(lpparam: LoadPackageParam): Long {
        return try {
            val parserCls = XposedHelpers.findClass("android.content.pm.PackageParser", lpparam.classLoader)
            val parser = parserCls.getDeclaredConstructor().newInstance()
            val apkFile = File(lpparam.appInfo.sourceDir)
            val pkg = XposedHelpers.callMethod(parser, "parsePackage", apkFile, 0)

            try {
                XposedHelpers.getLongField(pkg, "mLongVersionCode")
            } catch (_: Throwable) {
                XposedHelpers.getIntField(pkg, "mVersionCode").toLong()
            }
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + "Failed to parse version code: ${t.message}")
            0L
        }
    }
}

// Data models for the Cache structure
data class Cache(
    val waVersion: Long,
    val moduleVersion: Int,
    val enumClass: String,
    val classes: List<CachedClass>
)

data class CachedClass(
    val name: String,
    val methods: List<String>
)