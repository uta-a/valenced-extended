package dev.uta.valencedextended.voicecord

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/**
 * SAF の content:// URI を Discord プロセス内で直接開く。
 * コンパニオンの 127.0.0.1 ループバック配信（LocalFileServer / LocalFetcher）はこれで不要になる。
 * URI の読み取り権限は Discord 自身が持っている必要がある（ファイル選択を Discord 内で行い takePersistableUriPermission する）。
 */
internal object UriSource {
    fun open(ctx: Context, uriString: String): DecodeSession {
        val uri = Uri.parse(uriString)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "content:// の URI だけ再生できる" }
        return DecodeSession.open { it.setDataSource(ctx, uri, null) }
    }
}
