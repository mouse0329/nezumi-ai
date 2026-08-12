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
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.stripGemmaTokens
import com.nezumi_ai.data.inference.stripTxtFileBlocks
import com.nezumi_ai.data.inference.stripVideoBlocks
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.presentation.ui.component.TextFileViewerDialog
import com.nezumi_ai.data.media.MessageMediaStore
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.presentation.ui.component.ImageViewerDialog
import com.nezumi_ai.presentation.ui.component.MediaViewerDialog
import com.nezumi_ai.data.media.VideoAttachmentEncoding
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
import com.nezumi_ai.presentation.ui.composable.InlineToolCallMessageBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val onUserPromptEdit: (MessageEntity) -> Unit = {},
    private val onAiMessageLayoutChanged: () -> Unit = {},
    private val onAiMessageSpeak: (MessageEntity, String) -> Unit = { _, _ -> },
    private val onAiMessageRegenerate: (MessageEntity) -> Unit = {},
 // 応答バリアント選択のコールバック: (parentUserMessageId, newIndex) -> Unit
    private val onAiVariantSelect: (Long, Int) -> Unit = { _, _ -> },
    private val lifecycleOwner: LifecycleOwner? = null,
    private val viewModelStoreOwner: ViewModelStoreOwner? = null,
    // ドキュメント生成ツール (convert_md_to_document) の結果カードに付ける
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
     * 生成中フラグ。true の間は「取り消しボタン」を非表示にする。
     * Bug fix: 生成中に取り消しボタンが表示されると、推論中の KV キャッシュと
     * メッセージストアの整合が崩れるため、UI 上で一切押させないようにする。
     */
    private var isGenerating: Boolean = false

 // Thinking 表示仕様 (バグ修正後の新仕様)：
    //   - モデルが思考を出したら常にブロックを表示する (設定に依存しない)。
    //   - 【生成中】：強制的に展開し、トグル行は一切表示しない (閉じるバタンを消す)。
    //   - 【生成後】：一律に自動で閉じ、トグルボタンを表示してユーザーが開閉できるようにする。
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

        // 複数画像プレビュー (送信後表示)。タップで統一 MediaViewerDialog を開く。
        //   - imageUris: フレーム/画像 URI 列 (先頭の動画マーカーは剥がし済み)
        //   - videoUri, audioUri: 存在すればビュワーで同時に展開される
        //
        // 動画由来 (videoUri != null) のときは、フレーム列をカードとして1枚ずつ並べない。
        // 内部的には動画は「フレーム列 + 音声」に分解して保持しているが、ユーザーには
        // 常に「動画1本」として見せたいため、ここでは動画サムネ1枚だけを描画する。
        // 音声もこの動画カードに吸収し、独立した音声カードは出さない
        // (音声だけを別カードにすると「もう一つの動画のようなもの」に見えてしまう)。
        fun setupMultipleImagePreview(
            imageUris: List<String>,
            container: LinearLayout,
            context: Context,
            videoUri: String? = null,
            audioUri: String? = null,
            textFiles: List<TextFileAttachmentEncoding.TextFileEntry> = emptyList()
        ) {
            container.removeAllViews()

            if (!videoUri.isNullOrBlank()) {
                // --- 動画由来: 1枚の動画カードのみ表示。フレーム列・音声は個別カード化しない ---
                val cardView = androidx.cardview.widget.CardView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                        setMargins(8, 8, 8, 8)
                    }
                    radius = 12f
                    cardElevation = 4f
                    setCardBackgroundColor(android.graphics.Color.BLACK)
                }
                val frameLayout = android.widget.FrameLayout(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val imageView = ImageView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.message_image)
                }
                // 先頭フレームを動画の代表サムネとして流用
                imageUris.firstOrNull()?.let { loadImageIntoView(imageView, it) }
                frameLayout.addView(imageView)

                val playIcon = android.widget.TextView(context).apply {
                    text = "▶"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 28f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.argb(90, 0, 0, 0))
                }
                frameLayout.addView(playIcon)
                cardView.addView(frameLayout)

                cardView.setOnClickListener {
                    MediaViewerDialog.show(
                        context,
                        MediaViewerDialog.MediaBundle(
                            imageUris = imageUris,
                            videoUri = videoUri,
                            audioUri = audioUri,
                            initialIndex = 0,
                            title = context.getString(com.nezumi_ai.R.string.viewer_bundle_title_video_audio)
                        )
                    )
                }
                container.addView(cardView)
                // 動画カードの後ろにテキスト添付カードを並べる
                appendTextFileCards(container, context, textFiles)
                return
            }

            // --- 動画由来ではない、通常の複数画像 ---
            // まず画像カードを並べる。
            for ((idx, uri) in imageUris.withIndex()) {
                val cardView = androidx.cardview.widget.CardView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                        setMargins(8, 8, 8, 8)
                    }
                    radius = 12f
                    cardElevation = 4f
                    setCardBackgroundColor(android.graphics.Color.WHITE)
                }

                val imageView = ImageView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(250, 250)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.message_image)
                }

                loadImageIntoView(imageView, uri)
                imageView.setOnClickListener {
                    MediaViewerDialog.show(
                        context,
                        MediaViewerDialog.MediaBundle(
                            imageUris = imageUris,
                            videoUri = videoUri,
                            audioUri = audioUri,
                            initialIndex = idx,
                            title = context.getString(com.nezumi_ai.R.string.viewer_media_preview_title)
                        )
                    )
                }

                cardView.addView(imageView)
                container.addView(cardView)
            }

            // 画像の後ろに「音声カード」を並べる。
            // これを入れないと、「画像 + 音声」を同時送信した場合に送信後の一覧から
            // 音声が完全に消えてしまう。(モデル側には audioUri は正しく渡っているが
            //  UI 側の描画が拜けているのをここで補う)
            if (!audioUri.isNullOrBlank()) {
                val audioCard = androidx.cardview.widget.CardView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                        setMargins(8, 8, 8, 8)
                    }
                    radius = 12f
                    cardElevation = 4f
                    setCardBackgroundColor(android.graphics.Color.parseColor("#1F2A44"))
                }
                val audioFrame = android.widget.FrameLayout(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val playIcon = android.widget.TextView(context).apply {
 text = "▶"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 30f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val label = android.widget.TextView(context).apply {
                    text = "音声"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, 12)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                    )
                }
                audioFrame.addView(playIcon)
                audioFrame.addView(label)
                audioCard.addView(audioFrame)
                audioCard.setOnClickListener {
                    MediaViewerDialog.show(
                        context,
                        MediaViewerDialog.MediaBundle(
                            imageUris = imageUris,
                            videoUri = videoUri,
                            audioUri = audioUri,
                            initialIndex = 0,
                            title = context.getString(com.nezumi_ai.R.string.viewer_bundle_title_image_audio)
                        )
                    )
                }
                container.addView(audioCard)
            }

            // 画像・音声の後ろにテキスト添付カードを並べる。
            //   これがないと「テキストファイルだけ添付」したメッセージが送信後に
            //   何も添付されていないように見えてしまう。
            appendTextFileCards(container, context, textFiles)
        }

        /**
         * テキスト添付 (nezumi://txtfile) をファイル名カードとして並べる。
         * タップすると TextFileViewerDialog で中身を開く。
         */
        private fun appendTextFileCards(
            container: LinearLayout,
            context: Context,
            textFiles: List<TextFileAttachmentEncoding.TextFileEntry>
        ) {
            for (entry in textFiles) {
                val card = androidx.cardview.widget.CardView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                        setMargins(8, 8, 8, 8)
                    }
                    radius = 12f
                    cardElevation = 4f
                    setCardBackgroundColor(android.graphics.Color.parseColor("#274427"))
                }
                val frame = android.widget.FrameLayout(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                // ドキュメント添付 (Word/PDF/Excel/PowerPoint) は拡張子バッジで区別する。
                //   プレーンテキスト添付の "T" とは別表示にする。
                //   ピック時に Markdown 変換済みのもの (isConvertedDocument) は中身が
                //   読める .md なので "MD" バッジにする (タップでビュワーが開く)。
                val isDocument = TextFileAttachmentEncoding.isDocumentFile(entry.name) &&
                    !entry.isConvertedDocument
                val icon = android.widget.TextView(context).apply {
                    text = when {
                        isDocument -> entry.name.substringAfterLast('.', "").uppercase().take(4)
                            .ifBlank { "DOC" }
                        entry.isConvertedDocument -> "MD"
                        else -> "T"
                    }
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = if (isDocument) 20f else 28f
                    gravity = android.view.Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val name = android.widget.TextView(context).apply {
                    text = entry.name
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(8, 0, 8, 12)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                    )
                }
                frame.addView(icon)
                frame.addView(name)
                card.addView(frame)
                card.setOnClickListener {
                    TextFileViewerDialog.show(context, entry)
                }
                container.addView(card)
            }
        }
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_AI
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ItemMessageUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            UserMessageViewHolder(binding, onUserPromptEdit) { isGenerating }
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
        private val onUserPromptEdit: (MessageEntity) -> Unit,
        private val isGeneratingProvider: () -> Boolean = { false }
    ) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        
        fun bind(message: MessageEntity) {
            if (BuildConfig.DEBUG) {
                Log.d("MessageAdapter", "BIND_USER_MESSAGE: id=${message.id} content='${message.content}'")
            }
            binding.apply {
                // <txtfile> / <video> ブロックはモデル向けの埋め込みなので吹き出しには出さない
                userMessageText.text = message.content.stripTxtFileBlocks().stripVideoBlocks()
                userMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                
                // Media handling (統一ビュワー対応)
                //   1) imageUri 先頭の nezumi://videoframes マーカーを剥がして
                //      元動画 URI + 音声 URI + フレーム列 に展開する
                //   2) 画像/動画/音声 のどれか一つでもあれば mediaContainer を表示
                val (videoMeta, imageUrisRaw) = VideoAttachmentEncoding.split(message.imageUri)
                val textFiles = TextFileAttachmentEncoding.extract(message.imageUri)
                // テキスト添付マーカーは画像サムネイルとしては描画しない
                val imageUris = imageUrisRaw.filter { !TextFileAttachmentEncoding.isMarker(it) }
                val videoUri = videoMeta?.originalVideoUri
                // 動画マーカーに埋め込んだ音声 URI を優先し、なければレコードの audioUri を見る
                val effectiveAudioUri = videoMeta?.audioUri ?: message.audioUri
                val hasVideo = !videoUri.isNullOrBlank()
                val hasAudio = !effectiveAudioUri.isNullOrEmpty()
                val hasImages = imageUris.isNotEmpty()
                val hasTextFiles = textFiles.isNotEmpty()

                if (hasImages || hasAudio || hasVideo || hasTextFiles) {
                    mediaContainer.visibility = View.VISIBLE

                    if (hasImages) {
                        // 複数 or 動画由来のときは LazyRow相当の横スクロールに統一
                        if (imageUris.size > 1 || hasVideo || hasAudio) {
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            userImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(
                                imageUris,
                                imageContainer,
                                binding.root.context,
                                videoUri = videoUri,
                                audioUri = effectiveAudioUri,
                                textFiles = textFiles
                            )
                        } else if (hasTextFiles) {
                            // 単一画像 + テキスト添付: 画像とテキストカードを横スクロールに並べる
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            userImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(
                                imageUris,
                                imageContainer,
                                binding.root.context,
                                videoUri = videoUri,
                                audioUri = effectiveAudioUri,
                                textFiles = textFiles
                            )
                        } else {
                            // 単一画像・音声なし・動画なし：従来通り表示
                            imageScrollView.visibility = View.GONE
                            singleImageContainer.visibility = View.VISIBLE
                            userImagePreview.visibility = View.VISIBLE
                            audioPlaybackContainer.visibility = View.GONE
                            val singleUri = imageUris.first()
                            try {
                                loadImageIntoView(userImagePreview, singleUri)
                            } catch (e: Exception) {
                                userImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                            userImagePreview.setOnClickListener {
                                MediaViewerDialog.show(
                                    binding.root.context,
                                    MediaViewerDialog.MediaBundle(
                                        imageUris = imageUris,
                                        videoUri = videoUri,
                                        audioUri = effectiveAudioUri
                                    )
                                )
                            }
                        }
                    } else if (hasAudio) {
                        // 画像なし・音声のみ：従来の音声プレイヤー
                        imageScrollView.visibility = View.GONE
                        singleImageContainer.visibility = View.GONE
                        userImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(effectiveAudioUri!!, userAudioPlayButton, userAudioDuration)
                        // 音声プレイヤーの下にテキスト添付があれば横スクロールでも並べる
                        if (hasTextFiles) {
                            imageScrollView.visibility = View.VISIBLE
                            setupMultipleImagePreview(
                                emptyList(),
                                imageContainer,
                                binding.root.context,
                                textFiles = textFiles
                            )
                        }
                    } else if (hasTextFiles) {
                        // テキスト添付のみ
                        imageScrollView.visibility = View.VISIBLE
                        singleImageContainer.visibility = View.GONE
                        userImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.GONE
                        setupMultipleImagePreview(
                            emptyList(),
                            imageContainer,
                            binding.root.context,
                            textFiles = textFiles
                        )
                    }
                } else {
                    mediaContainer.visibility = View.GONE
                    userImagePreview.visibility = View.GONE
                }
                
                copyMessageButton.setOnClickListener {
                    copyAllToClipboard(binding.root.context, message.content)
                }
                // Bug fix(#Edit-Instead-Of-Revoke): 元は取り消し(取消)専用ボタンだったが、
                //   ペンアイコンに差し替えて「編集」専用にした。編集開始 (再入力欄に
                //   戻す) は ChatFragment 側の onUserPromptEdit で行う。生成中は
                //   対象メッセージがまだ確定していない可能性があるため無効化する。
                val generating = isGeneratingProvider()
                revokePromptButton.isEnabled = !generating
                revokePromptButton.setOnClickListener {
                    if (isGeneratingProvider()) return@setOnClickListener
                    onUserPromptEdit(message)
                }

                // Bug fix(#Actions-Always-Visible): コピー・編集は常時表示に戻す
                //   （吹き出しタップでの開閉トグルは廃止）。編集は生成中のみ
                //   個別に隠し、誤操作を防ぐ。
                messageActionsRow.visibility = View.VISIBLE
                revokePromptButton.visibility = if (generating) View.GONE else View.VISIBLE
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
 (it as? android.widget.Button)?.text = ""
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

 // 応答バリアントスワイプ検出用。bind() で差し替える。
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
 // Scroll fix: aiThinkingMarkdownCompose の layoutChangeListener は
            //   Thinking ブロックの展開/折りたたみやトークン追加のたびに冗長発火し、
            //   自動追従スクロールを不安定化させる原因になっていたため削除。
            //   本文 (aiMessageMarkdownCompose / aiMessageText) の高さ変化だけで
            //   下端追従判定は十分機能する。
            binding.aiMessageText.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    onAiMessageLayoutChanged()
                }
            }

 // 応答バリアントの横スワイプ検出。AI メッセージカード全体に仕掛ける。
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
 // 再生成ボタン：末尾の AI メッセージかつ非ストリーミング、非生成中にのみ表示する。
            val canRegenerate = isLastAiMessage && !isGenerating && !message.isStreaming
            binding.regenerateMessageButton.visibility =
                if (canRegenerate) View.VISIBLE else View.GONE
            binding.regenerateMessageButton.isEnabled = canRegenerate
            binding.regenerateMessageButton.setOnClickListener {
                if (canRegenerate) onRegenerate(message)
            }

 // 応答バリアントナビゲーション (◀ n/m ▶)
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

 // スワイプ検出のハンドラをこの bind() のバリアント情報で差し替える。
            //   スワイプ可能なのは "応答が 2 件以上 かつ 非ストリーミング中" だけに限定。
            swipeVariantHandler = if (variantTotal > 1 && !message.isStreaming && !isGenerating) {
                { direction ->
                    val next = (variantIndex + direction).coerceIn(0, variantTotal - 1)
                    if (next != variantIndex) onVariantSelect(next)
                }
            } else null
            binding.apply {
 // v5.1 Thinking 表示仕様：
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

 // バグ修正：生成中 = 強制展開、トグル行非表示 (閉じるボタンを消す)。
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

                // インライン tool-call カード表示のため、表示用テキストのみ <tool_call> タグを
                // 保持したまま sanitize する。stripGemmaTokens() はタグを常に除去するデフォルト
                // 実装 (preserveToolCallTags=false) なので、この行だけは直接呼び出しに変更している。
                // コピー・読み上げ用の他の stripGemmaTokens() 呼び出しは、タグを含めない従来通り
                // の挙動のままでよいため変更しない。
                val visibleContent = Gemma4ThinkingParser.sanitizeVisibleText(
                    message.content,
                    preserveToolCallTags = true
                )
                val visibleThinking = thinking

                // インライン tool-call カード化後は、本文の <tool_call> タグ位置で
                // InlineToolCallMessageBody がカードを差し込むので、旧 aiStreamingToolCallCompose
                // は常に非表示にしておく (レイアウトXMLは下位互換のため残存)。
                aiStreamingToolCallCompose.visibility = View.GONE

                val persistedToolCards = if (!message.toolResultsJson.isNullOrBlank()) {
                    ToolResultCard.listFromJsonArray(message.toolResultsJson)
                } else {
                    emptyList()
                }

                // 画像生成中のプレースホルダーはインラインカードとは別系統なので残す。
                val streamingToolName = if (message.isStreaming && message.id == streamingMessageId) {
                    when (val s = streamingToolCallState) {
                        is ToolCallState.Executing -> s.toolName
                        is ToolCallState.Result -> s.toolName
                        else -> null
                    }
                } else null
                if (streamingToolName == "generate_image") {
                    mediaContainer.visibility = View.VISIBLE
                    singleImageContainer.visibility = View.VISIBLE
                    aiImagePreview.visibility = View.VISIBLE
                    aiImagePreview.setImageResource(R.drawable.ic_image)
                    aiImagePreview.alpha = 0.3f
                } else {
                    aiImagePreview.alpha = 1.0f
                    aiImagePreview.setOnClickListener(null)
                }

                when {
                    message.isStreaming && visibleContent.isBlank() -> {
                        renderPlaceholder(visibleThinking)
                    }
                    else -> {
                        renderInlineBody(
                            content = visibleContent,
                            toolResults = persistedToolCards,
                            isStreaming = message.isStreaming
                        )
                    }
                }

                aiMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                
                // Media handling (統一ビュワー対応: User 側と同じロジック)
                val (aiVideoMeta, aiImageUrisRaw) = VideoAttachmentEncoding.split(message.imageUri)
                val aiTextFiles = TextFileAttachmentEncoding.extract(message.imageUri)
                val aiImageUris = aiImageUrisRaw.filter { !TextFileAttachmentEncoding.isMarker(it) }
                val aiVideoUri = aiVideoMeta?.originalVideoUri
                val aiEffectiveAudioUri = aiVideoMeta?.audioUri ?: message.audioUri
                val aiHasVideo = !aiVideoUri.isNullOrBlank()
                val aiHasAudio = !aiEffectiveAudioUri.isNullOrEmpty()
                val aiHasImages = aiImageUris.isNotEmpty()
                val aiHasTextFiles = aiTextFiles.isNotEmpty()

                if (aiHasImages || aiHasAudio || aiHasVideo || aiHasTextFiles) {
                    mediaContainer.visibility = View.VISIBLE

                    if (aiHasImages) {
                        if (aiImageUris.size > 1 || aiHasVideo || aiHasAudio) {
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            aiImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(
                                aiImageUris,
                                imageContainer,
                                binding.root.context,
                                videoUri = aiVideoUri,
                                audioUri = aiEffectiveAudioUri,
                                textFiles = aiTextFiles
                            )
                        } else if (aiHasTextFiles) {
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            aiImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(
                                aiImageUris,
                                imageContainer,
                                binding.root.context,
                                videoUri = aiVideoUri,
                                audioUri = aiEffectiveAudioUri,
                                textFiles = aiTextFiles
                            )
                        } else {
                            imageScrollView.visibility = View.GONE
                            singleImageContainer.visibility = View.VISIBLE
                            aiImagePreview.visibility = View.VISIBLE
                            audioPlaybackContainer.visibility = View.GONE
                            val singleUri = aiImageUris.first()
                            try {
                                loadImageIntoView(aiImagePreview, singleUri)
                            } catch (e: Exception) {
                                aiImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                            aiImagePreview.setOnClickListener {
                                MediaViewerDialog.show(
                                    binding.root.context,
                                    MediaViewerDialog.MediaBundle(
                                        imageUris = aiImageUris,
                                        videoUri = aiVideoUri,
                                        audioUri = aiEffectiveAudioUri
                                    )
                                )
                            }
                        }
                    } else if (aiHasAudio) {
                        imageScrollView.visibility = View.GONE
                        singleImageContainer.visibility = View.GONE
                        aiImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(aiEffectiveAudioUri!!, aiAudioPlayButton, aiAudioDuration)
                        if (aiHasTextFiles) {
                            imageScrollView.visibility = View.VISIBLE
                            setupMultipleImagePreview(
                                emptyList(),
                                imageContainer,
                                binding.root.context,
                                textFiles = aiTextFiles
                            )
                        }
                    } else if (aiHasTextFiles) {
                        imageScrollView.visibility = View.VISIBLE
                        singleImageContainer.visibility = View.GONE
                        aiImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.GONE
                        setupMultipleImagePreview(
                            emptyList(),
                            imageContainer,
                            binding.root.context,
                            textFiles = aiTextFiles
                        )
                    }
                } else {
                    mediaContainer.visibility = View.GONE
                    aiImagePreview.visibility = View.GONE
                }
                
                // 旧・末尾一括カードはインライン化に伴い廃止。コンテナは常に非表示。
                toolResultsContainer.removeAllViews()
                toolResultsContainer.visibility = View.GONE

                copyMessageButton.setOnClickListener {
 // Thinking (内部推論) はユーザー向けのコピー内容に含めない。
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

                // Bug fix(#Actions-Always-Visible): copy/speak/regenerate 等の操作アイコンは
                //   常時表示に戻す（吹き出しタップでの開閉トグルは廃止）。ストリーミング中は
                //   操作対象がまだ確定していないため個別のボタン側で無効化する
                //   （speak は上の canSpeak チェックで既に GONE 制御済み）。
                messageActionsRow.visibility = View.VISIBLE

 // t/s と TTFT は全般タブの設定で非表示にできる。既定は両方とも非表示。
                // Bug fix(#43): Adapter のキャッシュ値を優先し、未初期化のときだけ SharedPreferences を直接参照する。
                // これにより、設定フラグメントから戻ってきた直後に refreshPerfIndicatorVisibility() で
                // 強制リバインドさせれば、スクロールや新規メッセージを待たずに即座で反映される。
                val ctx = binding.root.context
                val showTps = cachedShowTps
                    ?: com.nezumi_ai.utils.PreferencesHelper.isShowTps(ctx).also { cachedShowTps = it }
                val showTtft = cachedShowTtft
                    ?: com.nezumi_ai.utils.PreferencesHelper.isShowTtft(ctx).also { cachedShowTtft = it }
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
                // Bug fix(#Meta-Row-Hierarchy): tv_tps はタイムスタンプと同格の常時表示ではなく、
                //   雷アイコン付きの「詳細」トグル経由でのみ開く。トグル自体は表示すべき情報が
                //   あるときだけ出し、既定では折り畳んでおく。
                if (parts.isNotEmpty()) {
                    tvTps.text = parts.joinToString("  ·  ")
                    perfMetaToggle.visibility = View.VISIBLE
                    tvTps.visibility = View.GONE
                    perfMetaToggle.setOnClickListener {
                        tvTps.visibility =
                            if (tvTps.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                } else {
                    perfMetaToggle.visibility = View.GONE
                    tvTps.visibility = View.GONE
                    perfMetaToggle.setOnClickListener(null)
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

 /** 新: TTFT (Time To First Token) の表示フォーマット。 */
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

        /**
         * インライン tool-call カード対応の本文描画。
         * 本文中の <tool_call> タグ位置にカードを差し込み、前後のテキストと
         * 順に縦に並べる。レガシー本文 (タグなし) は Composable 内部で
         * 従来のバブルテキストにフォールバックされるので、旧レコードも安全に描画される。
         */
        private fun renderInlineBody(
            content: String,
            toolResults: List<ToolResultCard>,
            isStreaming: Boolean
        ) {
            // ドキュメント生成カードの保存ボタンは、生成終了後 (ストリーミング完了後)
            // にのみ有効にする。生成中に押されても中身が未確定のファイルを
            // コピーしてしまうため。
            val saveHandler: ((String, String, String, (Boolean) -> Unit) -> Unit)? =
                if (isStreaming) null else onSaveGeneratedDocument
            // インライン描画では本文とカードを含めて常に Compose で描画する。
            // renderMarkdown のキャッシュと衝突しないよう、嬉 lastRenderedContent をリセット。
            binding.aiMessageText.visibility = View.GONE
            binding.aiMessageMarkdownCompose.visibility = View.VISIBLE
            binding.aiMessageMarkdownCompose.setContent {
                NezumiComposeTheme {
                    InlineToolCallMessageBody(
                        content = content,
                        toolResults = toolResults,
                        isStreaming = isStreaming,
                        onSaveDocument = saveHandler
                    )
                }
            }
            lastRenderedContent = content
            lastRenderedContentMode = ContentRenderMode.Markdown
        }

        private fun renderThinkingMarkdown(content: String) {
 // Scroll fix:
            //   - early-return 判定は lastRenderedThinking の同値だけで十分。
            //     lastRenderedContentMode は「本文」用のフラグなのでここで参照/更新しない。
            //   - aiThinkingMarkdownCompose の visibility は呼び出し元の展開/折りたたみロジックが管理する。
            //   - onAiMessageLayoutChanged() は呼ばない。Thinking ブロックのレイアウト変化で
            //     毎トークン自動スクロールが再スケジュールされ、追従が乱れるため。
            //
 // バグ修正 (一箱所に重なる/表示されない):
            //   旧実装は early-return しても lastRenderedThinking を呼び出し元で更新する側だったが、
                //   同一ビューホルダーの再利用時に Compose のスナップショットがリセットされず、
                //   setContent が呼ばれないケースがあった。確実に新しい content で setContent するよう、
                //   キャッシュを renderThinkingMarkdown 内部でも保持する。
            if (lastRenderedThinking == content) return

 // Bug fix(#Thinking-Layout):
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
 * Thinking ブロック専用の Markdown 描画。
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
 (it as? android.widget.Button)?.text = ""
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

