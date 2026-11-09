package app.valenced.xposed.tweaks

import de.robv.android.xposed.XposedBridge.hookAllConstructors
import de.robv.android.xposed.XposedHelpers
import app.valenced.plugins.Version
import app.valenced.xposed.DexFinder
import app.valenced.xposed.loadClassOrNull
import app.valenced.xposed.methodHook
import app.valenced.xposed.tweak
import app.valenced.xposed.tweaks.plugins.internal.DISCORD_VERSION
import org.luckypray.dexkit.query.enums.StringMatchType

var DISCORD_OTA_COMMIT: String? = null

// valenced-extended: 352.17 - Stable では更新クラスのコンストラクタ直後だと versionName が未初期化で読めない。
// OTA のコミットが分からないと、OTA で JS が差し替わってもモジュールのキャッシュが古いまま使われ、
// Design などを見つけられずに JS のプラグインが起動しない。その場合は後（キャッシュ名を決めるとき）に読み直す。
private var retryOtaCommit: (() -> String?)? = null

/** [DISCORD_OTA_COMMIT]。初期化時に読めなかったら読み直す。 */
fun discordOtaCommit(): String? {
    DISCORD_OTA_COMMIT?.let { return it }
    val retry = retryOtaCommit ?: return null
    retryOtaCommit = null
    return runCatching(retry).getOrNull()?.also { DISCORD_OTA_COMMIT = it }
}

private val VERSION_NAME = Regex("""^\d+(\.\d+)+$""")

val discordVersionRetriever by tweak {
    val version = try {
        // Discord dropped BuildConfig when it enabled R8.
        val buildConfig = classLoader.loadClassOrNull("com.discord.BuildConfig")
        val nativeVersionString = buildConfig
            ?.let { XposedHelpers.getStaticObjectField(it, "VERSION_NAME_RNA") as String }
            ?: findClientInfoVersionName()

        log.i("Discord loaded with version: $nativeVersionString")
        nativeVersionString?.let(Version::parse)
    } catch (e: Throwable) {
        log.e("Unable to get the Discord version", e)
        null
    }

    // Plugins can't resolve their Discord dependency without a version, so fall back to 0.
    if (version == null) log.w("Unknown Discord version, using 0")
    DISCORD_VERSION = version ?: Version(listOf(0))

    val updater = DiscordBundleUpdater(classLoader)
    val updaterClass = updater.updaterClass
    val getOtaCommit = updater.getOtaCommit
    if (updaterClass == null || getOtaCommit == null) {
        log.e("Unable to find Discord's bundle updater, so the OTA commit is unknown")
        return@tweak
    }

    // Discord creates the updater once, during app initialization.
    hookAllConstructors(updaterClass, methodHook {
        after {
            try {
                val commit = getOtaCommit.invoke(thisObject) as String?

                if (commit != null) {
                    log.i("Running on OTA commit: $commit")
                    DISCORD_OTA_COMMIT = commit
                } else log.i("Running on local commit")
            } catch (e: Throwable) {
                log.w("Unable to get Discord OTA commit yet, retrying later: ${e.cause ?: e}")
                val updater = thisObject
                retryOtaCommit = { getOtaCommit.invoke(updater) as String? }
            }
        }
    }.build())
}

/**
 * Reads the version name (e.g. `352.2`) that Discord's `ClientInfo.init` assigns, which R8 inlined from `BuildConfig.VERSION_NAME_RNA`.
 * The method runs after plugins load, so this reads the constant from the DEX instead.
 *
 * The method builds a user agent like `Discord-Android/<versionCode>;RNA`, but also sets the version name.
 */
private fun findClientInfoVersionName(): String? =
    DexFinder.findString("ClientInfo.versionName") {
        findMethod {
            matcher {
                addUsingString("Discord-Android/", StringMatchType.StartsWith)
                addUsingString(";RNA", StringMatchType.EndsWith)
            }
        }.singleOrNull()?.usingStrings?.singleOrNull(VERSION_NAME::matches)
    }
