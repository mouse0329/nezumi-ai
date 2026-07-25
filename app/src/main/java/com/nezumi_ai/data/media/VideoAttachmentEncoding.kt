package com.nezumi_ai.data.media

/**
 * DB スキーマを変えないまま「このメッセージは動画由来のフレーム列である」ことを
 * `MessageEntity.imageUri` (カンマ区切り URI 文字列) に載せるためのエンコーディング。
 *
 * imageUri 先頭に以下のマーカーを差し込む:
 *   `nezumi://videoframes?video=<encoded>&audio=<encoded>&dur=<ms>`
 * その後に通常通り `,frame1Uri,frame2Uri,...` が続く。
 *
 * 既存の `split(",")` フローと共存するために、この URL 自体には `,` を含めない。
 * (URI エンコード済み値のみを埋めるので `,` は現れない)
 */
object VideoAttachmentEncoding {
    private const val SCHEME = "nezumi://videoframes"
    const val MARKER_PREFIX = "nezumi://videoframes"

    data class Meta(
        val originalVideoUri: String?,
        val audioUri: String?,
        val durationMs: Long
    )

    fun encode(meta: Meta): String {
        val q = buildString {
            append("?")
            append("video=").append(enc(meta.originalVideoUri.orEmpty()))
            append("&audio=").append(enc(meta.audioUri.orEmpty()))
            append("&dur=").append(meta.durationMs.coerceAtLeast(0L))
        }
        return "$SCHEME$q"
    }

    fun isMarker(token: String): Boolean = token.startsWith(MARKER_PREFIX)

    fun tryDecode(token: String): Meta? {
        if (!isMarker(token)) return null
        val q = token.substringAfter('?', "")
        if (q.isEmpty()) return Meta(null, null, 0L)
        val map = q.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to dec(kv[1]) else null
        }.toMap()
        return Meta(
            originalVideoUri = map["video"].takeUnless { it.isNullOrBlank() },
            audioUri = map["audio"].takeUnless { it.isNullOrBlank() },
            durationMs = map["dur"]?.toLongOrNull() ?: 0L
        )
    }

    /**
     * `MessageEntity.imageUri` から先頭のマーカーとフレーム URI 列を分離する。
     * マーカーが無いメッセージは (null, imageUri) が返る。
     */
    fun split(imageUri: String?): Pair<Meta?, List<String>> {
        if (imageUri.isNullOrBlank()) return null to emptyList()
        val parts = imageUri.split(',').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null to emptyList()
        val head = parts.first()
        return if (isMarker(head)) {
            tryDecode(head) to parts.drop(1)
        } else {
            null to parts
        }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun dec(s: String): String =
        java.net.URLDecoder.decode(s, Charsets.UTF_8.name())
}
