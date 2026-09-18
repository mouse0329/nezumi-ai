package com.nezumi_ai.data.inference

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * .litertlm コンテナのセクション情報（type / バイト範囲 / メタ items）。
 *
 * .litertlm はヘッダー(FlatBuffers)＋複数セクション(TFLiteモデル本体、重み、
 * トークナイザー、LLM メタデータ等)を束ねたコンテナ形式。
 * [parseLitertlmSections] はこのヘッダー部分のみを解析し、各セクションの
 * [begin, end) バイト範囲とメタ情報 items を返す（テンソルや重み自体は読まない）。
 */
data class LitertlmSection(
    val type: Int,
    val begin: Long,
    val end: Long,
    val items: Map<String, String>
) {
    val typeName: String get() = when (type) {
        1 -> "GenericBinaryData"
        3 -> "TFLiteModel"
        4 -> "SP_Tokenizer"
        5 -> "LlmMetadataProto"
        6 -> "HF_Tokenizer_Zlib"
        7 -> "TFLiteWeights"
        else -> "type($type)"
    }

    /** セクションのバイト長。[begin] > [end] や範囲異常時は 0 を返す。 */
    val sizeBytes: Long get() = (end - begin).coerceAtLeast(0L)
}

object LitertlmSectionType {
    const val NONE = 0
    const val GENERIC_BINARY = 1
    const val DEPRECATED = 2
    const val TFLITE_MODEL = 3
    const val SP_TOKENIZER = 4
    const val LLM_METADATA = 5
    const val HF_TOKENIZER_ZLIB = 6
    const val TFLITE_WEIGHTS = 7
}

const val HINT_MODEL_TYPE = "model_type"
private const val MAGIC = "LITERTLM"
private const val V_DATA_STRING_VALUE = 9 // VData union tag for StringValue

class LitertlmParseException(message: String) : Exception(message)

/** 最小限のFlatBuffersリーダー。テーブル/vtable走査のみをサポート。 */
private class Fb(private val buf: ByteBuffer) {
    fun u16(off: Int): Int = buf.getShort(off).toInt() and 0xFFFF
    fun u32(off: Int): Long = buf.getInt(off).toLong() and 0xFFFFFFFFL
    fun i32(off: Int): Int = buf.getInt(off)
    fun u64(off: Int): Long = buf.getLong(off)
    fun byteAt(off: Int): Int = buf.get(off).toInt() and 0xFF

    /** offにあるuoffsetを辿り、指す先の絶対位置を返す */
    fun indirect(off: Int): Int = off + u32(off).toInt()

    /** tablePosのテーブルにおけるフィールドidの絶対オフセットを返す。存在しなければ0 */
    fun field(tablePos: Int, id: Int): Int {
        val vtable = tablePos - i32(tablePos)
        val vtableSize = u16(vtable)
        val slot = 4 + 2 * id
        if (slot >= vtableSize) return 0
        val rel = u16(vtable + slot)
        if (rel == 0) return 0
        return tablePos + rel
    }

    /** fieldOffにあるuoffsetが指す文字列を読む */
    fun str(fieldOff: Int): String {
        val pos = indirect(fieldOff)
        if (pos + 4 > buf.capacity()) return ""
        val n = u32(pos).toInt()
        val end = pos + 4 + n
        if (end > buf.capacity()) return ""
        val bytes = ByteArray(n)
        val dup = buf.duplicate()
        dup.position(pos + 4)
        dup.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** SectionObject.items (field 0) を読み、文字列値のみmapに収める */
    fun sectionItems(obj: Int): Map<String, String> {
        val itemsField = field(obj, 0)
        if (itemsField == 0) return emptyMap()
        val vec = indirect(itemsField)
        val n = u32(vec).toInt()
        val items = mutableMapOf<String, String>()
        for (i in 0 until n) {
            val kvp = indirect(vec + 4 + 4 * i)
            val keyField = field(kvp, 0)
            val vtField = field(kvp, 1)
            val valField = field(kvp, 2)
            if (keyField == 0 || vtField == 0 || valField == 0) continue
            if (byteAt(vtField) != V_DATA_STRING_VALUE) continue
            val valTable = indirect(valField)
            val sField = field(valTable, 0)
            if (sField != 0) {
                items[str(keyField)] = str(sField)
            }
        }
        return items
    }
}

/**
 * .litertlm ファイルのヘッダーをパースし、セクション一覧を返す。
 * @param bytes ファイル全体のバイト列(ByteArray)
 */
fun parseLitertlmSections(bytes: ByteArray): List<LitertlmSection> {
    if (bytes.size < MAGIC.length || String(bytes, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) {
        throw LitertlmParseException("missing \"$MAGIC\" magic")
    }
    if (bytes.size < 32) {
        throw LitertlmParseException("file too short (${bytes.size} bytes)")
    }

    val fullBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val headerEnd = fullBuf.getLong(24)
    if (headerEnd < 32 || headerEnd > bytes.size) {
        throw LitertlmParseException("bad header_end_offset $headerEnd")
    }

    // FlatBufferはオフセット32から始まる。fbは「32からの相対座標系」で扱う
    val fbBytes = bytes.copyOfRange(32, headerEnd.toInt())
    val meta = Fb(ByteBuffer.wrap(fbBytes).order(ByteOrder.LITTLE_ENDIAN))

    val root = meta.indirect(0)

    // LiteRTLMMetaData.section_metadata = field 1
    val smField = meta.field(root, 1)
    if (smField == 0) throw LitertlmParseException("no section_metadata")
    val sm = meta.indirect(smField)

    // SectionMetadata.objects = field 0 (vector of SectionObject)
    val objsField = meta.field(sm, 0)
    if (objsField == 0) throw LitertlmParseException("no section objects")
    val objs = meta.indirect(objsField)

    val n = meta.u32(objs).toInt()
    val sections = mutableListOf<LitertlmSection>()
    for (i in 0 until n) {
        val obj = meta.indirect(objs + 4 + 4 * i)
        // SectionObject: items=field0, begin_offset=field1, end_offset=field2, data_type=field3
        var begin = 0L
        var end = 0L
        var type = 0

        meta.field(obj, 1).takeIf { it != 0 }?.let { begin = meta.u64(it) }
        meta.field(obj, 2).takeIf { it != 0 }?.let { end = meta.u64(it) }
        meta.field(obj, 3).takeIf { it != 0 }?.let { type = meta.byteAt(it) }

        val items = meta.sectionItems(obj)
        sections.add(LitertlmSection(type, begin, end, items))
    }
    return sections
}

/** ファイルとセクションから、指定セクションのバイト列を切り出す */
fun sectionBytes(fileBytes: ByteArray, section: LitertlmSection): ByteArray {
    if (section.end > fileBytes.size || section.begin > section.end) {
        throw LitertlmParseException("bad section range [${section.begin},${section.end})")
    }
    return fileBytes.copyOfRange(section.begin.toInt(), section.end.toInt())
}

/**
 * ディスク上の .litertlm ファイルから **ヘッダー部分のみ** を読み込み、セクション一覧を返す。
 *
 * モデル本体（数百 MB〜数 GB）を全部 [ByteArray] に読み込むと
 * ロード前見積もりの意味が薄れる（メモリ・IO を無駄に使う）ため、
 * まず先頭 32 バイトの固定ヘッダーから `header_end_offset` を読み取り、
 * 必要なぶんだけ ([0, header_end_offset)) を読む。
 *
 * @param path .litertlm ファイルの絶対パス
 * @param maxHeaderBytes ヘッダーとして読み込む最大バイト数（異常なファイルからの過大確保を防ぐガード）
 */
fun parseLitertlmSectionsFromFile(
    path: String,
    maxHeaderBytes: Long = 16L * 1024 * 1024, // 16MB もあれば通常のヘッダーには十分すぎる
): List<LitertlmSection> {
    RandomAccessFile(path, "r").use { raf ->
        val fileLen = raf.length()
        if (fileLen < 32) {
            throw LitertlmParseException("file too short (${fileLen} bytes)")
        }

        val fixedHeader = ByteArray(32)
        raf.seek(0)
        raf.readFully(fixedHeader)

        if (String(fixedHeader, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) {
            throw LitertlmParseException("missing \"$MAGIC\" magic")
        }

        val headerEnd = ByteBuffer.wrap(fixedHeader).order(ByteOrder.LITTLE_ENDIAN).getLong(24)
        if (headerEnd < 32 || headerEnd > fileLen) {
            throw LitertlmParseException("bad header_end_offset $headerEnd")
        }
        if (headerEnd > maxHeaderBytes) {
            throw LitertlmParseException("header_end_offset $headerEnd exceeds maxHeaderBytes $maxHeaderBytes")
        }

        val headerBytes = ByteArray(headerEnd.toInt())
        raf.seek(0)
        raf.readFully(headerBytes)

        return parseLitertlmSections(headerBytes)
    }
}
