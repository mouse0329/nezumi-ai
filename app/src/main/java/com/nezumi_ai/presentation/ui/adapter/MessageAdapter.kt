package com.nezumi_ai.presentation.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
import com.nezumi_ai.databinding.ItemMessageUserBinding
import com.nezumi_ai.databinding.ItemMessageAiBinding
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.stripGemmaTokens
import com.nezumi_ai.data.media.MessageMediaStore
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.presentation.ui.component.ImageViewerDialog
import com.nezumi_ai.presentation.ui.component.ToolResultCardView
import com.nezumi_ai.presentation.ui.composable.PersistedToolCallIndicators
import com.nezumi_ai.presentation.ui.composable.StreamingToolCallIndicator
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val onUserPromptRevoke: (MessageEntity) -> Unit = {},
    private val onAiMessageLayoutChanged: () -> Unit = {},
    private val onAiMessageSpeak: (MessageEntity, String) -> Unit = { _, _ -> },
    private val onAiMessageRegenerate: (MessageEntity) -> Unit = {},
    // ★ 応答バリアント選択のコールバック: (parentUserMessageId, newIndex) -> Unit
    private val onAiVariantSelect: (Long, Int) -> Unit = { _, _ -> },
    private val lifecycleOwner: LifecycleOwner? = null,
    private val viewModelStoreOwner: ViewModelStoreOwner? = null
) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    /**
     * ★ 応答バリアント情報。ChatFragment から setVariantInfo() で代入される。
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
     * 生成中フラグ。true の間は「取り消しボタン」を非表示にする。
     * Bug fix: 生成中に取り消しボタンが表示されると、推論中の KV キャッシュと
     * メッセージストアの整合が崩れるため、UI 上で一切押させないようにする。
     */
    private var isGenerating: Boolean = false

    // ★ Thinking 表示仕様 (バグ修正後の新仕様)：
    //   - モデルが思考を出したら常にブロックを表示する (設定に依存しない)。
    //   - 【生成中】：強制的に展開し、トグル行は一切表示しない (閉じるバタンを消す)。
    //   - 【生成後】：一律に自動で閉じ、トグルボタンを表示してユーザーが開閉できるようにする。
    //     開閉状態は thinkingExpandedByMessageId に保持し、重複バインドにも耐える。
    private val thinkingExpandedByMessageId = mutableSetOf<Long>()
    private var speakingMessageId: Long? = null
    private var streamingMessageId: Long? = null
    private var streamingToolCallState: ToolCallState? = null

    private enum class ContentRenderMode {
        Placeholder,
        Markdown
    }

    /**
     * 生成状態をセットし、UI を再描画して取り消しボタンの表示/非表示を切り替える。
     */
    fun setIsGenerating(generating: Boolean) {
        if (isGenerating == generating) return
        isGenerating = generating
        // ユーザーメッセージの取り消しボタン表示を全体で再評価させるため、
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
        
        fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

        private fun copyAllToClipboard(context: Context, content: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("message", content))
                Toast.makeText(
                    context,
                    context.getString(R.string.copied_to_clipboard),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun loadImageIntoView(imageView: ImageView, uri: String) {
            try {
                val loadUri = MessageMediaStore.toUri(uri)
                if (loadUri?.scheme == "file") {
                    val path = loadUri.path
                    if (path != null && java.io.File(path).exists()) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                        } else {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    } else {
                        imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                } else if (loadUri != null) {
                    imageView.setImageURI(loadUri)
                } else {
                    imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } catch (e: Exception) {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        // Phase 11: 複数画像プレビュー用ヘルパー関数（送信前と統一）
        // Phase 14: file:// URI に対応して画像読み込み
        fun setupMultipleImagePreview(imageUris: List<String>, container: LinearLayout, context: Context) {
            container.removeAllViews()
            for (uri in imageUris) {
                // CardView を使用して角丸・ボーダー実現
                val cardView = androidx.cardview.widget.CardView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                        setMargins(8, 8, 8, 8)  // 16dp spacing (両側8dp)
                    }
                    radius = 12f  // 角丸
                    cardElevation = 4f  // 影
                    setCardBackgroundColor(android.graphics.Color.WHITE)
                }
                
                val imageView = ImageView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(250, 250)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.message_image)
                }
                
                loadImageIntoView(imageView, uri)
                
                // タップしてモーダルで大きく表示
                imageView.setOnClickListener {
                    ImageViewerDialog.show(context, uri)
                }
                
                cardView.addView(imageView)
                container.addView(cardView)
            }
        }
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_AI
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ItemMessageUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            UserMessageViewHolder(binding, onUserPromptRevoke) { isGenerating }
        } else {
            val binding = ItemMessageAiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            AiMessageViewHolder(binding, onAiMessageLayoutChanged, lifecycleOwner, viewModelStoreOwner)
        }
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
        when (holder) {
            is UserMessageViewHolder -> holder.bind(getItem(position))
            is AiMessageViewHolder -> {
                val msg = getItem(position)
                val parentId = msg.parentUserMessageId
                val info = if (parentId != null) variantInfo[parentId] else null
                holder.bind(
                    msg,
                    isLastAiMessage(position),
                    isGenerating,
                    onAiMessageRegenerate,
                    variantTotal = info?.first ?: 1,
                    variantIndex = info?.second ?: 0,
                    onVariantSelect = { newIndex ->
                        if (parentId != null) onAiVariantSelect(parentId, newIndex)
                    }
                )
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // ストリーミング中のアイテムだけキャッシュをクリアする。
        // 静的なアイテムはキャッシュを保持してスクロール復帰時の再レンダリングを防ぐ。
        if (holder is AiMessageViewHolder) {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_ID.toInt()) {
                val item = runCatching { getItem(pos) }.getOrNull()
                if (item?.isStreaming == true) holder.clearCache()
            }
        }
    }

    
    class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onUserPromptRevoke: (MessageEntity) -> Unit,
        private val isGeneratingProvider: () -> Boolean = { false }
    ) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        
        fun bind(message: MessageEntity) {
            if (BuildConfig.DEBUG) {
                Log.d("MessageAdapter", "BIND_USER_MESSAGE: id=${message.id} content='${message.content}'")
            }
            binding.apply {
                userMessageText.text = message.content
                userMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                
                // Media handling
                if (!message.imageUri.isNullOrEmpty() || !message.audioUri.isNullOrEmpty()) {
                    mediaContainer.visibility = View.VISIBLE
                    
                    if (!message.imageUri.isNullOrEmpty()) {
                        // Phase 11: 複数画像対応
                        val imageUris = message.imageUri!!.split(",").filter { it.isNotBlank() }
                        if (imageUris.size > 1) {
                            // 複数画像：HorizontalScrollView で表示
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            userImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(imageUris, imageContainer, binding.root.context)
                        } else {
                            // 単一画像：従来通り表示
                            imageScrollView.visibility = View.GONE
                            singleImageContainer.visibility = View.VISIBLE
                            userImagePreview.visibility = View.VISIBLE
                            audioPlaybackContainer.visibility = View.GONE
                            try {
                                loadImageIntoView(userImagePreview, message.imageUri!!)
                            } catch (e: Exception) {
                                userImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                            userImagePreview.setOnClickListener {
                                ImageViewerDialog.show(binding.root.context, message.imageUri!!)
                            }
                        }
                    }
                    
                    if (!message.audioUri.isNullOrEmpty()) {
                        // Show audio player
                        imageScrollView.visibility = View.GONE
                        singleImageContainer.visibility = View.GONE
                        userImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(message.audioUri, userAudioPlayButton, userAudioDuration)
                    }
                } else {
                    mediaContainer.visibility = View.GONE
                    userImagePreview.visibility = View.GONE
                }
                
                copyMessageButton.setOnClickListener {
                    copyAllToClipboard(binding.root.context, message.content)
                }
                // Bug fix: 生成中に取り消しボタンが見えてしまう不具合への対処。
                // bind のたびにジェネレート状態を参照して可視性を制御する。
                val generating = isGeneratingProvider()
                revokePromptButton.visibility = if (generating) View.GONE else View.VISIBLE
                revokePromptButton.isEnabled = !generating
                revokePromptButton.setOnClickListener {
                    if (isGeneratingProvider()) return@setOnClickListener
                    onUserPromptRevoke(message)
                }
            }
        }
        
        private fun setupAudioPlayback(audioUri: String, playButton: View, durationText: View) {
            try {
                val audioUriObj = MessageMediaStore.toUri(audioUri) ?: return
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(
                        binding.root.context,
                        audioUriObj
                    )
                    setOnPreparedListener { mp ->
                        val duration = mp.duration / 1000
                        val minutes = duration / 60
                        val seconds = duration % 60
                        (durationText as? android.widget.TextView)?.text = String.format("%d:%02d", minutes, seconds)
                    }
                    prepareAsync()
                }
                
                playButton.setOnClickListener {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        (it as? android.widget.Button)?.text = "▶"
                    } else {
                        mediaPlayer?.start()
                        (it as? android.widget.Button)?.text = "⏸"
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(binding.root.context, "音声の再生に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    inner class AiMessageViewHolder(
        private val binding: ItemMessageAiBinding,
        private val onAiMessageLayoutChanged: () -> Unit,
        private val lifecycleOwner: LifecycleOwner? = null,
        private val viewModelStoreOwner: ViewModelStoreOwner? = null
    ) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        // 前回レンダリングしたcontentをキャッシュ。同じ内容なら再レンダリングしない。
        private var lastRenderedContent: String? = null
        private var lastRenderedThinking: String? = null
        private var lastRenderedContentMode: ContentRenderMode? = null

        // ★ 応答バリアントスワイプ検出用。bind() で差し替える。
        private var swipeVariantHandler: ((direction: Int) -> Unit)? = null

        fun clearCache() {
            lastRenderedContent = null
            lastRenderedThinking = null
            lastRenderedContentMode = null
        }

        init {
            lifecycleOwner?.let { owner ->
                binding.root.setViewTreeLifecycleOwner(owner)
                (owner as? SavedStateRegistryOwner)?.let {
                    binding.root.setViewTreeSavedStateRegistryOwner(it)
                }
            }
            (viewModelStoreOwner as? androidx.lifecycle.ViewModelStoreOwner)?.let {
                binding.root.setViewTreeViewModelStoreOwner(it)
            }
            binding.aiMessageText.movementMethod = LinkMovementMethod.getInstance()
            val lifecycleStrategy = if (lifecycleOwner != null)
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            else
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            binding.aiMessageMarkdownCompose.setViewCompositionStrategy(lifecycleStrategy)
            binding.aiThinkingMarkdownCompose.setViewCompositionStrategy(lifecycleStrategy)
            binding.aiStreamingToolCallCompose.setViewCompositionStrategy(lifecycleStrategy)
            binding.aiMessageMarkdownCompose.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    onAiMessageLayoutChanged()
                }
            }
            // ★ Scroll fix: aiThinkingMarkdownCompose の layoutChangeListener は
            //   Thinking ブロックの展開/折りたたみやトークン追加のたびに冗長発火し、
            //   自動追従スクロールを不安定化させる原因になっていたため削除。
            //   本文 (aiMessageMarkdownCompose / aiMessageText) の高さ変化だけで
            //   下端追従判定は十分機能する。
            binding.aiMessageText.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    onAiMessageLayoutChanged()
                }
            }

            // ★ 応答バリアントの横スワイプ検出。AI メッセージカード全体に仕掛ける。
            //   RecyclerView の縦スクロールと競合しないよう、横方向に十分動いたときだけ requestDisallowInterceptTouchEvent する。
            val touchSlop = android.view.ViewConfiguration.get(binding.root.context).scaledTouchSlop
            val minSwipeDistancePx = touchSlop * 4  // 作図をはっきりさせる
            var downX = 0f
            var downY = 0f
            var tracking = false
            binding.aiMessageRoot.setOnTouchListener { v, ev ->
                val handler = swipeVariantHandler ?: return@setOnTouchListener false
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        tracking = true
                        false  // 他のタップターゲット (ボタンなど) の子ビューにも伝える
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!tracking) return@setOnTouchListener false
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                            // 十分に横に動いたので、親に縦スクロールを遠慮してもらう
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val consumed = if (tracking) {
                            val dx = ev.x - downX
                            val dy = ev.y - downY
                            if (kotlin.math.abs(dx) >= minSwipeDistancePx &&
                                kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                            ) {
                                // 右スワイプ = 前の応答へ (direction = -1)
                                // 左スワイプ = 次の応答へ (direction = +1)
                                handler(if (dx > 0) -1 else +1)
                                true
                            } else false
                        } else false
                        tracking = false
                        consumed
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        tracking = false
                        false
                    }
                    else -> false
                }
            }
        }

        fun bind(
            message: MessageEntity,
            isLastAiMessage: Boolean = false,
            isGenerating: Boolean = false,
            onRegenerate: (MessageEntity) -> Unit = {},
            variantTotal: Int = 1,
            variantIndex: Int = 0,
            onVariantSelect: (Int) -> Unit = {}
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "MessageAdapter",
                    "BIND_AI_MESSAGE: id=${message.id} content='${message.content.take(50)}'..."
                )
            }
            // ★ 再生成ボタン：末尾の AI メッセージかつ非ストリーミング、非生成中にのみ表示する。
            val canRegenerate = isLastAiMessage && !isGenerating && !message.isStreaming
            binding.regenerateMessageButton.visibility =
                if (canRegenerate) View.VISIBLE else View.GONE
            binding.regenerateMessageButton.isEnabled = canRegenerate
            binding.regenerateMessageButton.setOnClickListener {
                if (canRegenerate) onRegenerate(message)
            }

            // ★ 応答バリアントナビゲーション (◀ n/m ▶)
            //   - 応答が 2 件以上ある場合のみ表示
            //   - ストリーミング中 / 生成中は非表示 (切り替えでレースを避ける)
            val showVariantNav = variantTotal > 1 && !message.isStreaming && !isGenerating
            binding.variantNavContainer.visibility =
                if (showVariantNav) View.VISIBLE else View.GONE
            if (showVariantNav) {
                binding.variantPageInfo.text = "${variantIndex + 1}/$variantTotal"
                val canPrev = variantIndex > 0
                val canNext = variantIndex < variantTotal - 1
                binding.variantPrevButton.isEnabled = canPrev
                binding.variantPrevButton.alpha = if (canPrev) 1.0f else 0.3f
                binding.variantNextButton.isEnabled = canNext
                binding.variantNextButton.alpha = if (canNext) 1.0f else 0.3f
                binding.variantPrevButton.setOnClickListener {
                    if (canPrev) onVariantSelect(variantIndex - 1)
                }
                binding.variantNextButton.setOnClickListener {
                    if (canNext) onVariantSelect(variantIndex + 1)
                }
            } else {
                binding.variantPrevButton.setOnClickListener(null)
                binding.variantNextButton.setOnClickListener(null)
            }

            // ★ スワイプ検出のハンドラをこの bind() のバリアント情報で差し替える。
            //   スワイプ可能なのは "応答が 2 件以上 かつ 非ストリーミング中" だけに限定。
            swipeVariantHandler = if (variantTotal > 1 && !message.isStreaming && !isGenerating) {
                { direction ->
                    val next = (variantIndex + direction).coerceIn(0, variantTotal - 1)
                    if (next != variantIndex) onVariantSelect(next)
                }
            } else null
            binding.apply {
                // ★ v5.1 Thinking 表示仕様：
                //   - 設定スイッチの ON/OFF に一切依存せず、thinkingContent が非空であれば表示。
                //     (OFF のときに誤って生成されたものもバックグラウンド実行のるつぼとして「隠さず」出す)
                //   - 生成中は常に展開、閉じトグルは表示しない。
                //   - 生成完了後は閉じトグルを表示し、ユーザーが閉じた ID を記録して保持。
                val thinking = message.thinkingContent?.stripGemmaTokens()
                val hasThinking = !thinking.isNullOrBlank()
                if (hasThinking) {
                    aiThinkingBlock.visibility = View.VISIBLE
                    if (thinking != lastRenderedThinking) {
                        renderThinkingMarkdown(thinking)
                        lastRenderedThinking = thinking
                    }

                    // ★ バグ修正：生成中 = 強制展開、トグル行非表示 (閉じるボタンを消す)。
                    //   生成完了後 = トグル行を表示し、自動で閉じる (ユーザーが手動で開ける)。
                    val streaming = message.isStreaming
                    if (streaming) {
                        // 生成中: トグル行を一切表示せず、強制展開。
                        aiThinkingToggleRow.visibility = View.GONE
                        aiThinkingToggleRow.setOnClickListener(null)
                        aiThinkingToggleRow.isClickable = false
                        aiThinkingBody.visibility = View.VISIBLE
                        aiThinkingMarkdownCompose.visibility = View.VISIBLE
                        // 生成中は展開フラグを集合から削除しておき、完了後の初回バインドでは閉じた状態になるようにする。
                        thinkingExpandedByMessageId.remove(message.id)
                    } else {
                        // 生成完了後: トグル行を表示し、初期は閉じた状態。
                        val expanded = thinkingExpandedByMessageId.contains(message.id)
                        aiThinkingToggleRow.visibility = View.VISIBLE
                        aiThinkingToggleRow.isClickable = true
                        aiThinkingBody.visibility = if (expanded) View.VISIBLE else View.GONE
                        aiThinkingMarkdownCompose.visibility = if (expanded) View.VISIBLE else View.GONE
                        val toggleLabel = binding.aiThinkingToggleLabel
                        val chevron = binding.aiThinkingChevron
                        toggleLabel.text = binding.root.context.getString(
                            if (expanded) R.string.gemma_hide_thinking else R.string.gemma_show_thinking
                        )
                        chevron.text = if (expanded) "▲" else "▼"
                        aiThinkingToggleRow.setOnClickListener {
                            val nowExpanded = thinkingExpandedByMessageId.contains(message.id)
                            if (nowExpanded) {
                                thinkingExpandedByMessageId.remove(message.id)
                            } else {
                                thinkingExpandedByMessageId.add(message.id)
                            }
                            // 自己リバインドで展開状態を反映
                            val position = bindingAdapterPosition
                            if (position != RecyclerView.NO_POSITION) {
                                notifyItemChanged(position)
                            }
                        }
                    }
                } else {
                    aiThinkingBlock.visibility = View.GONE
                    aiThinkingBody.visibility = View.GONE
                    aiThinkingMarkdownCompose.visibility = View.GONE
                    lastRenderedThinking = null
                    thinkingExpandedByMessageId.remove(message.id)
                }

                val visibleContent = message.content.stripGemmaTokens()
                val visibleThinking = thinking

                val persistedToolCards = if (!message.isStreaming && !message.toolResultsJson.isNullOrBlank()) {
                    ToolResultCard.listFromJsonArray(message.toolResultsJson)
                } else {
                    emptyList()
                }
                val showStreamingToolCall = message.isStreaming &&
                    message.id == streamingMessageId &&
                    streamingToolCallState != null &&
                    streamingToolCallState !is ToolCallState.Done
                when {
                    showStreamingToolCall -> {
                        aiStreamingToolCallCompose.visibility = View.VISIBLE
                        val toolState = streamingToolCallState!!
                        aiStreamingToolCallCompose.setContent {
                            NezumiComposeTheme {
                                StreamingToolCallIndicator(state = toolState)
                            }
                        }

                        // 画像生成中の場合は、画像コンテナを表示して領域を確保
                        val toolName = when (toolState) {
                            is ToolCallState.Executing -> toolState.toolName
                            is ToolCallState.Result -> toolState.toolName
                            else -> null
                        }
                        if (toolName == "generate_image") {
                            mediaContainer.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.VISIBLE
                            aiImagePreview.visibility = View.VISIBLE
                            aiImagePreview.setImageResource(R.drawable.ic_image)
                            aiImagePreview.alpha = 0.3f
                        }
                    }
                    persistedToolCards.isNotEmpty() -> {
                        aiStreamingToolCallCompose.visibility = View.VISIBLE
                        aiStreamingToolCallCompose.setContent {
                            NezumiComposeTheme {
                                PersistedToolCallIndicators(cards = persistedToolCards)
                            }
                        }
                        aiImagePreview.alpha = 1.0f
                        aiImagePreview.setOnClickListener(null)
                    }
                    else -> {
                        aiStreamingToolCallCompose.visibility = View.GONE
                        aiImagePreview.alpha = 1.0f
                        aiImagePreview.setOnClickListener(null)
                    }
                }

                when {
                    message.isStreaming && visibleContent.isBlank() -> {
                        renderPlaceholder(visibleThinking)
                    }
                    else -> {
                        renderMarkdown(visibleContent)
                    }
                }

                aiMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                
                // Media handling
                if (!message.imageUri.isNullOrEmpty() || !message.audioUri.isNullOrEmpty()) {
                    mediaContainer.visibility = View.VISIBLE
                    
                    if (!message.imageUri.isNullOrEmpty()) {
                        // Phase 11: 複数画像対応
                        val imageUris = message.imageUri!!.split(",").filter { it.isNotBlank() }
                        if (imageUris.size > 1) {
                            // 複数画像：HorizontalScrollView で表示
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            aiImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(imageUris, imageContainer, binding.root.context)
                        } else {
                            // 単一画像：従来通り表示
                            imageScrollView.visibility = View.GONE
                            singleImageContainer.visibility = View.VISIBLE
                            aiImagePreview.visibility = View.VISIBLE
                            audioPlaybackContainer.visibility = View.GONE
                            try {
                                loadImageIntoView(aiImagePreview, message.imageUri!!)
                            } catch (e: Exception) {
                                aiImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                            aiImagePreview.setOnClickListener {
                                ImageViewerDialog.show(binding.root.context, message.imageUri!!)
                            }
                        }
                    }
                    
                    if (!message.audioUri.isNullOrEmpty()) {
                        // Show audio player
                        imageScrollView.visibility = View.GONE
                        singleImageContainer.visibility = View.GONE
                        aiImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(message.audioUri, aiAudioPlayButton, aiAudioDuration)
                    }
                } else {
                    mediaContainer.visibility = View.GONE
                    aiImagePreview.visibility = View.GONE
                }
                
                toolResultsContainer.removeAllViews()
                if (!message.isStreaming && persistedToolCards.isNotEmpty()) {
                    toolResultsContainer.visibility = View.VISIBLE
                    for (card in persistedToolCards) {
                        val cardView = ToolResultCardView(binding.root.context)
                        cardView.bind(card)
                        cardView.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = 8
                        }
                        toolResultsContainer.addView(cardView)
                    }
                } else {
                    toolResultsContainer.visibility = View.GONE
                }

                copyMessageButton.setOnClickListener {
                    // ★ Thinking (内部推論) はユーザー向けのコピー内容に含めない。
                    //   回答本文だけをクリップボードに転送する。
                    copyAllToClipboard(binding.root.context, message.content.stripGemmaTokens())
                }

                val speakText = message.content.stripGemmaTokens().trim()
                val canSpeak = !message.isStreaming && speakText.isNotBlank()
                    && com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED
                val isSpeakingThisMessage = speakingMessageId == message.id
                speakMessageButton.visibility =
                    if (canSpeak && !isSpeakingThisMessage) View.VISIBLE else View.GONE
                speakMessageButton.isEnabled = speakingMessageId == null
                speakMessageProgress.visibility =
                    if (canSpeak && isSpeakingThisMessage) View.VISIBLE else View.GONE
                speakMessageButton.setOnClickListener {
                    onAiMessageSpeak(message, speakText)
                }

                // ★ t/s と TTFT は全般タブの設定で非表示にできる。既定は両方とも非表示。
                val ctx = binding.root.context
                val showTps = com.nezumi_ai.utils.PreferencesHelper.isShowTps(ctx)
                val showTtft = com.nezumi_ai.utils.PreferencesHelper.isShowTtft(ctx)
                val tps = message.generationTps
                val generationTimeMs = message.generationTimeMs
                val ttftMs = message.ttftMs
                val parts = mutableListOf<String>()
                if (!message.isStreaming) {
                    if (showTps) {
                        tps?.takeIf { it > 0f }?.let { parts += String.format("%.1f t/s", it) }
                        generationTimeMs?.takeIf { it > 0L }?.let { parts += formatGenerationTime(it) }
                    }
                    if (showTtft) {
                        ttftMs?.takeIf { it > 0L }?.let { parts += formatTtft(it) }
                    }
                }
                if (parts.isNotEmpty()) {
                    tvTps.visibility = View.VISIBLE
                    tvTps.text = parts.joinToString("  ·  ")
                } else {
                    tvTps.visibility = View.GONE
                }
            }
        }

        private fun renderPlaceholder(thinking: String?) {
            val text = binding.root.context.getString(
                if (thinking.isNullOrBlank()) {
                    R.string.response_generating
                } else {
                    R.string.gemma_answer_generating_hint
                }
            )
            if (
                lastRenderedContent == text &&
                lastRenderedContentMode == ContentRenderMode.Placeholder
            ) return

            binding.aiMessageText.text = text
            binding.aiMessageText.visibility = View.VISIBLE
            binding.aiMessageMarkdownCompose.visibility = View.GONE
            lastRenderedContent = text
            lastRenderedContentMode = ContentRenderMode.Placeholder
        }

        private fun formatGenerationTime(ms: Long): String {
            return if (ms < 60_000L) {
                String.format("生成 %.1fs", ms / 1000f)
            } else {
                val totalSeconds = ms / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                String.format("生成 %d:%02d", minutes, seconds)
            }
        }

        /** ★ 新: TTFT (Time To First Token) の表示フォーマット。 */
        private fun formatTtft(ms: Long): String {
            return if (ms < 1000L) {
                String.format("TTFT %dms", ms)
            } else {
                String.format("TTFT %.2fs", ms / 1000f)
            }
        }

        private fun renderMarkdown(content: String) {
            if (
                lastRenderedContent == content &&
                lastRenderedContentMode == ContentRenderMode.Markdown
            ) return

            binding.aiMessageText.visibility = View.GONE
            binding.aiMessageMarkdownCompose.visibility = View.VISIBLE

            binding.aiMessageMarkdownCompose.setContent {
                GalleryMarkdownText(content = content)
            }
            lastRenderedContent = content
            lastRenderedContentMode = ContentRenderMode.Markdown
        }

        private fun renderThinkingMarkdown(content: String) {
            // ★ Scroll fix:
            //   - early-return 判定は lastRenderedThinking の同値だけで十分。
            //     lastRenderedContentMode は「本文」用のフラグなのでここで参照/更新しない。
            //   - aiThinkingMarkdownCompose の visibility は呼び出し元の展開/折りたたみロジックが管理する。
            //   - onAiMessageLayoutChanged() は呼ばない。Thinking ブロックのレイアウト変化で
            //     毎トークン自動スクロールが再スケジュールされ、追従が乱れるため。
            //
            // ★ バグ修正 (一箱所に重なる/表示されない):
            //   旧実装は early-return しても lastRenderedThinking を呼び出し元で更新する側だったが、
                //   同一ビューホルダーの再利用時に Compose のスナップショットがリセットされず、
                //   setContent が呼ばれないケースがあった。確実に新しい content で setContent するよう、
                //   キャッシュを renderThinkingMarkdown 内部でも保持する。
            if (lastRenderedThinking == content) return

            // ★ Bug fix(#Thinking-Layout):
            //   旧実装は本文用の GalleryMarkdownText (widthIn max=280dp + 内部 padding 11dp) を
            //   Thinking ブロックでも使っていたため、親の ai_thinking_body の枠
            //   (縦線とパディングを除いた実質 248dp 前後) を超えて Compose ビューが膨張し、
            //   Thinking ブロック全体が画面外にはみ出していた。
            //   Thinking 専用の描画関数 ThinkingMarkdownText を使い、背景・ボーダーを自己主張せず
            //   fillMaxWidth() で親のスペースに収める。
            binding.aiThinkingMarkdownCompose.setContent {
                ThinkingMarkdownText(content = content)
            }
            lastRenderedThinking = content
        }

        /**
         * ★ Thinking ブロック専用の Markdown 描画。
         * 親の ai_thinking_body 内部で使われるため、自己のカード背景や固定幅は持たない。
         * 幅は常に fillMaxWidth() で親コンテナに合わせ、枠外はみ出しを防ぐ。
         */
        @Composable
        private fun ThinkingMarkdownText(content: String) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                SelectionContainer {
                    ProvideTextStyle(
                        value = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = colorResource(id = R.color.text_secondary),
                            letterSpacing = 0.2.sp
                        )
                    ) {
                        MaterialTheme {
                            MarkdownLatexText(text = content, textSize = 36f)
                        }
                    }
                }
            }
        }

        @Composable
        private fun GalleryMarkdownText(content: String) {
            val shape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(colorResource(id = R.color.surface_card), shape)
                    .border(
                        BorderStroke(1.dp, colorResource(id = R.color.border)),
                        shape
                    )
                    .padding(11.dp)
            ) {
                SelectionContainer {
                    ProvideTextStyle(
                        value = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = colorResource(id = R.color.text_primary),
                            letterSpacing = 0.2.sp
                        )
                    ) {
                        MaterialTheme {
                            MarkdownLatexText(text = content, textSize = 40f)
                        }
                    }
                }
            }
        }
        
        private fun setupAudioPlayback(audioUri: String, playButton: View, durationText: View) {
            try {
                val audioUriObj = MessageMediaStore.toUri(audioUri) ?: return
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(
                        binding.root.context,
                        audioUriObj
                    )
                    setOnPreparedListener { mp ->
                        val duration = mp.duration / 1000
                        val minutes = duration / 60
                        val seconds = duration % 60
                        (durationText as? android.widget.TextView)?.text = String.format("%d:%02d", minutes, seconds)
                    }
                    prepareAsync()
                }
                
                playButton.setOnClickListener {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        (it as? android.widget.Button)?.text = "▶"
                    } else {
                        mediaPlayer?.start()
                        (it as? android.widget.Button)?.text = "⏸"
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(binding.root.context, "音声の再生に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    @Composable
    private fun NezumiComposeTheme(content: @Composable () -> Unit) {
        ProvideTextStyle(
            value = TextStyle(
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = colorResource(id = R.color.text_primary),
                letterSpacing = 0.2.sp
            )
        ) {
            MaterialTheme {
                content()
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

