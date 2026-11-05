package com.nezumi_ai.voicevox

/**
 * VOICEVOX 音声ライブラリのクレジット表記／利用規約台帳。
 *
 * 方針:
 * - 本アプリでは「商用・非商用いずれでも利用可能」な音声ライブラリのみを同梱カタログに載せる。
 *   非商用限定（商用利用に個別の事前確認が必須）のライブラリは
 *   [VoicevoxManager.modelCatalog] から除外している（No.7 / ユーレイちゃん）。
 * - 生成音声を利用する際は VOICEVOX を利用した旨のクレジット表記が必須。
 *   さらに各音声ライブラリごとのクレジット表記も必要になるため、
 *   話者ごとの表記文字列と規約 URL をここで一元管理する。
 *
 * 参照: VOICEVOX 音声モデル利用規約 https://voicevox.hiroshiba.jp/term/
 */
object VoicevoxLicense {

    /** 商用利用の可否区分。 */
    enum class CommercialUse {
        /** 個人・法人ともにクレジット表記のみで商用利用可。 */
        ALLOWED,

        /** 個人は商用可。企業／法人が関わる場合のみ権利者への事前確認が必要。 */
        ALLOWED_WITH_CORPORATE_CHECK,

        /** 商用可だが、用途・作品種別に追加制限がある。 */
        ALLOWED_WITH_RESTRICTION,

        /** 規約の公開が終了しているなど、利用者側での確認が必要。 */
        NEEDS_USER_CHECK
    }

    data class Entry(
        /** VoicevoxManager のカタログ上の話者名。 */
        val speaker: String,
        /** 生成音声に付与すべきクレジット表記。 */
        val credit: String,
        /** 利用規約 URL。 */
        val termsUrl: String,
        val commercialUse: CommercialUse = CommercialUse.ALLOWED,
        /** 追加の注意事項（UI に表示する）。 */
        val note: String? = null
    ) {
        val commercialLabel: String
            get() = when (commercialUse) {
                CommercialUse.ALLOWED -> "商用・非商用ともに利用可"
                CommercialUse.ALLOWED_WITH_CORPORATE_CHECK -> "個人は商用可 / 企業利用は要事前確認"
                CommercialUse.ALLOWED_WITH_RESTRICTION -> "商用可（用途制限あり）"
                CommercialUse.NEEDS_USER_CHECK -> "利用前に規約の確認が必要"
            }
    }

    private const val ZUNKO = "https://zunko.jp/con_ongen_kiyaku.html"
    private const val VIRVOX = "https://www.virvoxproject.com/voicevoxの利用規約"

    /** VOICEVOX 本体（音声モデル）の利用規約。 */
    const val VOICEVOX_TERMS_URL = "https://voicevox.hiroshiba.jp/term/"
    const val VOICEVOX_CREDIT = "VOICEVOX"

    /** VOICEVOX 本体の許諾内容の要約。アプリ内ライセンス画面で表示する。 */
    val voicevoxCoreSummary: List<String> = listOf(
        "商用・非商用問わず利用できます。",
        "アプリケーションに組み込んで再配布できます。",
        "作成された音声を利用する際は、各音声ライブラリの規約に従ってください。",
        "作成された音声の利用を他者に許諾する際は、当該他者に対し本許諾内容の 3 及び 4 の遵守を義務付けてください。",
        "禁止事項: 逆コンパイル・リバースエンジニアリング及びその方法の公開、製作者または第三者に不利益をもたらす行為、公序良俗に反する行為。",
        "本ソフトウェアにより生じた損害・不利益について、製作者は一切の責任を負いません。",
        "ご利用の際は VOICEVOX を利用したことがわかるクレジット表記が必要です。"
    )

    private val entries: List<Entry> = listOf(
        Entry("四国めたん", "VOICEVOX:四国めたん", ZUNKO),
        Entry("ずんだもん", "VOICEVOX:ずんだもん", ZUNKO),
        Entry("春日部つむぎ", "VOICEVOX:春日部つむぎ", "https://tsumugi-official.studio.site/rule"),
        Entry("波音リツ", "VOICEVOX:波音リツ", "http://canon-voice.com/kiyaku.html"),
        Entry("玄野武宏", "VOICEVOX:玄野武宏", VIRVOX),
        Entry("白上虎太郎", "VOICEVOX:白上虎太郎", VIRVOX),
        Entry(
            "青山龍星", "VOICEVOX:青山龍星", VIRVOX,
            CommercialUse.ALLOWED_WITH_CORPORATE_CHECK,
            "企業が携わる形で利用する場合は「ななはぴ」(https://v.seventhh.com/contact/) への事前確認が必要です。"
        ),
        Entry("冥鳴ひまり", "VOICEVOX:冥鳴ひまり", "https://meimeihimari.wixsite.com/himari/terms-of-use"),
        Entry("九州そら", "VOICEVOX:九州そら", ZUNKO),
        Entry(
            "もち子さん", "VOICEVOX:もち子(cv 明日葉よもぎ)",
            "https://vtubermochio.wixsite.com/mochizora/利用規約",
            CommercialUse.ALLOWED_WITH_RESTRICTION,
            "音声作品・音声素材・ゲーム作品等を除いて商用・非商用で利用可能。企業が携わる形で利用する場合は「もちぞら模型店」への事前確認が必要です。"
        ),
        Entry("剣崎雌雄", "VOICEVOX:剣崎雌雄", "https://frontier.creatia.cc/fanclubs/413/posts/4507"),
        Entry("WhiteCUL", "VOICEVOX:WhiteCUL", "https://www.whitecul.com/guideline"),
        Entry(
            "後鬼", "VOICEVOX:後鬼", "https://ついなちゃん.com/voicevox_terms/",
            CommercialUse.ALLOWED_WITH_CORPORATE_CHECK,
            "企業が携わる形で利用する場合は【鬼っ子ハンターついなちゃん】プロジェクトへの事前確認が必要です。"
        ),
        Entry(
            "ちび式じい", "VOICEVOX:ちび式じい",
            "https://docs.google.com/presentation/d/1AcD8zXkfzKFf2ertHwWRwJuQXjNnijMxhz7AJzEkaI4"
        ),
        Entry("櫻歌ミコ", "VOICEVOX:櫻歌ミコ", "https://voicevox35miko.studio.site/rule"),
        Entry("小夜/SAYO", "VOICEVOX:小夜/SAYO", "https://316soramegu.wixsite.com/sayo-official/guideline"),
        Entry("ナースロボ＿タイプＴ", "VOICEVOX:ナースロボ＿タイプＴ", "https://www.krnr.top/rules"),
        Entry("†聖騎士 紅桜†", "VOICEVOX:†聖騎士 紅桜†", "https://commons.nicovideo.jp/material/nc296132"),
        Entry("雀松朱司", "VOICEVOX:雀松朱司", VIRVOX),
        Entry("麒ヶ島宗麟", "VOICEVOX:麒ヶ島宗麟", VIRVOX),
        Entry("春歌ナナ", "VOICEVOX:春歌ナナ", "https://nanahira.jp/haruka_nana/guideline.html"),
        Entry("猫使アル", "VOICEVOX:猫使アル", "https://nekotukarb.wixsite.com/nekonohako/利用規約"),
        Entry("猫使ビィ", "VOICEVOX:猫使ビィ", "https://nekotukarb.wixsite.com/nekonohako/利用規約"),
        Entry("中国うさぎ", "VOICEVOX:中国うさぎ", ZUNKO),
        Entry("栗田まろん", "VOICEVOX:栗田まろん", "https://aivoice.jp/character/maron/"),
        Entry("あいえるたん", "VOICEVOX:あいえるたん", "https://www.infiniteloop.co.jp/special/iltan/terms/"),
        Entry("満別花丸", "VOICEVOX:満別花丸", "https://100hanamaru.wixsite.com/manbetsu-hanamaru/rule"),
        Entry("琴詠ニア", "VOICEVOX:琴詠ニア", "https://commons.nicovideo.jp/works/nc315435"),
        Entry(
            "Voidoll", "VOICEVOX:Voidoll(CV:丹下桜)", "https://blog.nicovideo.jp/niconews/224589.html",
            CommercialUse.ALLOWED_WITH_CORPORATE_CHECK,
            "法人による利用の場合は個別の問い合わせ (https://qa.nicovideo.jp/) が必要です。"
        ),
        Entry(
            "ぞん子", "VOICEVOX:ぞん子", "https://zonko.zone-energy.jp/guideline",
            CommercialUse.ALLOWED_WITH_CORPORATE_CHECK,
            "商用利用の場合は個別の問い合わせ (https://zonko.zone-energy.jp/contact) が必要です。"
        ),
        Entry("中部つるぎ", "VOICEVOX:中部つるぎ", ZUNKO),
        Entry("離途", "VOICEVOX:離途", "https://litmus9.com/#/voicebank#rules"),
        Entry("黒沢冴白", "VOICEVOX:黒沢冴白", VIRVOX),
        Entry("東北ずん子", "VOICEVOX:東北ずん子", ZUNKO),
        Entry("東北きりたん", "VOICEVOX:東北きりたん", ZUNKO),
        Entry("東北イタコ", "VOICEVOX:東北イタコ", ZUNKO),
        Entry("あんこもん", "VOICEVOX:あんこもん", ZUNKO),
        Entry("夜語トバリ", "VOICEVOX:夜語トバリ", "https://yogataritobari.studio.site/#rules"),
        Entry("暁記ミタマ", "VOICEVOX:暁記ミタマ", "https://yogataritobari.studio.site/#rules"),
        Entry("里石ユカ", "VOICEVOX:里石ユカ（つぼみ）", "https://satoishiyuka.wixsite.com/satoishi/kiyaku"),
        Entry(
            "雨晴はう", "VOICEVOX:雨晴はう", "",
            CommercialUse.NEEDS_USER_CHECK,
            "音声ライブラリの配布・規約公開が終了しているため、利用前に権利者の最新の案内をご確認ください。"
        )
    )

    /** VOICEVOX Nemo の話者（女声1〜6 / 男声1〜3）は「VOICEVOX Nemo」クレジットで共通。 */
    private val nemoEntry = Entry(
        speaker = "VOICEVOX Nemo",
        credit = "VOICEVOX Nemo",
        termsUrl = "https://voicevox.hiroshiba.jp/nemo/term/"
    )

    private val bySpeaker: Map<String, Entry> = entries.associateBy { it.speaker }

    /**
     * 商用利用が認められておらず、カタログから除外している話者。
     * 誤って再追加されないようにここに残す。
     */
    val excludedNonCommercialSpeakers: Set<String> = setOf("No.7", "ユーレイちゃん")

    fun forSpeaker(speaker: String): Entry? {
        bySpeaker[speaker]?.let { return it }
        // Nemo は「女声1」「男声2」等の匿名話者名
        if (speaker.startsWith("女声") || speaker.startsWith("男声")) return nemoEntry
        return null
    }

    fun creditFor(speaker: String): String = forSpeaker(speaker)?.credit ?: "VOICEVOX:$speaker"

    /** 話者リストから重複を除いたクレジット表記一覧を作る。 */
    fun creditsFor(speakers: Collection<String>): List<String> =
        speakers.map { creditFor(it) }.distinct()

    /** 話者リストに対応するライセンス項目一覧（UI 表示用）。 */
    fun entriesFor(speakers: Collection<String>): List<Entry> =
        speakers.mapNotNull { forSpeaker(it) }.distinctBy { it.credit }
}
