package dev.uta.valencedextended.voicecord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.valenced.Logger
import app.valenced.xposed.api.HostScope
import app.valenced.xposed.hook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** SAF の結果をプロセス内で受け取る。選択中の再要求はキャンセルと同じ null を返す。 */
internal object FilePicker {
    private data class Selection(val context: Context, val uri: Uri)
    private class Pending(val requestCode: Int) {
        val result = CompletableDeferred<Selection?>()
    }

    private val pending = AtomicReference<Pending?>()
    private val nextRequestCode = AtomicInteger(0x5643)
    private val hooked = mutableSetOf<Method>()

    suspend fun pick(host: HostScope, log: Logger): Map<String, Any?>? {
        val pick = Pending(nextRequestCode.getAndUpdate { if (it == 0x7fff) 0x5643 else it + 1 })
        if (!pending.compareAndSet(null, pick)) return null
        try {
            installHook()
            host.withAppActivity { activity ->
                activity.runOnUiThread {
                    // 待機中に呼び出し元がキャンセルされた場合は選択画面を開かない。
                    if (pending.get() !== pick || !pick.result.isActive) return@runOnUiThread
                    runCatching {
                        activity.startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "audio/*"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            },
                            pick.requestCode,
                        )
                    }.onFailure { pick.result.completeExceptionally(it) }
                }
            }
            val selection = pick.result.await() ?: return null
            return withContext(Dispatchers.IO) { describe(selection) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("VoiceCord: file selection failed", e)
            throw e
        } finally {
            pending.compareAndSet(pick, null)
            pick.result.cancel()
        }
    }

    @Synchronized
    private fun installHook() {
        // dispatch は具象 Activity の override より手前で必ず通る。OS ごとの reason 引数の有無にも対応する。
        val methods = Activity::class.java.declaredMethods.filter { method ->
            val types = method.parameterTypes
            method.name == "dispatchActivityResult" && types.size >= 4 &&
                types[0] == String::class.java && types[1] == Int::class.javaPrimitiveType &&
                types[2] == Int::class.javaPrimitiveType && types[3] == Intent::class.java
        }
        check(methods.isNotEmpty()) { "Activity.dispatchActivityResult is unavailable" }
        for (method in methods) {
            if (method in hooked) continue
            method.isAccessible = true
            method.hook {
                before {
                    val pick = pending.get() ?: return@before
                    // who がある結果は Fragment 等の所有物なので触らない。
                    if (args[0] != null || args[1] != pick.requestCode) return@before
                    val activity = thisObject as Activity
                    val uri = (args[3] as? Intent)?.data
                    pick.result.complete(
                        if (args[2] == Activity.RESULT_OK && uri != null) Selection(activity.applicationContext, uri)
                        else null
                    )
                    // 自分のリクエストだけ消費し、Discord の結果処理へは流さない。
                    result = null
                }
            }
            hooked += method
        }
    }

    private fun describe(selection: Selection): Map<String, Any?> {
        val (context, uri) = selection
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var name = uri.lastPathSegment ?: uri.toString()
        // サイズを公開しないプロバイダでは Android の慣例に合わせて -1 とする。
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val input = checkNotNull(resolver.openInputStream(uri)) { "Unable to read selected audio" }
        input.use { stream ->
            val buffer = ByteArray(8192)
            var remaining = 64 * 1024
            while (remaining > 0) {
                val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        val alphabet = "0123456789abcdef"
        val hash = buildString(64) {
            for (byte in digest.digest()) {
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0xf])
            }
        }
        return mapOf("uri" to uri.toString(), "name" to name, "size" to size, "hash" to hash)
    }
}
