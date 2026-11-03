package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.data.media.VideoAttachmentEncoding

/**
 * クラスタ D (コンテキストメーター) の純粋計算層。
 *
 * StateFlow の更新・Repository アクセス・ModelManager 同期呼び出しは
 * [com.nezumi_ai.presentation.viewmodel.ChatViewModel] 側に残し、
 * メッセージ集合のフィルタ・添付カウント・セグメント内訳の算術だけをここに集約する。
 */
class ContextMeterCalculator(
    private val promptBuilding: PromptBuildingUseCase = PromptBuildingUseCase()
) {

    /**
     * バリアント選択を適用して「今モデルに見せるべき」メッセージ列を返す。
     * UI 表示用のフィルタと同じ規則（parent ごとに選択 index、未指定は最新）。
     */
    fun applyVariantSelection(
        all: List<MessageEntity>,
        selection: Map<Long, Int>
    ): List<MessageEntity> {
        val byParent: Map<Long, List<MessageEntity>> = all
            .filter { it.role != "user" && it.parentUserMessageId != null }
            .groupBy { it.parentUserMessageId!! }
            .mapValues { entry ->
                entry.value.sortedWith(compareBy({ it.variantIndex }, { it.timestamp }))
            }

        val visibleIds = HashSet<Long>()
        byParent.forEach { (parentId, variants) ->
            if (variants.isEmpty()) return@forEach
            val requested = selection[parentId]
            val chosen = if (requested != null && requested in variants.indices) {
                variants[requested]
            } else {
                variants.last()
            }
            visibleIds.add(chosen.id)
        }

        return all.filter { msg ->
            when {
                msg.role == "user" -> true
                msg.parentUserMessageId == null -> true
                else -> msg.id in visibleIds
            }
        }
    }

    /**
     * コンテキストメーター・プロンプト構築の双方で使う「モデル文脈に含めるメッセージ」。
     * 再生成中は [regeneratingParentUserMessageId] 配下の旧 assistant を除外する。
     */
    fun messagesIncludedInModelContext(
        messages: List<MessageEntity>,
        selectedVariantByParent: Map<Long, Int>,
        regeneratingParentUserMessageId: Long? = null
    ): List<MessageEntity> {
        val selected = applyVariantSelection(messages, selectedVariantByParent)
        return if (regeneratingParentUserMessageId != null) {
            selected.filterNot { msg ->
                msg.role != "user" && msg.parentUserMessageId == regeneratingParentUserMessageId
            }
        } else {
            selected
        }
    }

    /** 画像・音声添付の実体件数（ビデオ/テキストファイルマーカーは除外）。 */
    fun recountAttachmentCount(messages: List<MessageEntity>): Int {
        var count = 0
        for (message in messages) {
            message.imageUri?.split(',')?.forEach { raw ->
                val uri = raw.trim()
                if (uri.isEmpty()) return@forEach
                if (VideoAttachmentEncoding.isMarker(uri) ||
                    TextFileAttachmentEncoding.isMarker(uri)
                ) {
                    return@forEach
                }
                count++
            }
            if (!message.audioUri.isNullOrBlank()) count++
        }
        return count
    }

    /**
     * GGUF 経路のメディアトークン概算。
     * [tokensPerImage] / [tokensPerAudio] が未学習 (0 以下) のときは 0 を返す。
     */
    fun recountGgufMediaTokens(
        messages: List<MessageEntity>,
        tokensPerImage: Int,
        tokensPerAudio: Int
    ): Int {
        var images = 0
        var audio = 0
        for (message in messages) {
            if (promptBuilding.shouldExcludeFromModelContext(message)) continue
            message.imageUri?.split(',')?.forEach { raw ->
                val uri = raw.trim()
                if (uri.isEmpty()) return@forEach
                if (VideoAttachmentEncoding.isMarker(uri) ||
                    TextFileAttachmentEncoding.isMarker(uri)
                ) {
                    return@forEach
                }
                images++
            }
            if (!message.audioUri.isNullOrBlank()) audio++
        }
        if (images == 0 && audio == 0) return 0
        val perImage = tokensPerImage.takeIf { it > 0 } ?: return 0
        val perAudio = tokensPerAudio.takeIf { it > 0 } ?: perImage
        return images * perImage + audio * perAudio
    }

    /**
     * 直近ユーザー発話の添付件数から 1 枚/1 音声あたりのトークン単価を求める。
     * 学習できない場合は null。
     */
    fun learnGgufMediaUnitTokens(
        requestMedia: Int,
        messages: List<MessageEntity>
    ): Pair<Int?, Int?> {
        if (requestMedia <= 0) return null to null
        val latest = messages.lastOrNull { message ->
            message.role.equals("user", ignoreCase = true) &&
                (!message.imageUri.isNullOrBlank() || !message.audioUri.isNullOrBlank())
        } ?: return null to null
        val items = recountAttachmentCount(listOf(latest))
        if (items <= 0) return null to null
        val each = requestMedia / items
        if (each <= 0) return null to null
        val imageUnit = if (!latest.imageUri.isNullOrBlank()) each else null
        val audioUnit = if (!latest.audioUri.isNullOrBlank()) each else null
        return imageUnit to audioUnit
    }

    /** プロンプト文字列中の `<__media__>` マーカー数。 */
    fun countMediaMarkers(text: String): Int =
        MEDIA_MARKER_REGEX.findAll(text).count()

    /**
     * 送信後メーターの合計トークンを決める。
     * GGUF の prompt 分と context 合計、生成分を優先順位付きで合成する。
     */
    fun resolveTotalTokensAfterSend(
        contextTotal: Int?,
        promptTokens: Int?,
        generated: Int,
        previousUsageTokens: Int
    ): Int {
        val gen = generated.coerceAtLeast(0)
        return when {
            contextTotal != null && promptTokens != null && contextTotal > promptTokens -> contextTotal
            contextTotal != null && promptTokens == null -> contextTotal
            promptTokens != null -> promptTokens + gen
            contextTotal != null -> contextTotal
            else -> (previousUsageTokens + gen).coerceAtLeast(0)
        }
    }

    /**
     * GGUF 内訳バー用: 合計・メディア・固定分から chat 分を再配分する。
     */
    fun scaleSegmentsAfterSend(
        total: Int,
        media: Int,
        currentSystemTokens: Int,
        currentToolTokens: Int,
        currentChatTokens: Int,
        generated: Int
    ): SegmentCounts {
        val textTotal = (total - media).coerceAtLeast(0)
        val fixed = (currentSystemTokens + currentToolTokens).coerceAtLeast(0)
        val chatFromTotal = (textTotal - fixed).coerceAtLeast(0)
        val chatTokens = if (chatFromTotal > currentChatTokens) {
            chatFromTotal
        } else {
            currentChatTokens + generated.coerceAtLeast(0)
        }
        return SegmentCounts(
            systemTokens = currentSystemTokens.coerceAtLeast(0),
            toolTokens = currentToolTokens.coerceAtLeast(0),
            chatTokens = chatTokens.coerceAtLeast(0),
            mediaTokens = media.coerceAtLeast(0)
        )
    }

    data class SegmentCounts(
        val systemTokens: Int = 0,
        val toolTokens: Int = 0,
        val chatTokens: Int = 0,
        val mediaTokens: Int = 0
    ) {
        val publishedTotal: Int
            get() = (systemTokens + toolTokens + chatTokens + mediaTokens).coerceAtLeast(0)
    }

    companion object {
        private val MEDIA_MARKER_REGEX = Regex("<__media__>")
    }
}
