package com.nezumi_ai.sd.safety

import android.util.Log

object PromptFilter {

    private const val TAG = "PromptFilter"

    // 明確に性的・暴力的なキーワード（英語・ローマ字）
    // 追加が必要な場合はここに追記するだけでよい
    private val EXTRA_COMPREHENSIVE_KEYWORDS: Set<String> = setOf(
    // ーーー 1. 衣服の排除・破壊（通常は使わない表現） ーーー
    "topless", "bottomless", "stripping", "undressing", "clothelss", "unclothed",
    "nakedness", "nudism", "bare-breasted", "disrobe", "shirtless", "pantyless",
    "nobra", "bra-less", "unbuttoned", "zipper-down", "ripped-clothes", "torn-clothes",

    // ーーー 2. 透け・密着（結果フィルターが最も誤判定しやすい「際どい」表現） ーーー
    "see-through", "sheer", "transparent-clothes", "wet-clothes", "body-paint", 
    "plunging-neckline", "high-leg", "micro-skirt", "submissively",

    // ーーー 3. 過激な下着・フェティッシュ衣装（日常の水着などは除外） ーーー
    "lingerie", "thong", "microbikini", "g-string", "bondage", "fetishwear", 
    "erotic-costume", "latex-suit", "pasties", "nipple-covers", "strapless-bra",

    // ーーー 4. 性的な状態・ジャンル・雰囲気の直接指定 ーーー
    "lewd", "erotic", "erotica", "sensual", "softcore", "hardcore", "smut",
    "vulgar", "lascivious", "lustful", "obscene", "suggestive", "playboy", 
    "pinup", "fetish", "voyeurism", "peeping","pussy",

    // ーーー 5. 性行為・ポーズ・体液の直接指定 ーーー
    "masturbate", "masturbation", "intercourse", "penetration", "copulation",
    "fellatio", "cunnilingus", "sodomy", "squirt", "ejaculation", "orgasm", 
    "spread-legs", "kneeling-pose", "doggy-style", "cowgirl-position",

    // ーーー 6. 海外の画像AIですり抜けに使われる日本語・オタク系スラング ーーー
    "ecchi", "oppai", "chinko", "manko", "av", "jav", "ahegao", "bukkake",
    "milf", "lolita", "loli", "shotacon", "shota", "hentai", "pantsu", 
    "sukumizu", "shimapan", "paizuri", "nakadashi", "ryona",
     // ーーー 1. 銃器・武器全般 ーーー
    "gun", "rifle", "pistol", "handgun", "shotgun", "revolver", "firearm",
    "weapon", "blade", "knife", "sword", "dagger", "machete", "axe",

    // ーーー 2. 暴力行為（アクション） ーーー
    "assault", "attack", "punch", "kick", "stab", "shoot",
    "execute", "execution", "torture", "beating", "fight", "combat",

    // ーーー 3. 負傷・残虐表現 ーーー
    "blood", "bloody", "bleeding", "gore", "gory", "wound", "injured",
    "injury", "mutilation", "corpse", "dead-body", "carnage", "slaughter", 
    "murder", "kill",

    // ーーー 4. 戦争・テロ・危険物 ーーー
    "war", "warfare", "battlefield", "terrorism", "terrorist", "explosion",
    "bomb", "grenade", "dynamite", "hostage", "kidnap",

    // ーーー 5. その他規制キーワード ーーー
    "guro", "bloodshed", "splatter"
    )


    enum class Result { ALLOW, BLOCK }

    /**
     * プロンプトを検査して ALLOW / BLOCK を返す。
     * BLOCK の場合、MNNサーバーへのリクエストをキャンセルすることで
     * 約50秒の無駄なUNET演算を防止する。
     */
    fun check(prompt: String): Result {
        val lower = prompt.lowercase()
        val hit: String? = EXTRA_COMPREHENSIVE_KEYWORDS.firstOrNull { lower.contains(it) }
        return if (hit != null) {
            Log.w(TAG, "Prompt blocked by keyword: \"$hit\"")
            Result.BLOCK
        } else {
            Result.ALLOW
        }
    }
}
