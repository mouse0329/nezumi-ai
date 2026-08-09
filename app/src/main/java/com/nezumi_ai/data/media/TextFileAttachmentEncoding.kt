package com.nezumi_ai.data.media

/**
 * DB スキーマを変えないまま「このメッセージにはテキストファイル添付がある」ことを
 * `MessageEntity.imageUri` (カンマ区切り URI 文字列) に載せるためのエンコーディング。
 *
 * `VideoAttachmentEncoding` と同じ思想で、テキストファイルの URI を 1 件につき
 *   `nezumi://txtfile?name=<url-encoded display name>&uri=<url-encoded uri>`
 * という 1 トークンにエンコードしてカンマ区切り列に混ぜる。
 * URL エンコード済みの値しか埋めないのでトークン自体に `,` は現れず、
 * 既存の `split(",")` フローと共存できる。
 *
 * 実体のテキストはプロンプトに `<txtfile>{name:"...",body:"..."}</txtfile>` として
 * 挿入されてモデルに渡る。URI は「一覧表示 / テキストビュワーで開く」ためだけに使い、
 * UI 側で生の <txtfile> タグは表示しない。
 */
object TextFileAttachmentEncoding {
    const val MARKER_PREFIX = "nezumi://txtfile"
    private const val SCHEME = MARKER_PREFIX

    data class TextFileEntry(
        val name: String,
        val uri: String,
        /**
         * PDF/Word/Excel 等のドキュメントをピック時点で Markdown 変換し、
         * uri が変換後の .md ファイルを指している場合に true。
         * ビュワーはこのフラグを見て「プレビュー可」と「変換前バイナリ」を区別する
         * (name には元の拡張子が残るため、拡張子だけでは判定できない)。
         */
        val isConvertedDocument: Boolean = false
    )

    fun encode(entry: TextFileEntry): String {
        val docParam = if (entry.isConvertedDocument) "&doc=1" else ""
        return "$SCHEME?name=${enc(entry.name)}&uri=${enc(entry.uri)}$docParam"
    }

    fun isMarker(token: String): Boolean = token.startsWith(MARKER_PREFIX)

    /**
     * Word / PDF / Excel / PowerPoint などのバイナリドキュメントかどうかを
     * 拡張子で判定する。
     *
     * これらのファイルはプレーンテキストとして読み込めないため、添付の送信時に
     * Chaquopy 経由の MarkItDown で Markdown 変換し、変換結果の本文を
     * プレーンテキスト添付と同じ <txtfile> ブロックとしてモデルに渡す。
     */
    fun isDocumentFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in DOCUMENT_FILE_EXTENSIONS

    /** UI 層のファイルピッカーと同じ定義。拡張子は小文字・ドット無しで持つ。 */
    private val DOCUMENT_FILE_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    )

    fun tryDecode(token: String): TextFileEntry? {
        if (!isMarker(token)) return null
        val q = token.substringAfter('?', "")
        val map = q.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to dec(kv[1]) else null
        }.toMap()
        val name = map["name"]?.takeIf { it.isNotBlank() } ?: return null
        val uri = map["uri"]?.takeIf { it.isNotBlank() } ?: return null
        val isConvertedDocument = map["doc"] == "1"
        return TextFileEntry(name = name, uri = uri, isConvertedDocument = isConvertedDocument)
    }

    /**
     * `MessageEntity.imageUri` (カンマ区切り) からテキストファイル添付の一覧を取り出す。
     * テキスト添付が無いメッセージでは空リストが返る。
     */
    fun extract(imageUri: String?): List<TextFileEntry> {
        if (imageUri.isNullOrBlank()) return emptyList()
        return imageUri.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { tryDecode(it) }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun dec(s: String): String =
        java.net.URLDecoder.decode(s, Charsets.UTF_8.name())
}
