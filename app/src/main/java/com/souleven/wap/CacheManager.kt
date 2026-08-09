package com.souleven.wap

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Modifier

object CacheManager {

    private val CACHE_PATH = "/data/data/com.whatsapp/files/wap.cache"

    /**
     * Scan APK using DexKit to find matching classes and methods.
     *
     * Two layers are scanned:
     *
     * 1. Premium gating class(es), located by the "FREE_TRIAL" marker string.
     *    Inside them we hook every boolean method that unlocks a premium feature:
     *      - methods taking the feature enum as their single parameter (e.g. A0M/A0N/A01)
     *      - no-argument per-feature checks (e.g. A04..A0L) that WhatsApp's UI calls directly
     *
     * 2. The entitlement provider class(es) implementing the LX/0ky interface
     *    (e.g. X.0l4). This is the "Plus subscription" master gate: it holds a volatile
     *    boolean field (A06) that the UI reads DIRECTLY (bypassing method hooks), and when
     *    that field is true every feature unlocks at once. We collect its (featureEnum) ->
     *    boolean methods AND the master field name so the hooker can force it true.
     */
    fun scan(lpparam: LoadPackageParam, waVersion: Long): Cache {
        val apkPath = lpparam.appInfo.sourceDir
        val loader = lpparam.classLoader
        val classes = mutableListOf<CachedClass>()

        DexKitBridge.create(apkPath).use { bridge ->
            val enumMatches = bridge.findClass {
                matcher {
                    modifiers = 0x4000  // ENUM
                    usingStrings("APP_THEMES", "APP_ICONS", "STICKERS")
                }
            }
            XposedBridge.log("WAP: Enum matches = ${enumMatches.size}")

            val enumClass = enumMatches.firstOrNull()
                ?: throw IllegalStateException("Premium feature enum not found")

            val enumName = enumClass.name
            XposedBridge.log("WAP: Enum = $enumName")

            val candidates = bridge.findClass {
                matcher {
                    usingStrings("FREE_TRIAL")
                }
            }
            XposedBridge.log("WAP: Candidates = ${candidates.size}")

            candidates.forEach { classData ->
                val clazz = classData.getInstance(loader)
                val enumMethods = mutableListOf<String>()
                val plainMethods = mutableListOf<String>()

                // Pass 1: (featureEnum) -> boolean methods, e.g. A0M/A0N/A01.
                clazz.declaredMethods.forEach methods@ { method ->
                    if (method.returnType != Boolean::class.javaPrimitiveType) return@methods
                    if (method.parameterTypes.size == 1 && method.parameterTypes[0].name == enumName) {
                        XposedBridge.log("WAP: Found ${clazz.name}.${method.name}(enum)")
                        enumMethods += method.name
                    }
                }

                // Pass 2: () -> boolean per-feature checks the UI calls directly.
                // Only collected on classes proven to be premium gates (enum-param hits),
                // to avoid force-true'ing unrelated booleans on other FREE_TRIAL classes.
                if (enumMethods.isNotEmpty()) {
                    clazz.declaredMethods.forEach methods@ { method ->
                        if (method.returnType != Boolean::class.javaPrimitiveType) return@methods
                        if (method.parameterTypes.isEmpty()) {
                            XposedBridge.log("WAP: Found ${clazz.name}.${method.name}()")
                            plainMethods += method.name
                        }
                    }
                }

                if (enumMethods.isNotEmpty() || plainMethods.isNotEmpty()) {
                    classes += CachedClass(clazz.name, enumMethods, plainMethods)
                }
            }

            // Layer 2: the entitlement provider ("Plus" master gate).
            // Class(es) implementing the LX/0ky interface (e.g. X.0l4).
            // NOTE: DexKit reports names in dotted form ("X.0ky"), not smali form ("LX/0ky;").
            val providers = bridge.findClass {
                matcher {
                    addInterface("X.0ky")
                }
            }
            XposedBridge.log("WAP: Providers = ${providers.size}")

            providers.forEach { classData ->
                val clazz = classData.getInstance(loader)
                val enumMethods = mutableListOf<String>()

                clazz.declaredMethods.forEach methods@ { method ->
                    if (method.returnType != Boolean::class.javaPrimitiveType) return@methods
                    if (method.parameterTypes.size == 1 && method.parameterTypes[0].name == enumName) {
                        XposedBridge.log("WAP: Found ${clazz.name}.${method.name}(enum) [provider]")
                        enumMethods += method.name
                    }
                }

                // The volatile boolean field is the master "Plus active" toggle (A06).
                // It is written only in the constructor and read directly by the UI.
                val masterField = clazz.declaredFields.firstOrNull {
                    it.type == Boolean::class.javaPrimitiveType &&
                        Modifier.isVolatile(it.modifiers)
                }?.name

                if (enumMethods.isNotEmpty() || masterField != null) {
                    XposedBridge.log("WAP: Provider ${clazz.name} masterField=$masterField")
                    classes += CachedClass(clazz.name, enumMethods, emptyList(), masterField)
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
                val enumMethods = mutableListOf<String>()
                val methodArray = obj.optJSONArray("methods") ?: JSONArray()
                for (j in 0 until methodArray.length()) {
                    enumMethods += methodArray.getString(j)
                }

                val plainMethods = mutableListOf<String>()
                val plainArray = obj.optJSONArray("plainMethods") ?: JSONArray()
                for (j in 0 until plainArray.length()) {
                    plainMethods += plainArray.getString(j)
                }

                val masterField = obj.optString("masterField", "").takeIf { it.isNotEmpty() }

                classes += CachedClass(obj.getString("name"), enumMethods, plainMethods, masterField)
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

                val enumArr = JSONArray()
                clazz.enumMethods.forEach { enumArr.put(it) }
                obj.put("methods", enumArr)

                val plainArr = JSONArray()
                clazz.plainMethods.forEach { plainArr.put(it) }
                obj.put("plainMethods", plainArr)

                obj.put("masterField", clazz.masterField ?: "")

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

    /**
     * Version detection without the fragile PackageParser hidden-API path.
     * ApplicationInfo carries the version code directly and works on every device;
     * the field is read via reflection so it survives any SDK level.
     */
    fun getWhatsAppVersion(lpparam: LoadPackageParam): Long {
        return try {
            val info = lpparam.appInfo
            try {
                XposedHelpers.getLongField(info, "longVersionCode")
            } catch (_: Throwable) {
                XposedHelpers.getIntField(info, "versionCode").toLong()
            }
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + "Failed to read version code: ${t.message}")
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
    val enumMethods: List<String>,
    val plainMethods: List<String>,
    val masterField: String? = null
)
