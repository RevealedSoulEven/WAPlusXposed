package com.souleven.wap

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Modifier

/** Which premium system we are patching inside the loaded app. */
enum class ScanMode { WHATSAPP, INSTAGRAM, FACEBOOK }

object CacheManager {

    fun cachePath(pkg: String): String = "/data/data/$pkg/files/wap.cache"

    /**
     * Dispatch to the right scan strategy for the loaded app.
     */
    fun scan(lpparam: LoadPackageParam, appVersion: Long, mode: ScanMode): Cache {
        return when (mode) {
            ScanMode.WHATSAPP -> scanWhatsApp(lpparam, appVersion)
            // Instagram uses the same Meta "benefits" entitlement system as Facebook:
            // the provider (X.C7ij) gates features via (String) -> boolean checks and a
            // java.util.Set of active benefit keys; UI listeners (implementing X.Oxh)
            // receive the set and can re-lock features. Plus the IG Plus subscription
            // gate (X.Kj4.A01, found via the "user_offer_type" marker) whose boolean
            // is the master "Plus active" toggle for premium perks like app icons.
            ScanMode.INSTAGRAM -> scanInstagram(lpparam, appVersion)
            ScanMode.FACEBOOK -> scanBenefits(
                lpparam, appVersion,
                providerMarker = "CUSTOM_APP_ICON",
                listenerInterface = null
            )
        }
    }

    /**
     * Instagram scan: benefit system + the IG Plus subscription master gate +
     * the app-icon per-icon lock state.
     *
     * 1. Benefit system: X.7ij gates features via (String) -> boolean checks.
     * 2. IG Plus subscription: X.Kj4.A01() ("is user_offer_type one of the valid
     *    trial offer types") is the master "Plus active" toggle; forcing it true
     *    unlocks every premium perk at once, same idea as WhatsApp's X.0l4.A06.
     * 3. App icon lock: the picker cell X.VKx.A01 compares each icon's state field
     *    (X.I0S.A01, type X.PRa) against PRa.A06 = IG_PLUS_LOCKED. The state comes
     *    from server data, so the method hook (Kj4) does not change it. We hook the
     *    cell method and rewrite the icon's state field to IG_PLUS_AVAILABLE so
     *    every icon renders and taps as unlocked.
     */
    private fun scanInstagram(lpparam: LoadPackageParam, appVersion: Long): Cache {
        val benefitCache = scanBenefits(
            lpparam, appVersion,
            providerMarker = "subs_active_benefits",
            listenerInterface = "X.Oxh"
        )

        val apkPath = lpparam.appInfo.sourceDir
        val loader = lpparam.classLoader
        val classes = benefitCache.classes.toMutableList()
        val seen = benefitCache.classes.mapTo(mutableSetOf()) { it.name }

        DexKitBridge.create(apkPath).use { bridge ->
            val gates = bridge.findClass {
                matcher {
                    usingStrings("user_offer_type")
                }
            }
            XposedBridge.log("WAP: IG Plus gate classes = ${gates.size}")

            gates.forEach { classData ->
                val clazz = classData.getInstance(loader)
                if (!seen.add(clazz.name)) return@forEach

                val plainMethods = mutableListOf<String>()
                clazz.declaredMethods.forEach methods@ { method ->
                    if (method.returnType != Boolean::class.javaPrimitiveType) return@methods
                    if (method.parameterTypes.isEmpty()) {
                        XposedBridge.log("WAP: IG Plus gate ${clazz.name}.${method.name}()")
                        plainMethods += method.name
                    }
                }

                if (plainMethods.isNotEmpty()) {
                    classes += CachedClass(clazz.name, emptyList(), plainMethods)
                }
            }

            // App icon cell: force every icon's state to "available" so premium icons
            // are not gated by IG Plus. The cell method (X.VKx.A01) takes the icon
            // (X.I0S) as its second argument; we rewrite its PRa state field.
            val cells = bridge.findClass {
                matcher {
                    usingStrings("com.instagram.aura.appicon.ui.AuraAppIconCell (AuraAppIconPickerContent.kt:194)")
                }
            }
            XposedBridge.log("WAP: IG icon cell classes = ${cells.size}")

            cells.forEach { classData ->
                val clazz = classData.getInstance(loader)
                if (!seen.add(clazz.name)) return@forEach

                val objectArgMethods = mutableListOf<String>()
                var objectArgField: String? = null
                clazz.declaredMethods.forEach methods@ { method ->
                    if (method.parameterTypes.size < 2) return@methods
                    // The icon type (X.I0S) declares a state field of the enum X.PRa.
                    val stateField = method.parameterTypes[1].declaredFields.firstOrNull {
                        it.type.isEnum && it.type.name == "X.PRa"
                    } ?: return@methods
                    objectArgField = stateField.name
                    XposedBridge.log("WAP: IG icon cell ${clazz.name}.${method.name} force ${method.parameterTypes[1].name}.${stateField.name}")
                    objectArgMethods += method.name
                }

                if (objectArgMethods.isNotEmpty() && objectArgField != null) {
                    classes += CachedClass(
                        clazz.name, emptyList(), emptyList(),
                        null, emptyList(), null, emptyList(),
                        objectArgMethods, 1, objectArgField
                    )
                }
            }
        }

        return benefitCache.copy(classes = classes)
    }

    /**
     * WhatsApp scan.
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
    private fun scanWhatsApp(lpparam: LoadPackageParam, waVersion: Long): Cache {
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

    /**
     * Instagram / Facebook "benefits" scan.
     *
     * Both apps share Meta's benefit-entitlement architecture:
     *
     *  - The benefit provider class (e.g. Facebook's X.7sF / X.9Sb, Instagram's X.C7ij)
     *    gates every premium benefit via (String) -> boolean methods (e.g. A08/A03/A0C)
     *    and holds a java.util.Set field (A01) of active benefit keys. The UI reads the
     *    set DIRECTLY in places (listeners receive it and check contains(benefit)), so
     *    method hooks alone are not enough — we also record the Set field and populate it
     *    with every benefit key after construction.
     *
     *  - (Set) -> void listener callbacks (e.g. Facebook's X.UCG.D3x(Set), Instagram's
     *    X.7uv.Elp(Set) via the X.Oxh interface) receive the active-benefit set and can
     *    re-lock features. We hook them and inject every benefit key into the Set
     *    argument before the original runs.
     *
     * The provider is located by a stable marker string inside its check methods; the
     * listeners are located either by the same marker (Facebook) or by implementing a
     * known interface (Instagram's X.Oxh).
     */
    private fun scanBenefits(
        lpparam: LoadPackageParam,
        appVersion: Long,
        providerMarker: String,
        listenerInterface: String?
    ): Cache {
        val apkPath = lpparam.appInfo.sourceDir
        val loader = lpparam.classLoader
        val classes = mutableListOf<CachedClass>()
        val seen = mutableSetOf<String>()

        DexKitBridge.create(apkPath).use { bridge ->
            val candidates = bridge.findClass {
                matcher {
                    usingStrings(providerMarker)
                }
            }
            XposedBridge.log("WAP: Benefit provider classes = ${candidates.size}")

            candidates.forEach { classData ->
                val clazz = classData.getInstance(loader)
                if (!seen.add(clazz.name)) return@forEach
                collectBenefitClass(clazz, classes)
            }

            // Listeners that do NOT reference the provider marker (e.g. Instagram's
            // anonymous Oxh implementations) are found by their interface instead.
            if (listenerInterface != null) {
                val listeners = bridge.findClass {
                    matcher {
                        addInterface(listenerInterface)
                    }
                }
                XposedBridge.log("WAP: Benefit listener classes ($listenerInterface) = ${listeners.size}")
                listeners.forEach { classData ->
                    val clazz = classData.getInstance(loader)
                    if (!seen.add(clazz.name)) return@forEach
                    collectBenefitClass(clazz, classes)
                }
            }
        }

        if (classes.isEmpty()) {
            // Never cache an empty result (same guard as the other scan modes).
            throw IllegalStateException("No benefit provider found")
        }

        return Cache(
            waVersion = appVersion,
            moduleVersion = BuildConfig.VERSION_CODE,
            enumClass = "",
            classes = classes
        )
    }

    /**
     * Benefit keys that unlock every premium feature, one per Meta app.
     * (populateBenefits is a no-op for WhatsApp, whose master field is boolean.)
     */
    fun populateBenefitKeys(cache: Cache, mode: ScanMode): Cache =
        cache.copy(benefitKeys = benefitKeysFor(mode))

    /**
     * Benefit keys that unlock every premium feature, one per Meta app.
     * Instagram (from X.C7ij.A0C's switch) and Facebook (from X.9Sb.A03's switch).
     */
    fun benefitKeysFor(mode: ScanMode): List<String> = when (mode) {
        ScanMode.WHATSAPP -> emptyList()
        ScanMode.INSTAGRAM -> IG_BENEFIT_KEYS
        ScanMode.FACEBOOK -> FB_BENEFIT_KEYS
    }

    /** Collect (String) -> boolean checks, the Set master field, and (Set) -> void listeners from a class. */
    private fun collectBenefitClass(clazz: Class<*>, classes: MutableList<CachedClass>) {
        val stringMethods = mutableListOf<String>()
        val setArgMethods = mutableListOf<String>()

        clazz.declaredMethods.forEach methods@ { method ->
            if (method.parameterTypes.size != 1) return@methods
            val param = method.parameterTypes[0]
            if (method.returnType == Boolean::class.javaPrimitiveType && param == String::class.java) {
                XposedBridge.log("WAP: Found ${clazz.name}.${method.name}(String) [benefit]")
                stringMethods += method.name
            } else if (method.returnType == Void.TYPE && param == java.util.Set::class.java) {
                XposedBridge.log("WAP: Found ${clazz.name}.${method.name}(Set) [listener]")
                setArgMethods += method.name
            }
        }

        // The benefit provider holds a java.util.Set field (A01) of active benefit keys;
        // the UI reads it directly in places, so we record it to populate after construction.
        val setField = clazz.declaredFields.firstOrNull {
            it.type == java.util.Set::class.java
        }?.name

        // A class qualifies if it has benefit checks, listeners, or the master Set field
        // (pure set-holders are covered by the constructor hook alone).
        if (stringMethods.isNotEmpty() || setArgMethods.isNotEmpty() || setField != null) {
            XposedBridge.log("WAP: Benefit class ${clazz.name} setField=$setField")
            classes += CachedClass(clazz.name, emptyList(), emptyList(), null, stringMethods, setField, setArgMethods)
        }
    }

    fun loadCache(pkg: String): Cache? {
        try {
            val file = File(cachePath(pkg))
            if (!file.exists()) {
                XposedBridge.log("WAP: Cache doesn't exist")
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

                val stringMethods = mutableListOf<String>()
                val stringArray = obj.optJSONArray("stringMethods") ?: JSONArray()
                for (j in 0 until stringArray.length()) {
                    stringMethods += stringArray.getString(j)
                }

                val setField = obj.optString("setField", "").takeIf { it.isNotEmpty() }

                val setArgMethods = mutableListOf<String>()
                val setArgArray = obj.optJSONArray("setArgMethods") ?: JSONArray()
                for (j in 0 until setArgArray.length()) {
                    setArgMethods += setArgArray.getString(j)
                }

                val objectArgMethods = mutableListOf<String>()
                val objectArgArray = obj.optJSONArray("objectArgMethods") ?: JSONArray()
                for (j in 0 until objectArgArray.length()) {
                    objectArgMethods += objectArgArray.getString(j)
                }

                classes += CachedClass(
                    obj.getString("name"), enumMethods, plainMethods, masterField,
                    stringMethods, setField, setArgMethods,
                    objectArgMethods, obj.optInt("objectArgIndex", 1),
                    obj.optString("objectArgField", "").takeIf { it.isNotEmpty() }
                )
            }

            val benefitKeys = mutableListOf<String>()
            val keyArray = json.optJSONArray("benefitKeys") ?: JSONArray()
            for (j in 0 until keyArray.length()) {
                benefitKeys += keyArray.getString(j)
            }

            return Cache(
                waVersion = json.getLong("waVersion"),
                moduleVersion = json.getInt("moduleVersion"),
                enumClass = json.getString("enumClass"),
                classes = classes,
                benefitKeys = benefitKeys
            )
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
            return null
        }
    }

    fun saveCache(cache: Cache, pkg: String) {
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

                val stringArr = JSONArray()
                clazz.stringMethods.forEach { stringArr.put(it) }
                obj.put("stringMethods", stringArr)

                obj.put("setField", clazz.setField ?: "")

                val setArgArr = JSONArray()
                clazz.setArgMethods.forEach { setArgArr.put(it) }
                obj.put("setArgMethods", setArgArr)

                val objectArgArr = JSONArray()
                clazz.objectArgMethods.forEach { objectArgArr.put(it) }
                obj.put("objectArgMethods", objectArgArr)
                obj.put("objectArgIndex", clazz.objectArgIndex)
                obj.put("objectArgField", clazz.objectArgField ?: "")

                classArray.put(obj)
            }
            root.put("classes", classArray)

            val keyArr = JSONArray()
            cache.benefitKeys.forEach { keyArr.put(it) }
            root.put("benefitKeys", keyArr)

            File(cachePath(pkg)).writeText(root.toString())
            XposedBridge.log("WAP: Cache saved")
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
        }
    }

    fun deleteCache(pkg: String) {
        try {
            File(cachePath(pkg)).delete()
            XposedBridge.log("WAP: Cache deleted")
        } catch (t: Throwable) {
            XposedBridge.log("WAP: " + t.stackTraceToString())
        }
    }

    /**
     * Version detection without the fragile PackageParser hidden-API path.
     * ApplicationInfo carries the version code directly and works on every device;
     * the field is read via reflection so it survives any SDK level.
     */
    fun getAppVersion(lpparam: LoadPackageParam): Long {
        return try {
            val info = lpparam.appInfo
            try {
                XposedHelpers.getLongField(info, "longVersionCode")
            } catch (_: Throwable) {
                XposedHelpers.getIntField(info, "versionCode").toLong()
            }
        } catch (t: Throwable) {
            XposedBridge.log("WAP: Failed to read version code: ${t.message}")
            0L
        }
    }
}

// Instagram premium benefit keys, from X.C7ij.A0C's entitlement switch.
private val IG_BENEFIT_KEYS = listOf(
    "STORY_CUSTOM_LISTS",
    "LIVE_NOTES",
    "STORY_SUPERLIKES",
    "PREMIUM_MESSAGE_STICKERS",
    "ENHANCED_LISTS",
    "SEARCH_STORY_VIEWERS",
    "STORY_EXTEND",
    "CUSTOM_APP_ICON",
    "CUSTOM_PROFILE_BIO_FONT",
    "CUSTOM_RINGTONES",
    "IG_ENHANCED_PROFILE_EVENTS",
    "IG_ENHANCED_PROFILE_MENTIONS",
    "IG_ENHANCED_PROFILE_OTHER_PROFILES",
    "IG_ENHANCED_PROFILE_SHAREABLE_LINKS",
    "IG_LINKS_IN_REELS",
    "IGD_VOICE_EFFECTS",
    "TEXT_RESPONSES",
    "AI_CREDITS",
    "SUGGESTED_REELS",
    "UNLIMITED_INSIGHTS_HISTORY",
    "AUDIENCE_CONNECTIONS",
    "PREMIUM_BENCHMARKING",
    "CORE_TIER_BENEFIT_MV4B_TIER_1",
    "CORE_TIER_BENEFIT_MV4B_TIER_2",
    "CORE_TIER_BENEFIT_MV4B_TIER_3",
    "CORE_TIER_BENEFIT_MV4B_TIER_4",
    "CORE_TIER_BENEFIT_LEGACY_MV4B_TIER_1",
    "MV_FEED",
    "NEXT_GEN_WA_ULTRA_BENEFIT",
    "DISPLAY_ONLY_AI_APPS_ACCESS",
    "ACCESS_TO_TRIAL_FEATURES",
    "CUSTOMER_SUPPORT_ACCESS_TO_SUPPORT_HOME",
    "HC_BENEFIT",
    "BIZ_WA_CHAT_ASSIGNMENT",
    "BIZ_WA_PROTECTED_BUSINESS_ACCOUNT",
    "BIZ_WA_NEW_CHAT_THREADS_LIMIT",
    "IMAGINE_VIDEO",
    "DEPRECATED_MV_FEED"
)

// Facebook Plus benefit keys, from X.9Sb.A03's entitlement map.
private val FB_BENEFIT_KEYS = listOf(
    "CUSTOM_APP_ICON",
    "STORY_EXTEND",
    "STORY_PREVIEW",
    "ENHANCED_CONTENT_SCHEDULING",
    "STORY_SUPERLIKES",
    "ENHANCED_CONTENT_PROTECTION",
    "SEARCH_STORY_VIEWERS",
    "BIZ_LINKS_IN_REELS",
    "STORY_REWATCH"
)

// Data models for the Cache structure
data class Cache(
    val waVersion: Long,
    val moduleVersion: Int,
    val enumClass: String,
    val classes: List<CachedClass>,
    val benefitKeys: List<String> = emptyList()
)

data class CachedClass(
    val name: String,
    val enumMethods: List<String>,
    val plainMethods: List<String>,
    val masterField: String? = null,
    val stringMethods: List<String> = emptyList(),
    val setField: String? = null,
    val setArgMethods: List<String> = emptyList(),
    val objectArgMethods: List<String> = emptyList(),
    val objectArgIndex: Int = 1,
    val objectArgField: String? = null
)
