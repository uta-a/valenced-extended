package dev.uta.valencedextended.voicecord

import android.app.AndroidAppHelper
import android.content.Context
import app.valenced.plugins.PluginManifest
import app.valenced.plugins.PluginScope
import app.valenced.plugins.Version
import app.valenced.xposed.BuildConfig
import app.valenced.xposed.api.registerNativeAsyncMethod
import app.valenced.xposed.api.registerNativeMethod
import app.valenced.xposed.tweaks.plugins.internal.InternalPluginFlags
import app.valenced.xposed.tweaks.plugins.internal.internalPlugin
import app.valenced.xposed.versionCode
import com.bytedance.shadowhook.ShadowHook
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

private val manifest = PluginManifest(
    id = "voicecord",
    name = "VoiceCord",
    description = "Mixes audio files and soundboard sounds into your outgoing voice.",
    author = "uta_a",
    version = Version.parse("0.1.0"),
)

/** libvoicecord.so の読み込みはプロセスにつき1回だけ。フックは stop で外し、start で張り直す。 */
private val nativeInitialized = AtomicBoolean(false)

/** stop 後もブリッジメソッドは登録されたまま残るので、有効中かどうかをここで見る。 */
private val active = AtomicBoolean(false)

@Volatile private var appContext: Context? = null
@Volatile private var discordVersionCode = -1
@Volatile private var player: Player? = null
@Volatile private var localPlayer: LocalPlayer? = null

internal val voicecordPlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ENABLED_BY_DEFAULT)) {
        start {
            val p = player ?: Player(log, bridge).also { player = it }
            val local = localPlayer ?: LocalPlayer(log).also { localPlayer = it }
            registerMethods(p, local)
            active.set(true)

            when {
                nativeInitialized.get() -> {
                    val r = NativeBridge.nativeReinstall()
                    check(r == 0) { "VoiceCord: failed to reinstall hooks: $r" }
                    NativeBridge.nativeSetInject(true)
                }
                // 起動後にオンにされた場合は、次の起動でフックを張る
                startedLate -> requireReload()
                else -> withAppContext { ctx -> initNative(ctx) }
            }
        }

        stop {
            active.set(false)
            localPlayer?.stop()
            player?.unsubscribe()
            if (nativeInitialized.get()) {
                // リングを空にして注入を止め、送信期限を自分が立てた分だけ 0 に戻してからフックを外す
                player?.stop()
                val failed = NativeBridge.nativeUninstall()
                if (failed != 0) {
                    // 外せなかったフックが残ったまま再開させない
                    log.w("VoiceCord: $failed hook(s) could not be removed")
                    requireReload()
                }
            }
        }
    }

private fun PluginScope.initNative(ctx: Context) {
    // attachBaseContext 時点の base Context では Application がまだ無く applicationContext が null になる。
    // ContentResolver と cacheDir は base Context でも使えるので、そのまま保持する。
    appContext = ctx.applicationContext ?: ctx
    log.i("VoiceCord: appContext set (${appContext?.javaClass?.name})")
    val vc = runCatching { ctx.versionCode().toInt() }
        .onFailure { log.e("VoiceCord: failed to get Discord versionCode", it) }
        .getOrDefault(-1)
    discordVersionCode = vc

    VersionGuard.load(modulePath)

    if (!VersionGuard.isSupported(vc)) {
        errors.tryEmit(
            IllegalStateException("VoiceCord はこの Discord（versionCode $vc）に未対応です。対応: ${VersionGuard.supported}")
        )
        return
    }

    try {
        val initErr = ShadowHook.init(
            ShadowHook.ConfigBuilder()
                .setMode(ShadowHook.Mode.UNIQUE)
                .setDebuggable(BuildConfig.DEBUG)
                .build()
        )
        check(initErr == 0) { "ShadowHook.init failed: $initErr (${ShadowHook.toErrmsg(initErr)})" }

        System.loadLibrary("voicecord")
        check(VersionGuard.applyOffsets(vc)) { "VoiceCord: missing or invalid offsets for versionCode $vc" }
        val r = NativeBridge.nativeInit()
        check(r == 0) { "nativeInit failed: $r" }
        NativeBridge.nativeSetVersion(vc)
        NativeBridge.nativeSetInject(active.get())
        nativeInitialized.set(true)
        log.i("VoiceCord: native initialized for versionCode $vc")
    } catch (t: Throwable) {
        errors.tryEmit(t)
    }
}

/** ネイティブ操作ができない理由。使えるなら null。 */
private fun unavailableReason(): String? = when {
    !active.get() -> "VoiceCord is disabled"
    nativeInitialized.get() -> null
    discordVersionCode >= 0 && !VersionGuard.isSupported(discordVersionCode) -> "unsupported Discord version"
    else -> "not initialized (reload Discord)"
}

/** initNative で保持した Context。無ければ現在の Application を取り直して保持する。 */
private fun PluginScope.resolveContext(): Context? {
    appContext?.let { return it }
    val app = runCatching { AndroidAppHelper.currentApplication() }.getOrNull()
    log.w("VoiceCord: appContext was null; fallback Application=${app?.javaClass?.name}")
    if (app != null) appContext = app
    return app
}

private fun failure(error: String) = mapOf("ok" to false, "error" to error)

private fun PluginScope.registerMethods(player: Player, local: LocalPlayer) {
    registerNativeMethod("voicecord.state") {
        val s = if (unavailableReason() == null) NativeBridge.nativeState() else 0
        mapOf(
            "hooked" to (s and NativeBridge.STATE_HOOKED != 0),
            "playing" to (s and NativeBridge.STATE_PLAYING != 0),
            "supported" to VersionGuard.isSupported(discordVersionCode),
            "versionCode" to discordVersionCode,
        )
    }

    registerNativeMethod("voicecord.subscribe") {
        player.subscribe()
        null
    }

    registerNativeAsyncMethod("voicecord.pickFile") {
        FilePicker.pick(this, log)
    }

    registerNativeMethod("voicecord.stop") {
        if (unavailableReason() == null) player.stop()
        null
    }

    registerNativeMethod("voicecord.setParams") { args ->
        val gain = (args.getOrNull(0) as? Number)?.toFloat()
        val duck = (args.getOrNull(1) as? Number)?.toFloat()
        require(gain != null && gain.isFinite() && duck != null && duck.isFinite()) { "gain and duck must be numbers" }
        if (unavailableReason() == null) NativeBridge.nativeSetParams(gain, duck)
        null
    }

    registerNativeAsyncMethod("voicecord.playUri") { args ->
        unavailableReason()?.let { return@registerNativeAsyncMethod failure(it) }
        val uri = args.getOrNull(0) as? String ?: return@registerNativeAsyncMethod failure("uri must be a string")
        val ctx = resolveContext() ?: return@registerNativeAsyncMethod failure("no app context")
        player.play { UriSource.open(ctx, uri) }
    }

    registerNativeAsyncMethod("voicecord.playSoundboard") { args ->
        unavailableReason()?.let { return@registerNativeAsyncMethod failure(it) }
        val id = args.getOrNull(0) as? String ?: return@registerNativeAsyncMethod failure("soundId must be a string")
        val ctx = resolveContext() ?: return@registerNativeAsyncMethod failure("no app context")
        player.play {
            val file = SoundboardFetcher.fetch(File(ctx.cacheDir, "voicecord"), id)
            DecodeSession.open { it.setDataSource(file.absolutePath) }
        }
    }

    // 端末だけで鳴らす（VC には送らないので、フックや対応版でなくても使える）
    registerNativeAsyncMethod("voicecord.previewUri") { args ->
        if (!active.get()) return@registerNativeAsyncMethod failure("VoiceCord is disabled")
        val uri = args.getOrNull(0) as? String ?: return@registerNativeAsyncMethod failure("uri must be a string")
        val volume = (args.getOrNull(1) as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: 1f
        val ctx = resolveContext() ?: return@registerNativeAsyncMethod failure("no app context")
        runCatching { local.play(ctx, uri, volume) }
            .fold({ mapOf("ok" to true) }, {
                log.e("VoiceCord: local playback failed", it)
                failure(it.message ?: it.javaClass.simpleName)
            })
    }

    registerNativeMethod("voicecord.setPreviewVolume") { args ->
        val volume = (args.getOrNull(0) as? Number)?.toFloat()
        require(volume != null && volume.isFinite()) { "volume must be a number" }
        local.setVolume(volume)
        null
    }

    registerNativeMethod("voicecord.stopPreview") {
        local.stop()
        null
    }

    // 疎通確認用: 440Hz のサイン波を 2 秒流す
    registerNativeMethod("voicecord.playTestTone") {
        unavailableReason()?.let { return@registerNativeMethod failure(it) }
        player.playPcm(ShortArray(OUT_RATE * 2) { i -> (sin(2 * PI * 440 * i / OUT_RATE) * 8000).toInt().toShort() })
        mapOf("ok" to true)
    }
}
