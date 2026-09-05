package com.nezumi_ai.data.miniapp

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 仕様 v1.1 §32「署名とインストール検証」の実装。
 *
 * signature.json 形式:
 * ```json
 * {
 *   "algorithm": "Ed25519",
 *   "keyId": "...",
 *   "publicKey": "<base64 X.509 Ed25519 public key>",
 *   "files": { "manifest.json": "sha256:...", ... },
 *   "signature": "<base64>"
 * }
 * ```
 * 検証順序（§32準拠、スキップ不可）:
 * ZIP構造 → Path Traversal検査 → Manifest検証 → Hash計算 → Hash一致
 *   → 署名検証 → Trusted Key確認
 */
object MiniAppSignatureVerifier {

    private const val TAG = "MiniAppSigVerifier"

    /** 検証結果。trusted=false かつ署名ありの場合は UNKNOWN_SIGNING_KEY として Dev Mode 確認へ回す。 */
    data class VerificationResult(
        val manifest: MiniAppManifest,
        val files: Map<String, ByteArray>,
        val signature: JSONObject?,
        val keyId: String?,
        val signed: Boolean,
        val trusted: Boolean
    )

    /** Ed25519 公開鍵の信頼リスト。開発者が手動で信頼登録した鍵を保持する。 */
    data class TrustedKey(val keyId: String, val publicKeyBase64: String, val label: String) {
        fun toJson() = JSONObject().apply {
            put("keyId", keyId); put("publicKey", publicKeyBase64); put("label", label)
        }

        companion object {
            fun fromJson(o: JSONObject) =
                TrustedKey(o.getString("keyId"), o.getString("publicKey"), o.optString("label", ""))
        }
    }

    // ---------------------------------------------------------------------
    // Trusted Key 管理
    // ---------------------------------------------------------------------

    fun listTrustedKeys(context: Context): List<TrustedKey> {
        val file = MiniAppStore.get(context).trustedKeysFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(file.readText())
            (0 until arr.length()).map { TrustedKey.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun addTrustedKey(context: Context, key: TrustedKey) {
        val keys = listTrustedKeys(context).filterNot { it.keyId == key.keyId }.toMutableList()
        keys.add(key)
        persistTrustedKeys(context, keys)
    }

    fun removeTrustedKey(context: Context, keyId: String) {
        persistTrustedKeys(context, listTrustedKeys(context).filterNot { it.keyId == keyId })
    }

    private fun persistTrustedKeys(context: Context, keys: List<TrustedKey>) {
        val arr = org.json.JSONArray()
        keys.forEach { arr.put(it.toJson()) }
        runCatching { MiniAppStore.get(context).trustedKeysFile().writeText(arr.toString()) }
            .onFailure { Log.w(TAG, "Failed to persist trusted keys", it) }
    }

    // ---------------------------------------------------------------------
    // 検証パイプライン
    // ---------------------------------------------------------------------

    /**
     * 展開済みファイル群（相対パス → バイト列）を検証する。
     * ZIP 構造/Path Traversal の検査は [MiniAppInstaller] 側で展開時に実施済みである前提。
     *
     * @throws MiniAppException PACKAGE_INVALID / PACKAGE_HASH_MISMATCH / SIGNATURE_INVALID /
     *         UNKNOWN_SIGNING_KEY / PACKAGE_TAMPERED
     */
    fun verify(context: Context, files: Map<String, ByteArray>): VerificationResult {
        // 1. Manifest 検証
        val manifestBytes = files["manifest.json"]
            ?: throw MiniAppException("PACKAGE_INVALID", "manifest.json がパッケージに含まれていません")
        val manifest = MiniAppManifest.parse(String(manifestBytes, Charsets.UTF_8))

        // エントリ HTML の存在確認
        if (!files.containsKey(manifest.entry)) {
            throw MiniAppException(
                "PACKAGE_INVALID",
                "エントリファイル '${manifest.entry}' がパッケージに含まれていません"
            )
        }

        val signatureJson = files["signature.json"]?.let { String(it, Charsets.UTF_8) }
        if (signatureJson == null) {
            // 未署名: Dev Mode 確認へ（§32 Developer Mode）
            return VerificationResult(
                manifest = manifest,
                files = files,
                signature = null,
                keyId = null,
                signed = false,
                trusted = false
            )
        }

        val sig = try {
            JSONObject(signatureJson)
        } catch (e: Exception) {
            throw MiniAppException("SIGNATURE_INVALID", "signature.json がJSONとして不正です: ${e.message}")
        }

        val algorithm = sig.optString("algorithm", "")
        if (!algorithm.equals("Ed25519", ignoreCase = true)) {
            throw MiniAppException("SIGNATURE_INVALID", "未対応の署名アルゴリズムです: $algorithm")
        }

        // 2. Hash 計算 → Hash 一致
        val filesObj = sig.optJSONObject("files")
            ?: throw MiniAppException("SIGNATURE_INVALID", "signature.json に files がありません")
        val checkedKeys = mutableSetOf<String>()
        for (key in filesObj.keys()) {
            val expected = filesObj.getString(key)
            if (!expected.startsWith("sha256:")) {
                throw MiniAppException("SIGNATURE_INVALID", "未対応のハッシュ形式です: $expected")
            }
            val actual = sha256Hex(files[key])
                ?: throw MiniAppException("PACKAGE_HASH_MISMATCH", "署名対象ファイルが欠落しています: $key")
            if (!actual.equals(expected.removePrefix("sha256:"), ignoreCase = true)) {
                throw MiniAppException("PACKAGE_HASH_MISMATCH", "ファイルハッシュが一致しません: $key")
            }
            checkedKeys.add(key)
        }
        // manifest.json は必ず署名対象であること（§11「Manifestも署名対象」）
        if ("manifest.json" !in checkedKeys) {
            throw MiniAppException("SIGNATURE_INVALID", "manifest.json が署名対象に含まれていません")
        }
        // signature.json 以外の全ファイルが署名対象に含まれること（取りこぼし=改ざん余地）
        val unsigned = files.keys.filter { it != "signature.json" && it !in checkedKeys }
        if (unsigned.isNotEmpty()) {
            throw MiniAppException(
                "PACKAGE_TAMPERED",
                "署名対象外のファイルが含まれています: ${unsigned.take(3).joinToString()}"
            )
        }

        // 3. 署名検証（署名対象 = signature フィールドを除いた canonical 表現）
        val publicKeyB64 = sig.optString("publicKey", "")
        if (publicKeyB64.isBlank()) {
            throw MiniAppException("SIGNATURE_INVALID", "signature.json に publicKey がありません")
        }
        val signatureB64 = sig.optString("signature", "")
        if (signatureB64.isBlank()) {
            throw MiniAppException("SIGNATURE_INVALID", "signature.json に signature がありません")
        }
        val payload = canonicalSignaturePayload(sig)
        val ok = runCatching {
            verifyEd25519(publicKeyB64, payload.toByteArray(Charsets.UTF_8), signatureB64)
        }.getOrDefault(false)
        if (!ok) {
            throw MiniAppException("SIGNATURE_INVALID", "署名検証に失敗しました")
        }

        // 4. Trusted Key 確認
        val keyId = sig.optString("keyId", "")
        val trusted = listTrustedKeys(context).any { it.keyId == keyId }
        if (!trusted) {
            throw MiniAppException(
                "UNKNOWN_SIGNING_KEY",
                "信頼されていない署名鍵です: $keyId",
                details = mapOf("keyId" to keyId, "publicKey" to publicKeyB64)
            )
        }

        return VerificationResult(
            manifest = manifest,
            files = files,
            signature = sig,
            keyId = keyId,
            signed = true,
            trusted = true
        )
    }

    /**
     * UNKNOWN_SIGNING_KEY を Dev Mode 同意で通す場合に、検証を鍵信頼登録込みで再実行する。
     */
    fun verifyTrustingKey(context: Context, files: Map<String, ByteArray>, key: TrustedKey): VerificationResult {
        addTrustedKey(context, key)
        return verify(context, files)
    }

    /** 署名対象ペイロード: algorithm/keyId/publicKey/files を決定的順序で連結。 */
    private fun canonicalSignaturePayload(sig: JSONObject): String {
        val sb = StringBuilder()
        sb.append("algorithm=").append(sig.optString("algorithm")).append('\n')
        sb.append("keyId=").append(sig.optString("keyId")).append('\n')
        sb.append("publicKey=").append(sig.optString("publicKey")).append('\n')
        val filesObj = sig.getJSONObject("files")
        for (key in filesObj.keys().asSequence().toList().sorted()) {
            sb.append("file=").append(key).append(':').append(filesObj.getString(key)).append('\n')
        }
        return sb.toString()
    }

    private fun sha256Hex(bytes: ByteArray?): String? {
        bytes ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun verifyEd25519(publicKeyB64: String, payload: ByteArray, signatureB64: String): Boolean {
        val keyBytes = Base64.decode(publicKeyB64, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val publicKey: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(publicKey)
        sig.update(payload)
        return sig.verify(Base64.decode(signatureB64, Base64.DEFAULT))
    }
}
