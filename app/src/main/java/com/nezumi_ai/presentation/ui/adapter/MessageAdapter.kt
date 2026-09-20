package com.nezumi_ai.presentation.ui.adapter

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.data.inference.stripGemmaTokens
import com.nezumi_ai.presentation.ui.theme.NezumiComposeTheme
import com.nezumi_ai.utils.PreferencesHelper

/**
 * チャットのメッセージ一覧アダプタ。
 * item_message_user.xml / item_message_ai.xml の ViewBinding を廃止し、
 * 各行のルートを ComposeView として [UserMessageItem] / [AiMessageItem] を描画する
 * (MessageItems.kt を参照)。
 *
 * 公開 API (コンストラクタ引数・setXxx メソッド群) は旧実装と同一で、
 * ChatFragment 側の呼び出しは変更不要。
 */
class MessageAdapter(
    private val onUserPromptEdit: (MessageEntity) -> Unit = {},
    private val onAiMessageLayoutChanged: () -> Unit = {},
    private val onAiMessageSpeak: (MessageEntity, String) -> Unit = { _, _ -> },
    private val onAiMessageRegenerate: (MessageEntity) -> Unit = {},
    // 応答バリアント選択のコールバック: (parentUserMessageId, newIndex) -> Unit
    private val onAiVariantSelect: (Long, Int) -> Unit = { _, _ -> },
    private val lifecycleOwner: LifecycleOwner? = null,
    private val viewModelStoreOwner: ViewModelStoreOwner? = null,
    // ドキュメント作成ツール (convert_md_to_document) の結果カードに付ける
    // 「保存」ボタンのコールバック。ChatFragment 側で SAF (CreateDocument) と
    // Markdown→docx/pdf/xlsx 変換に繋いである。onComplete は変換+保存の完了時に呼ぶ。
    private val onSaveGeneratedDocument: (markdown: String, format: String, fileName: String, onComplete: (Boolean) -> Unit) -> Unit = { _, _, _, _ -> }
) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    /**
     * 応答バリアント情報。ChatFragment から setVariantInfo() で代入される。
     *   キー: parentUserMessageId, 値: (全バリアント件数, 現在選択中の index)。
     *   ここに入っていない parent は応答バリアント = 1 とみなしてナビゲーションを非表示にする。
     */
    private var variantInfo: Map<Long, Pair<Int, Int>> = emptyMap()

    fun setVariantInfo(info: Map<Long, Pair<Int, Int>>) {
        if (variantInfo == info) return
        variantInfo = info
        // バリアントナビの見せ方は各 AI メッセージの bind で参照するので、AI 行だけ再描画を促す。
        // ListAdapter の内容を変えず notifyItemRangeChanged で payload=null のリバインドを強制。
        notifyItemRangeChanged(0, itemCount)
    }

    /**
     * パフォーマンス修正 (#scroll-jank): sanitizeVisibleText / stripGemmaTokens /
     * listFromJsonArray はいずれも文字列処理・正規表現・JSON パースを伴い、
     * ViewHolder がスクロールでリサイクルされて再 bind されるたびに毎回
     * 再計算されるとスクロールがカクつく。ストリーミング完了済み(確定)の
     * メッセージは内容が不変なので、message.id をキーに一度だけ計算して
     * キャッシュする。ストリーミング中のメッセージは content が頻繁に変わる
     * ためキャッシュしない。
     */
    private data class AiBindCache(
        val contentHash: Int,
        val visibleContent: String,
        val persistedToolCards: List<ToolResultCard>,
        val speakText: String
    )
    private val aiBindCache = HashMap<Long, AiBindCache>()

    private fun aiBindDataFor(message: MessageEntity): AiBindCache {
        if (message.isStreaming) {
            // ストリーミング中は毎回計算し、キャッシュには入れない。
            return computeAiBindData(message)
        }
        val cached = aiBindCache[message.id]
        val contentHash = message.content.hashCode()
        if (cached != null && cached.contentHash == contentHash) {
            return cached
        }
        val fresh = computeAiBindData(message)
        aiBindCache[message.id] = fresh
        return fresh
    }

    override fun onCurrentListChanged(
        previousList: MutableList<MessageEntity>,
        currentList: MutableList<MessageEntity>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        // リストから外れた (削除 / セッション切替された) メッセージの
        // キャッシュを掃除し、aiBindCache が無限に肥大化しないようにする。
        if (aiBindCache.isEmpty()) return
        val liveIds = currentList.mapTo(HashSet()) { it.id }
        aiBindCache.keys.retainAll(liveIds)
    }

    private fun computeAiBindData(message: MessageEntity): AiBindCache {
        val visibleContent = Gemma4ThinkingParser.sanitizeVisibleText(
            message.content,
            preserveToolCallTags = true
        )
        val persistedToolCards = if (!message.toolResultsJson.isNullOrBlank()) {
            ToolResultCard.listFromJsonArray(message.toolResultsJson)
        } else {
            emptyList()
        }
        val speakText = message.content.stripGemmaTokens().trim()
        return AiBindCache(
            contentHash = message.content.hashCode(),
            visibleContent = visibleContent,
            persistedToolCards = persistedToolCards,
            speakText = speakText
        )
    }

    /**
     * 生成中フラグ。true の間はユーザーメッセージの「編集ボタン」を非表示にする。
     * Bug fix: 生成中に取り消しボタンが表示されると、推論中の KV キャッシュと
     * メッセージストアの整合が崩れるため、UI 上で一切押させないようにする。
     */
    private var isGenerating: Boolean = false

    // Thinking 表示仕様：
    //   - モデルが思考を出したら常にブロックを表示する (設定に依存しない)。
    //   - 見た目 (Hide/Show reasoning ヘッダー + 左ルール) は生成中/完了後で共通。
    //   - 【思考中】：強制展開。トグルは無効 (閉じられない)。
    //   - 【思考終了〜生成完了前】：自動で閉じる。生成中はまだ開閉できない。
    //   - 【生成後】：閉じた状態から、トグルで開閉できる。
    //     開閉状態は thinkingExpandedByMessageId に保持し、重複バインドにも耐える。
    private val thinkingExpandedByMessageId = mutableSetOf<Long>()
    private var speakingMessageId: Long? = null
    private var streamingMessageId: Long? = null
    private var streamingToolCallState: ToolCallState? = null

    // Bug fix(#43): t/s ・ TTFT の表示トグルは SharedPreferences に保存されるが、
    // RecyclerView の ViewHolder はリサイクルで前回の visibility を保持する。
    // Adapter 側でフラグをキャッシュし、変更されたときに全アイテムを強制リバインドすることで
    // 「トグルが効くときと効かないときがある」バグを防ぐ。
    @Volatile private var cachedShowTps: Boolean? = null
    @Volatile private var cachedShowTtft: Boolean? = null

    /**
     * Bug fix(#43): 全般設定タブで t/s または TTFT のトグルが切り替わったときに呼ぶことで、
     * RecyclerView のリサイクルビューを含む全アイテムの visibility を確実に再評価させる。
     */
    fun refreshPerfIndicatorVisibility(showTps: Boolean, showTtft: Boolean) {
        val changed = cachedShowTps != showTps || cachedShowTtft != showTtft
        cachedShowTps = showTps
        cachedShowTtft = showTtft
        if (changed) {
            notifyDataSetChanged()
        }
    }

    /**
     * 生成状態をセットし、UI を再描画して編集ボタンの表示/非表示を切り替える。
     */
    fun setIsGenerating(generating: Boolean) {
        if (isGenerating == generating) return
        isGenerating = generating
        // ユーザーメッセージの編集ボタン表示を全体で再評価させるため、
        // リスト全体を invalidate する。件数は一般的に多くないため cost は軽い。
        notifyDataSetChanged()
    }

    fun setSpeakingMessageId(messageId: Long?) {
        val oldId = speakingMessageId
        if (oldId == messageId) return
        speakingMessageId = messageId
        notifyMessageChanged(oldId)
        notifyMessageChanged(messageId)
    }

    fun setStreamingToolCallState(messageId: Long?, state: ToolCallState?) {
        val oldId = streamingMessageId
        streamingMessageId = messageId
        streamingToolCallState = state
        if (oldId != messageId) {
            notifyMessageChanged(oldId)
        }
        notifyMessageChanged(messageId)
    }

    private fun notifyMessageChanged(messageId: Long?) {
        if (messageId == null) return
        val index = currentList.indexOfFirst { it.id == messageId }
        if (index >= 0) notifyItemChanged(index)
    }

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_AI = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val composeView = ComposeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val strategy = if (lifecycleOwner != null) {
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            } else {
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            }
            setViewCompositionStrategy(strategy)
        }
        lifecycleOwner?.let { owner ->
            composeView.setViewTreeLifecycleOwner(owner)
            (owner as? SavedStateRegistryOwner)?.let {
                composeView.setViewTreeSavedStateRegistryOwner(it)
            }
        }
        viewModelStoreOwner?.let {
            composeView.setViewTreeViewModelStoreOwner(it)
        }
        return ComposeMessageViewHolder(composeView)
    }

    /**
     * 引数の position がリスト中で最も後ろの AI メッセージかどうか判定する。
     * 再生成ボタンは「直前の AI 応答」のみに表示したいのでこのヘルパーを使う。
     */
    private fun isLastAiMessage(position: Int): Boolean {
        if (position < 0 || position >= itemCount) return false
        if (getItem(position).role == "user") return false
        for (i in position + 1 until itemCount) {
            if (getItem(i).role != "user") return false
        }
        return true
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val composeView = (holder as ComposeMessageViewHolder).composeView
        if (getItemViewType(position) == VIEW_TYPE_USER) {
            bindUserMessage(composeView, getItem(position))
        } else {
            bindAiMessage(composeView, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // ComposeView を破棄して、AudioPlayerCard の MediaPlayer など
        // DisposableEffect で管理しているリソースを確実に解放する。
        (holder as? ComposeMessageViewHolder)?.composeView?.disposeComposition()
    }

    class ComposeMessageViewHolder(val composeView: ComposeView) :
        RecyclerView.ViewHolder(composeView)

    // ─────────────────────────────────────────────────────────────
    // ユーザーメッセージ行 (旧 UserMessageViewHolder.bind)
    // ─────────────────────────────────────────────────────────────
    private fun bindUserMessage(composeView: ComposeView, message: MessageEntity) {
        composeView.setContent {
            NezumiComposeTheme {
                UserMessageItem(
                    message = message,
                    isGenerating = isGenerating,
                    onEdit = onUserPromptEdit
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AI メッセージ行 (旧 AiMessageViewHolder.bind)
    // ─────────────────────────────────────────────────────────────
    private fun bindAiMessage(composeView: ComposeView, position: Int) {
        val message = getItem(position)
        val parentId = message.parentUserMessageId
        val info = if (parentId != null) variantInfo[parentId] else null

        // インライン tool-call カード表示のため、表示用テキストのみ <tool_call> タグを
        // 保持したまま sanitize する。コピー・読み上げ用の他の stripGemmaTokens() 呼び出しは、
        // タグを含めない従来通りの挙動のまま。
        // (#scroll-jank): 確定済みメッセージは aiBindDataFor 内でキャッシュされ、
        //   同じ内容の再 bind では再計算されない。
        val bindData = aiBindDataFor(message)
        val visibleContent = bindData.visibleContent
        val persistedToolCards = bindData.persistedToolCards

        // 画像生成中のプレースホルダー (旧 aiImagePreview の generate_image 分岐)。
        val streamingToolName = if (message.isStreaming && message.id == streamingMessageId) {
            when (val s = streamingToolCallState) {
                is ToolCallState.Executing -> s.toolName
                is ToolCallState.Result -> s.toolName
                else -> null
            }
        } else null

        // t/s と TTFT は全般タブの設定で非表示にできる。既定は両方とも非表示。
        // Bug fix(#43): キャッシュ値を優先し、未初期化のときだけ SharedPreferences を直接参照する。
        val ctx = composeView.context
        val showTps = cachedShowTps
            ?: PreferencesHelper.isShowTps(ctx).also { cachedShowTps = it }
        val showTtft = cachedShowTtft
            ?: PreferencesHelper.isShowTtft(ctx).also { cachedShowTtft = it }

        val speakText = bindData.speakText
        val canSpeak = !message.isStreaming && speakText.isNotBlank() &&
            com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED

        // ドキュメント作成カードの保存ボタンは、生成終了後 (ストリーミング完了後)
        // にのみ有効にする。生成中に押されても中身が未確定のファイルをコピーしてしまうため。
        val saveHandler: ((String, String, String, (Boolean) -> Unit) -> Unit)? =
            if (message.isStreaming) null else onSaveGeneratedDocument

        composeView.setContent {
            NezumiComposeTheme {
                AiMessageItem(
                    message = message,
                    isLastAiMessage = isLastAiMessage(position),
                    isGenerating = isGenerating,
                    isSpeaking = speakingMessageId == message.id,
                    speakEnabled = canSpeak,
                    showTps = showTps,
                    showTtft = showTtft,
                    variantTotal = info?.first ?: 1,
                    variantIndex = info?.second ?: 0,
                    streamingImagePlaceholder = streamingToolName == "generate_image",
                    thinkingExpanded = thinkingExpandedByMessageId.contains(message.id),
                    visibleContent = visibleContent,
                    toolResults = persistedToolCards,
                    onSaveDocument = saveHandler,
                    onThinkingToggle = {
                        if (thinkingExpandedByMessageId.contains(message.id)) {
                            thinkingExpandedByMessageId.remove(message.id)
                        } else {
                            thinkingExpandedByMessageId.add(message.id)
                        }
                        // 自己リバインドで展開状態を反映
                        val idx = currentList.indexOfFirst { it.id == message.id }
                        if (idx >= 0) notifyItemChanged(idx)
                    },
                    onRegenerate = onAiMessageRegenerate,
                    onVariantSelect = { newIndex ->
                        if (parentId != null) onAiVariantSelect(parentId, newIndex)
                    },
                    onCopy = { msg ->
                        // Thinking (内部推論) はユーザー向けのコピー内容に含めない。
                        copyTextToClipboard(ctx, msg.content.stripGemmaTokens())
                    },
                    onSpeak = onAiMessageSpeak,
                    onBodySizeChanged = onAiMessageLayoutChanged
                )
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            // content と thinkingContent の変更のみをチェック（timestamp 変更は無視）
            // これにより、ストリーミング中の incremental update を正確に検出
            return oldItem.content == newItem.content &&
                oldItem.thinkingContent == newItem.thinkingContent &&
                oldItem.isStreaming == newItem.isStreaming &&
                oldItem.role == newItem.role &&
                oldItem.imageUri == newItem.imageUri &&
                oldItem.audioUri == newItem.audioUri &&
                oldItem.generationTps == newItem.generationTps &&
                oldItem.generationTimeMs == newItem.generationTimeMs &&
                oldItem.ttftMs == newItem.ttftMs
        }
    }
}
