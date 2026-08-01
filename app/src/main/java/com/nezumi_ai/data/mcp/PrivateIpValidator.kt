package com.nezumi_ai.data.mcp

import android.net.Uri
import java.net.InetAddress

/**
 * MCP サーバー URL のホストがプライベートネットワーク（LAN内 / localhost）かどうかを判定する。
 *
 * 本番環境ではパブリックなドメイン/IPへの平文HTTP通信は許可しない。
 * 一方で、ユーザーが自宅・オフィスのLAN上で動かすMCPテストサーバー
 * （例: http://192.168.1.23:3000/mcp）には接続できる必要があるため、
 * "宛先がプライベートIPレンジの場合に限り" 平文HTTPを許可する設計にする。
 *
 * 参照レンジ:
 * - RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
 * - RFC 3927: 169.254.0.0/16 (リンクローカル)
 * - loopback: 127.0.0.0/8, ::1
 * - RFC 4193: fc00::/7 (IPv6 ユニークローカル)
 */
object PrivateIpValidator {

    /**
     * URL文字列を検査し、平文HTTP(http://)での接続が許容されるかどうかを返す。
     * DNS解決を伴うため、IOスレッドから呼ぶこと（Compose の再コンポーズ中など
     * メインスレッドから呼んではいけない）。
     *
     * - https:// は常に許可（通信路自体が暗号化されているため）
     * - http:// はホストがプライベートIP/localhost/.local の場合のみ許可
     * - それ以外（パブリックホストへの http://）は不許可
     *
     * ホスト名（IPアドレス表記でないもの）は名前解決してIPで判定する。
     * 解決に失敗した場合は安全側に倒して不許可とする。
     */
    fun isCleartextAllowed(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: return false

        return when (scheme) {
            "https" -> true
            "http" -> isPrivateHostResolved(host)
            else -> false
        }
    }

    /**
     * 検証結果とユーザー向けメッセージをまとめて返す。保存前バリデーション用。
     */
    sealed class ValidationResult {
        data object Ok : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    /**
     * UI入力のライブバリデーション用。DNS解決は行わず、IPリテラル表記と
     * localhost/.local のホスト名のみを同期的に判定する（メインスレッドから呼んでよい）。
     *
     * ホスト名（例: myserver.example.com）が実際にプライベートIPを指しているかどうかの
     * 最終判定は、通信直前に [isCleartextAllowed]（IOスレッド）で行う。
     * そのため、ホスト名指定のhttp://はここでは一旦「保存自体は許可」し、
     * 実際の接続時にプライベートIPでなければ [McpClient] 側でブロックされる。
     */
    fun validate(url: String): ValidationResult {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return ValidationResult.Error("URLの形式が正しくありません")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host

        if (host.isNullOrBlank()) {
            return ValidationResult.Error("URLにホスト名が含まれていません")
        }

        return when (scheme) {
            "https" -> ValidationResult.Ok
            "http" -> {
                if (isPrivateHostSync(host)) {
                    ValidationResult.Ok
                } else {
                    ValidationResult.Error(
                        "http:// で接続できるのは同じLAN内のサーバー（例: 192.168.x.x, 10.x.x.x, " +
                            "localhost）のみです。インターネット上のサーバーには https:// を使用してください。"
                    )
                }
            }
            else -> ValidationResult.Error("http:// または https:// のURLを入力してください")
        }
    }

    /** DNS解決なしの同期判定。IPリテラルとlocalhost系ホスト名のみ判定できる。 */
    private fun isPrivateHostSync(host: String): Boolean {
        val normalizedHost = host.lowercase()
        if (normalizedHost == "localhost" || normalizedHost.endsWith(".local")) return true
        if (isLiteralPrivateIpv4(host)) return true
        // IPv6リテラルの簡易判定（DNS解決なし）
        if (host == "::1") return true
        if (normalizedHost.startsWith("fc") || normalizedHost.startsWith("fd")) return true
        if (normalizedHost.startsWith("fe80:")) return true
        // ここに来る = ホスト名形式（未解決）。保存自体はブロックしない
        // （実接続時に isCleartextAllowed が最終判定を行うため）。
        // ただしIPv4/IPv6の "見た目" をしたパブリックアドレスは即座に弾く。
        return !looksLikeIpAddress(host)
    }

    private fun looksLikeIpAddress(host: String): Boolean {
        return ipv4Regex.matches(host) || host.contains(":")
    }

    /** DNS解決込みの厳密判定。ネットワークI/Oを伴うのでIOスレッド専用。 */
    private fun isPrivateHostResolved(host: String): Boolean {
        val normalizedHost = host.lowercase()
        if (normalizedHost == "localhost" || normalizedHost.endsWith(".local")) return true

        val address = runCatching { InetAddress.getByName(host) }.getOrElse {
            // 名前解決に失敗した場合は安全側に倒して不許可
            return false
        }
        return isPrivateInetAddress(address)
    }

    private fun isPrivateInetAddress(address: InetAddress): Boolean {
        return address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress || // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 を含む
            address.isAnyLocalAddress ||
            isUniqueLocalIpv6(address)
    }

    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        // fc00::/7 : 先頭バイトが 0xFC または 0xFD
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    private fun isLiteralPrivateIpv4(host: String): Boolean {
        val match = ipv4Regex.matchEntire(host) ?: return false
        val octets = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b, _, _) = octets

        return when {
            a == 10 -> true                                  // 10.0.0.0/8
            a == 172 && b in 16..31 -> true                   // 172.16.0.0/12
            a == 192 && b == 168 -> true                       // 192.168.0.0/16
            a == 127 -> true                                   // 127.0.0.0/8 (loopback)
            a == 169 && b == 254 -> true                        // 169.254.0.0/16 (link-local)
            else -> false
        }
    }
}
