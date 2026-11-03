package com.nezumi_ai.presentation.viewmodel

import android.graphics.Bitmap
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.data.inference.MediaTokenProgress
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics
import com.nezumi_ai.data.repository.ToolCallHistoryRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.SystemClock
import android.util.Log
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.EngineManager
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.TextTokenEstimator
import com.nezumi_ai.data.inference.ThinkingLeakSalvage
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ChatMarkupSpec
import com.nezumi_ai.data.inference.GgufFormatResolver
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.presentation.viewmodel.usecase.ModelSessionCoordinator
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.data.inference.ToolCallTags
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.data.media.VideoAttachmentEncoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.File

/**
 * クラスタ B (生成オーケストレーション)。
 *
 * [ChatViewModel] から `generateAIResponse` 本体を移管した。
 * Phase 2 はクラス移動 + 委譲を優先し、Host インターフェース化は後続とする。
 * ViewModel の State / 補助メソッドは internal 経由で [vm] から参照する。
 */
internal class GenerationCoordinator(
    private val vm: ChatViewModel
) {
    suspend fun generateAIResponse(
        sessionId: Long,
        userMessage: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        currentTurnMessageId: Long? = null
    ) {
        var streamingMessageId: Long? = null
        var currentHasMediaInput = false
        var currentEngineModelName: String? = null
        // Bug fix: 正常完了パス (isStreaming=false を明示的に書き込んだ) と、
        //   finally の safety fallback (異常収束時のみ ThinkingLeakSalvage を走らせたい)
        //   を区別するためのフラグ。DB の isStreaming 再読み込みだけに頼ると、
        //   正常完了直後の一瞬のタイミングで誤って salvage が発火し、正しく分離済みの
        //   thinking が再度 content 側へ漏れて表示される不具合があった。
        var normalCompletionPersisted = false
        // Bug fix(#5): 過去ターンの media を LiteRT に再添付する際に生成する Bitmap を保持し、
        // finally で確実に recycle するため try の外側にスコープを出しておく。
        val pastHistoryBitmapsToRecycle = mutableListOf<Bitmap>()
        val aiStartMs = System.currentTimeMillis()  // Phase 11: 全体ロード時間を計測開始
        try {
            // Acquire WakeLock to prevent screen sleep during generation
            vm.acquireScreenWakeLock()

            // Tool Call State マシンをリセット。
            //   以前は vm.viewModelScope.launch で非同期にしていたが、前ターンの
            //   Executing / Result が開始直後の 1 フレーム残る / 停止直後の開始で
            //   古い状態が一瞬表示される原因になっていたため同期でクリアする。
            vm._toolCallState.value = null

            val manager = vm.requireModelManager()
            val selectedModel = vm.getActiveSelectedModel()
            val engineModelName = vm.toEngineModelName(selectedModel)
            currentEngineModelName = engineModelName

            vm._selectedModel.value = selectedModel
            val hasMediaInput = images.isNotEmpty() || audioClips.isNotEmpty()
            currentHasMediaInput = hasMediaInput
            val modelAvailable = ModelFileManager.isModelAvailable(vm.appContext, engineModelName)
            Log.d(ChatViewModel.TAG, "generateAIResponse: model=$selectedModel engine=$engineModelName available=$modelAvailable")
            val isCloudSelected = com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(selectedModel)
            if (isCloudSelected) {
                val cloudBlock = vm.cloudUseBlockMessage(selectedModel)
                if (cloudBlock != null) {
                    Log.w(ChatViewModel.TAG, "generateAIResponse: cloud model blocked: $engineModelName")
                    if (vm.modelLoadingOverlayDepth == 0) vm.clearModelLoadingIndicator()
                    vm.messageRepository.addMessage(
                        sessionId = sessionId,
                        role = "assistant",
                        content = cloudBlock
                    )
                    return
                }
            } else if (!modelAvailable) {
                val unavailableMsg = vm.appContext.getString(R.string.selected_model_not_downloaded, selectedModel)
                Log.w(ChatViewModel.TAG, "generateAIResponse: load not started, model unavailable: $engineModelName")
                if (vm.modelLoadingOverlayDepth == 0) {
                    vm.clearModelLoadingIndicator()
                }
                vm.messageRepository.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    content = unavailableMsg
                )
                return
            }
            // GGUF メタデータの chat_template 確認 (isAlwaysOnThinkingModel 等) は
            // loadModelWithOverlay より前に走る。オーバーレイ無しだとユーザーにはフリーズに見えるため、
            // ロード時間表示をここで開始し、テンプレート確認フェーズを出す。
            // クラウドは重みを載せず、ローカルモデルの解放が必要なときだけ画面を出す。
            val modelLoadStartMs = System.currentTimeMillis()
            val currentLoaded = manager.getCurrentModelName()
            val releaseLocal = isCloudSelected && currentLoaded != null &&
                !com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(currentLoaded)
            if (!isCloudSelected || releaseLocal) {
                vm.beginModelPreparationIndicator(
                    vm.appContext.getString(
                        if (releaseLocal) R.string.model_loading_phase_memory_release
                        else R.string.model_loading_phase_template_scan
                    )
                )
            }
            val baseConfig = vm.chatInferenceConfigForModel(selectedModel, probeTemplate = false)
            val backend = vm.settingsRepository.getBackendForModel(selectedModel)
            // Bug fix: LiteRT-LM 外部インポートモデル (.task / .litertlm) については、
            // モデル設定で画像/音声のスイッチを ON にしていない限り requireMultimodal を立てない。
            // これをガードしないと vision/audio executor のロードを試みてエラーになる。
            val engineLowerForCaps = engineModelName.lowercase()
            val isImportedLiteRtForCaps = (engineLowerForCaps.endsWith(".task") || engineLowerForCaps.endsWith(".litertlm")) &&
                File(engineModelName).isAbsolute
            val importedMultimodalAllowed = if (isImportedLiteRtForCaps) {
                val caps = com.nezumi_ai.utils.ImportedModelCapabilityStore.get(vm.appContext, engineModelName)
                (images.isNotEmpty() && caps.imageEnabled) || (audioClips.isNotEmpty() && caps.audioEnabled)
            } else {
                // ビルトイン Gemma 等は従来通り hasMediaInput だけで判定
                hasMediaInput
            }
            val config = baseConfig.copy(
                backendType = backend,
                requireMultimodal = importedMultimodalAllowed && !engineModelName.endsWith(".gguf", ignoreCase = true)
            ).normalized()
            val aiStartMs = System.currentTimeMillis()
            Log.d(ChatViewModel.TAG, "generateAIResponse START: model=$selectedModel, enableThinking=${config.enableThinking}, backend=${config.backendType}, requestedBackend=$backend")

            // Phase 11: ロード進捗ログの細分化
            Log.d(ChatViewModel.TAG, "generateAIResponse LOAD_START: model=$selectedModel templateProbe=false")

            // ロード前メモリ警告を出す。ユーザーが続行を選ぶまで実ロードは始めない。
            // proceedWithModelLoad 側で skipMemoryWarning=true にしてから再呼び出しする。
            val isModelAlreadyLoaded = manager.isModelLoaded(engineModelName, config)
            val skipMemoryWarning = false
            Log.d(
                ChatViewModel.TAG,
                "generateAIResponse: engineModelName=$engineModelName isModelAlreadyLoaded=$isModelAlreadyLoaded skipMemoryWarning=$skipMemoryWarning"
            )

            val loadResult = vm.loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false, skipMemoryWarning = skipMemoryWarning)

            val modelLoadEndMs = System.currentTimeMillis()
            Log.d(ChatViewModel.TAG, "generateAIResponse LOAD_END: model=$selectedModel duration=${modelLoadEndMs - modelLoadStartMs}ms success=${loadResult.isSuccess}")

            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()
                val errorMsg = error?.message ?: "Unknown error"
                Log.e(ChatViewModel.TAG, "Model loading failed for $selectedModel: $errorMsg", error)

 // 警告ダイアログ表示中はユーザー操作を待つ
                if (ModelSessionCoordinator.isModelLoadWarningMarker(error)) {
                    Log.d(ChatViewModel.TAG, "Model warning shown - waiting for user action: $errorMsg")
                    return
                }

                // メモリエラーを検出（実際の OOM エラー）
                if (ModelSessionCoordinator.isMemoryLoadFailure(error)) {
                    val memStatus = MemoryObserver.getMemoryStatus(vm.appContext)
                    vm._memoryError.value = ChatViewModel.MemoryErrorInfo(
                        usedPercent = memStatus.usedPercent,
                        usedMB = memStatus.usedMB,
                        totalMB = memStatus.maxMB
                    )
                    return
                }

                // ファイル読み込みエラーを検出
                if (ModelSessionCoordinator.shouldDeleteLocalModelFileOnLoadError(errorMsg, error)) {

                    Log.w(ChatViewModel.TAG, "モデルファイルの読み込みエラー: $selectedModel")
                    // モーダルダイアログ用に詳細をセット
                    vm._modelErrorDialogMessage.value = vm.formatModelErrorDialogMessage(
                        title = vm.appContext.getString(R.string.model_load_error_title),
                        message = vm.appContext.getString(R.string.model_load_error_unreadable),
                        details = if (errorMsg.isNotBlank()) vm.appContext.getString(R.string.model_error_path_with_msg, errorMsg, selectedModel) else vm.appContext.getString(R.string.model_error_path_only, selectedModel)
                    )
                    // 軽い通知も出す
 vm._uiMessage.emit(vm.appContext.getString(R.string.model_load_error_unreadable))

                    // ファイルを削除してリセット
                    try {
                        val modelEnum = when (selectedModel.uppercase()) {
                            "GEMMA4-2B" -> ModelFileManager.LocalModel.GEMMA4_2B
                            "GEMMA4-4B" -> ModelFileManager.LocalModel.GEMMA4_4B
                            else -> ModelFileManager.LocalModel.GEMMA4_2B
                        }
                        ModelFileManager.clearCorruptedModel(vm.appContext, modelEnum)
                        Log.i(ChatViewModel.TAG, "モデルファイルをクリアしました")
                    } catch (e: Exception) {
                        Log.e(ChatViewModel.TAG, "モデルファイルのクリアに失敗", e)
                    }
                    return
                }

                vm.handleModelLoadIssue(selectedModel, error)
                return
            }

            Log.d(ChatViewModel.TAG, "Starting inference for session $sessionId")
            // 生成中も送信直前の計算を出す。リアルタイム更新はしない。
            vm.publishContextMeterBeforeGeneration(sessionId)

            // ① 起動時 pending 抽出処理（モデルロード完了後に1回だけ実行）
            //
            // v2.1+: TOOL_ONLY モードのときは pending 自体が発生しないはずだが、
            // 以前のバージョンで作られた pending フラグが残っている可能性があるため
            // Worker 内部でも共に安全側にガードしている。
            if (!vm.pendingExtractionProcessed) {
                vm.pendingExtractionProcessed = true
                val pendingSaveMode = vm.settingsRepository.getMemorySaveMode()
                if (pendingSaveMode != com.nezumi_ai.data.memory.MemorySaveMode.TOOL_ONLY) {
                    vm.memoryExtractionWorker?.processPending(manager, config.copy(
                        temperature = 0.1f,
                        enableThinking = false
                    ), pendingSaveMode, { fetchSessionId ->
                        vm.messageRepository.getMessagesForSessionOnce(fetchSessionId)
                    }, suppressContradictionDeletion = true)
                } else {
                    Log.d(ChatViewModel.TAG, "MEMORY_PENDING: TOOL_ONLY mode - skipping pending processing")
                }
            }

            // LiteRT-LM 経路では Conversation API がエンジン側でチャットテンプレートを適用するため、
            // アプリ側でテンプレート文字列を組まず構造化ペイロード (system / history / current) を送る。
            // GGUF / クラウド / 内部推論は従来通りの単一テキストプロンプトを使う。
            val promptForModel = if (!vm.isGgufEngineModel(engineModelName) &&
                !com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(engineModelName)
            ) {
                vm.buildLiteRtStructuredPayload(
                    sessionId = sessionId,
                    config = config,
                    currentTurnMessageId = currentTurnMessageId
                )
            } else {
                vm.buildPromptWithSessionContext(
                    sessionId = sessionId,
                    config = config,
                    manager = manager,
                    engineModelName = engineModelName,
                    currentTurnMessageId = currentTurnMessageId
                )
            }

            // Bug fix: 外部インポートモデルの capability チェック。
            // GGUF（.gguf）に加え、LiteRT-LM インポート（.task / .litertlm）も同様に
            // 画像/音声のスイッチがオフの場合はエンジンに media を渡さない。
            // これをガードしないと LiteRtLmEngine 側で hasMultimodalInput=true と判定され、
            // requireMultimodal=true での再ロードが走り、非マルチモーダルなインポートモデルで
            // ロードエラーが発生する。
            val engineLower = engineModelName.lowercase()
            val isImportedGguf = engineLower.endsWith(".gguf") && File(engineModelName).isAbsolute
            val isImportedLiteRt = (engineLower.endsWith(".task") || engineLower.endsWith(".litertlm")) &&
                File(engineModelName).isAbsolute
            if (isImportedGguf || isImportedLiteRt) {
                val caps = com.nezumi_ai.utils.ImportedModelCapabilityStore.get(vm.appContext, engineModelName)
                val modelKindLabel = if (isImportedGguf) "GGUF" else "LiteRT-LM"
                if (images.isNotEmpty() && !caps.imageEnabled) {
                    Log.w(
                        ChatViewModel.TAG,
                        "$modelKindLabel: images sent but image capability off. count=${images.size}"
                    )
                    vm._uiMessage.emit(
                        vm.appContext.getString(R.string.vm_model_image_off, modelKindLabel)
                    )
                    return
                }
                if (audioClips.isNotEmpty() && !caps.audioEnabled) {
                    Log.w(
                        ChatViewModel.TAG,
                        "$modelKindLabel: audio sent but audio capability off. count=${audioClips.size}"
                    )
                    vm._uiMessage.emit(
                        vm.appContext.getString(R.string.vm_model_audio_off, modelKindLabel)
                    )
                    return
                }
            }
            if (com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(engineModelName)) {
                val media = com.nezumi_ai.data.inference.cloud.CloudMediaCapability.forModel(engineModelName)
                if (images.isNotEmpty() && !media.image) {
                    Log.w(ChatViewModel.TAG, "cloud: images sent but image capability off. count=${images.size}")
                    vm._uiMessage.emit(vm.appContext.getString(R.string.vm_model_image_off, "Cloud"))
                    return
                }
                if (audioClips.isNotEmpty() && !media.audio) {
                    Log.w(ChatViewModel.TAG, "cloud: audio sent but audio capability off. count=${audioClips.size}")
                    vm._uiMessage.emit(vm.appContext.getString(R.string.vm_model_audio_off, "Cloud"))
                    return
                }
            }

            // GGUF マルチモーダル: JNI が mmproj 未指定時もベース GGUF から clip/mtmd を初期化する（単一ファイル統合型）

            // Bug fix(#5): LiteRT-LM では会話の KV キャッシュを再利用するため、以前のターンで渡した
            // 画像/音声をモデルが参照できない。以前ターンの media を送信履歴から採集し、今ターンの
            // images/audioClips の先頭に追加することで、マルチターンでも画像/音声を参照できるようにする。
            // GGUF エンジンは現状 mtmd 側の仕様上マルチターン参照をサポートしていないため、
            // LiteRT エンジン使用時のみ過去 media を取り込む。
            val isLiteRtEngine = !vm.isGgufEngineModel(engineModelName)
            val combinedImages = mutableListOf<Bitmap>().also { it.addAll(images) }
            val combinedAudio = mutableListOf<ByteArray>().also { it.addAll(audioClips) }
            if (isLiteRtEngine) {
                runCatching {
                    val historyMessages = vm.messageRepository.getMessagesForSessionOnce(sessionId)
                    // 今ターンの user メッセージを除外するため currentTurnMessageId より古いものだけを対象にする。
                    val pastMessages = if (currentTurnMessageId != null) {
                        historyMessages.filter { it.id != currentTurnMessageId && it.timestamp <
                            (historyMessages.firstOrNull { m -> m.id == currentTurnMessageId }?.timestamp ?: Long.MAX_VALUE) }
                    } else {
                        historyMessages.dropLast(1)
                    }
                    // 過去の user メッセージの画像/音声を古い順に読み込んで先頭にプレフィックスする。
                    val historicalImages = mutableListOf<Bitmap>()
                    val historicalAudio = mutableListOf<ByteArray>()
                    for (m in pastMessages.filter { it.role == "user" }) {
                        m.imageUri
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            // nezumi://videoframes / nezumi://txtfile マーカーは Bitmap 化対象外
                            ?.filter { !com.nezumi_ai.data.media.VideoAttachmentEncoding.isMarker(it) }
                            ?.filter { !com.nezumi_ai.data.media.TextFileAttachmentEncoding.isMarker(it) }
                            ?.forEach { uriStr ->
                                val uri = MessageMediaStore.toUri(uriStr) ?: return@forEach
                                val bmp = vm.loadBitmapFromUri(uri) ?: return@forEach
                                val scaled = vm.scaleBitmapTo1024(bmp)
                                if (scaled !== bmp) bmp.recycle()
                                historicalImages.add(scaled)
                                pastHistoryBitmapsToRecycle.add(scaled)
                            }
                        m.audioUri?.let { uriStr ->
                            val uri = MessageMediaStore.toUri(uriStr) ?: return@let
                            val bytes = vm.loadAudioBytesFromUri(uri) ?: return@let
                            historicalAudio.add(bytes)
                        }
                    }
                    if (historicalImages.isNotEmpty() || historicalAudio.isNotEmpty()) {
                        combinedImages.addAll(0, historicalImages)
                        combinedAudio.addAll(0, historicalAudio)
                        Log.d(
                            ChatViewModel.TAG,
                            "LiteRT multi-turn media replay: addedImages=${historicalImages.size} addedAudio=${historicalAudio.size}"
                        )
                    }
                }.onFailure {
                    Log.w(ChatViewModel.TAG, "Failed to load past-turn media for LiteRT replay", it)
                }
            }
            val effectiveHasMediaInput = combinedImages.isNotEmpty() || combinedAudio.isNotEmpty()
            if (effectiveHasMediaInput && isLiteRtEngine) {
                // LiteRT 側に「このセッションで media を取り扱う」と伝え、 KV キャッシュ再利用ではなく
                // 毎ターン conversation を作り直すようにさせる。
                runCatching {
                    vm.requireModelManager().liteRtEngineForMultiTurnMedia()?.markSessionHasMedia(sessionId)
                }
            }

            // ストリーミング推論を実行（マルチモーダル対応）
            val aiResponseFlow: Flow<String> = withContext(Dispatchers.IO) {
                if (com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(engineModelName)) {
                    // Phase 5/6: クラウドは role 付きメッセージ配列を構造化して送る正式経路。
                    // 旧来の平文プロンプト (Gemma タグ込み単一文字列) は使わない (計画書 1.2)。
                    Log.d(ChatViewModel.TAG, "Using cloud structured inference: images=${combinedImages.size}")
                    manager.runCloudInferenceWithMessages(
                        sessionId = sessionId,
                        messages = vm.buildCloudChatMessages(
                            sessionId = sessionId,
                            config = config,
                            engineModelName = engineModelName,
                            currentTurnMessageId = currentTurnMessageId
                        ),
                        images = combinedImages,
                        config = config
                    )
                } else if (effectiveHasMediaInput) {
                    // マルチモーダル推論
                    Log.d(ChatViewModel.TAG, "Using multimodal inference: ${combinedImages.size} images, ${combinedAudio.size} audio clips (incl. past turns)")
                    manager.runInferenceWithMedia(
                        sessionId = sessionId,
                        prompt = promptForModel,
                        images = combinedImages,
                        audioClips = combinedAudio,
                        config = config
                    )
                } else {
                    // テキストのみ推論
                    Log.d(ChatViewModel.TAG, "Using text-only inference")
                    manager.runInference(
                        sessionId = sessionId,
                        prompt = promptForModel,
                        config = config
                    )
                }
            }

 // 応答バリアント: 再生成リクエストの場合は _pendingAssistantVariantSpec がセットされているので
            //   その parent + variantIndex を使う。通常送信の場合は currentTurnMessageId (user メッセージ id) を
            //   parent にして variantIndex=0 で新規登録する。currentTurnMessageId が null なら旧互換の null。
            val variantSpec = vm._pendingAssistantVariantSpec
            val effectiveParentId = variantSpec?.parentUserMessageId ?: currentTurnMessageId
            val effectiveVariantIndex = variantSpec?.variantIndex ?: 0
            streamingMessageId = vm.messageRepository.addMessage(
                sessionId = sessionId,
                role = "assistant",
                content = "",
                isStreaming = true,
                parentUserMessageId = effectiveParentId,
                variantIndex = effectiveVariantIndex
            )
            val activeStreamingMessageId = streamingMessageId
                ?: throw IllegalStateException("Failed to create streaming message")
            vm.streamingAssistantMessageIdForTools = activeStreamingMessageId
 // 新: TTFT (Time To First Token) の基準時刻。推論パイプラインに入った直後の時刻として抱える。
            val streamStartedAtMs: Long = SystemClock.elapsedRealtime()
 // ストリーミングレコードの id が定まったので、この parent の選択ターゲットを
            //   新規作成した variantIndex に合わせておく。UI を新応答にジャンプさせるため。
            if (effectiveParentId != null) {
                val newSelection = vm._selectedVariantByParent.value.toMutableMap()
                newSelection[effectiveParentId] = effectiveVariantIndex
                vm._selectedVariantByParent.value = newSelection
 // 永続化: 新規バリアント選択も保存する。
                vm._currentSessionId.value?.let { sid ->
                    vm.persistVariantSelectionForSession(sid, newSelection)
                }
            }

            val answerBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            var nativeThinkingStream = false
            // Only seed a synthetic `<think>` opener when the selected model family actually uses
            // assistant-side `<think>...</think>` prefilling. Qwen uses `/think` appended to the
            // last user turn, and Gemma uses a global `<|think|>` prefix instead; seeding `<think>`
            // for those models would misparse their outputs.
            // ネイティブ (GGUF chat_template) 適用中はテンプレート側が prefill を制御するため、
            // Kotlin 側の `<think>` seed は二重適用になるので抑制する。
            val nativeGgufTemplateActive =
                vm.isGgufEngineModel(engineModelName) && manager.hasGgufChatTemplate()
            val markupSpec = runCatching {
                GgufFormatResolver.resolveMarkupSpec(engineModelName, vm.appContext)
            }.getOrDefault(ChatMarkupSpec.DEFAULT)
            val isGgufStream = vm.isGgufEngineModel(engineModelName)
            val resolvedThinkStyle = markupSpec.thinkingStyle
                ?: ModelNameHeuristics.resolveThinkingPromptStyle(
                    modelPathOrName = engineModelName,
                    chatTemplate = GgufFormatResolver.resolveChatTemplateText(vm.appContext, engineModelName),
                )
            val usesThinkPrefill =
                resolvedThinkStyle == ModelNameHeuristics.ThinkingPromptStyle.ASSISTANT_TAG ||
                    resolvedThinkStyle == ModelNameHeuristics.ThinkingPromptStyle.GEMMA4_CHANNEL ||
                    resolvedThinkStyle == ModelNameHeuristics.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            val chatTemplateText = GgufFormatResolver.resolveChatTemplateText(vm.appContext, engineModelName)
            // Granite 4.2 公式 jinja は add_generation_prompt で
            // `<|im_start|>assistant\n<think>\n` をプロンプト末尾に置く。
            // 開きタグは出力に出ず、閉じタグ `</think>` だけが生成される。
            val promptOpenedThink =
                config.enableThinking &&
                    vm.isGgufEngineModel(engineModelName) &&
                    ModelNameHeuristics.templatePrefillsOpenThink(chatTemplateText.orEmpty())
            val implicitThinkPrefill =
                config.enableThinking &&
                    vm.isGgufEngineModel(engineModelName) &&
                    usesThinkPrefill &&
                    !nativeGgufTemplateActive &&
                    !promptOpenedThink
            if (implicitThinkPrefill) {
                answerBuilder.append("<think>\n")
            }
            fun rawForThinkParse(raw: String): String {
                if (!promptOpenedThink) return raw
                if (markupSpec.containsThinkingOpen(raw)) return raw
                return "<think>\n$raw"
            }
            fun treatUnmarkedAsThinking(raw: String, streaming: Boolean): Boolean {
                if (!config.enableThinking || !isGgufStream) return false
                if (markupSpec.containsThinkingClose(raw)) return false
                if (markupSpec.containsThinkingOpen(raw) || implicitThinkPrefill || promptOpenedThink) return true
                return streaming && markupSpec.supportsThinking
            }
            var lastPersistedContent = ""
            var lastPersistedThinking: String? = null
            // 確定時の一括パーサーがストリーミング中の分離結果を壊さないよう、
            // UIへ最後に提示した本文/Thinkingのペアを保持する。
            var lastStreamContentForFinal = ""
            var lastStreamThinkingForFinal: String? = null
            // 生成中に一度でも「思考と本文が別物として分かれた」状態を残す。
            // FINAL チャンクがタグ無し全文だと、最後の UI 更新が本文を Thinking へ
            // 戻して lastStreamContent を空にしてしまう。
            var lastGoodStreamContent = ""
            var lastGoodStreamThinking: String? = null
            var lastPersistAt = 0L
            var toolResultsJson: String? = null
            var firstOutputAtMs: Long? = null
 // 新: 本文の最初のトークンが到達した時刻 (シンキング間は含めない。TPS 算出に使う)
            var firstAnswerAtMs: Long? = null
 // 新: シンキング中の経過時間 (ms) を累積して本文生成時間から引く
            var thinkingElapsedMs: Long = 0L
 // 新: シンキングフェーズ開始時刻 (フェーズが閉じた時に差分を thinkingElapsedMs に加算)
            var thinkingStartedAtMs: Long? = null
            var generationEndAtMs: Long? = null
            var tokenCount = 0f

            // ストリーム内容を収集
            // タイムアウトは「最初の出力が来るまで」のみ有効。
            val firstTokenSeen = AtomicBoolean(false)

            val lastChunkAt = AtomicLong(SystemClock.elapsedRealtime())
            val wallEndAt = SystemClock.elapsedRealtime() + ChatViewModel.GENERATION_WALL_TIMEOUT_MS
            val toolCallInProgress = AtomicBoolean(false)
            var streamAbortNote: String? = null
            var collectionCancelledByUser = false
            // FINAL は生成ラウンドの終端。Binder のバッチ化で FINAL と後続の
            // stale chunk が同じ配信に含まれても、終了後の文字列を本文/Thinkingへ混ぜない。
            var finalReceived = false
            try {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val firstTokenTimeoutJob = launch {
                            delay(ChatViewModel.RESPONSE_TIMEOUT_MS)
                            if (!firstTokenSeen.get()) {
                                cancel(ChatViewModel.FirstTokenTimeoutException())
                            }
                        }

                        val stallTimeoutMs = PreferencesHelper
                            .getGenerationStallTimeoutMs(vm.appContext).toLong()
                        val stallWatchJob = launch {
                            while (isActive) {
                                delay(ChatViewModel.GENERATION_STALL_CHECK_MS)
                                // Bug fix (応答終了後もボタンが生成中状態のまま):
                                //   壁時計タイムアウトはこれまで chunk 到着時にしか評価されず、
                                //   ツール実行中等にエンジンの Flow が黙って止まると
                                //   (toolCallInProgress=true で停滞検知もスキップされるため)
                                //   collect が永久に返らず _isLoading が true のまま残っていた。
                                //   ウォッチドッグ側でも壁時計を評価し、どんな停滞パターンでも
                                //   必ず収束するようにする。
                                if (SystemClock.elapsedRealtime() > wallEndAt) {
                                    throw ChatViewModel.GenerationWallTimeoutException()
                                }
                                if (!firstTokenSeen.get()) continue
                                val idle = SystemClock.elapsedRealtime() - lastChunkAt.get()
                                // Bug fix (LiteRT tool-call パース失敗後に生成中のまま):
                                //   Gemma4 形式の壊れた tool call で native が Status Code: 3
                                //   (Failed to parse tool calls from code block) を出したあと
                                //   Flow が完了せず、toolCallInProgress=true のまま停滞検知を
                                //   永久スキップしていた。ツール実行中でも chunk 無通信が
                                //   stallTimeout を超えたら停滞扱いにする。
                                if (idle >= stallTimeoutMs) {
                                    throw ChatViewModel.GenerationStalledException()
                                }
                            }
                        }

                        try {
                            aiResponseFlow.collect { chunk ->
                                if (SystemClock.elapsedRealtime() > wallEndAt) {
                                    throw ChatViewModel.GenerationWallTimeoutException()
                                }
                                if (!firstTokenSeen.getAndSet(true)) {
                                    firstTokenTimeoutJob.cancel()
                                    firstOutputAtMs = SystemClock.elapsedRealtime()
                                }
                                val chunkArrivedAt = SystemClock.elapsedRealtime()
                                lastChunkAt.set(SystemClock.elapsedRealtime())
                                // Split incoming chunk by embedded control markers so that
                                // markers like \u0000__TPS__\u0000 or \u0000__FINAL__\u0000
                                // are handled separately even if they arrive inside a single
                                // delivered chunk.
                                // クラウドモデルではライブ TPS を更新しない
                                //   (メーター同様、ネットワーク込みの見かけ速度は表示しない方針)。
                                val isCloudStreamModel =
                                    com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(engineModelName)
                                var finalFromModelGlobal: String? = null
                                val segments = InferenceStreamProtocol.splitStreamChunks(chunk)
                                for (seg in segments) {
                                    if (finalReceived) continue
                                    val finalFromModel = InferenceStreamProtocol.decodeFinal(seg)
                                    val thinkDelta = InferenceStreamProtocol.decodeThinkChunk(seg)
                                    val toolCallChunk = InferenceStreamProtocol.decodeToolCallChunk(seg)
                                    val toolResultChunk = InferenceStreamProtocol.decodeToolResultChunk(seg)
                                    val toolResults = InferenceStreamProtocol.decodeToolResults(seg)
                                    val tpsValue = InferenceStreamProtocol.decodeTps(seg)
                                    val mediaProgress = InferenceStreamProtocol.decodeMediaProgress(seg)

                                    when {
                                        mediaProgress != null -> {
                                            // :gguf プロセスから転送された入力処理進捗をメイン側の
                                            // MediaTokenProgress に反映する（UI が collect している）。
                                            if (mediaProgress.stage.isBlank()) {
                                                MediaTokenProgress.clear()
                                            } else {
                                                MediaTokenProgress.update(
                                                    mediaProgress.stage,
                                                    mediaProgress.tokens,
                                                    mediaProgress.tps
                                                )
                                            }
                                        }
                                        finalFromModel != null -> {
                                            finalReceived = true
                                            Log.d(ChatViewModel.TAG, "FINAL received: length=${finalFromModel.length}")
                                            // 完了時に FINAL 全文でストリーム済み本文を差し替えない。
                                            // 差し替えるとツールカード周りで「完全版への再置き換え」が起き、
                                            // テキストが一瞬消えてガクンと跳ねる。FINAL は終了合図だけに使う。
                                            finalFromModelGlobal =
                                                answerBuilder.toString().ifBlank { finalFromModel }
                                        }
                                        thinkDelta != null -> {
 // シンキングフェーズ開始を記録 (未開始のときだけ)
                                            if (thinkingStartedAtMs == null && firstAnswerAtMs == null) {
                                                thinkingStartedAtMs = chunkArrivedAt
                                            }
                                            if (!nativeThinkingStream && answerBuilder.isNotBlank()) {
                                                val leadingThinking =
                                                    Gemma4ThinkingParser.sanitizeVisibleText(answerBuilder.toString())
                                                if (leadingThinking.isNotBlank()) {
                                                    thinkingBuilder.append(leadingThinking)
                                                }
                                                answerBuilder.clear()
                                            }
                                            nativeThinkingStream = true
                                            if (thinkDelta.isNotEmpty()) {
                                                val curT = thinkingBuilder.toString()
                                                val mergedT = vm.mergeStreamingChunk(curT, thinkDelta)
                                                if (mergedT != curT && mergedT.length >= curT.length) {
                                                    thinkingBuilder.clear()
                                                    thinkingBuilder.append(mergedT)
                                                    // 修正: Thinking の差分も生成トークンとして計上する。
                                                    //   (旧: 本文のみ計上していたため、Thinking が長いと
                                                    //    TPS が実態より大幅に低く表示されていた)
                                                    val thinkDeltaText = mergedT.substring(curT.length)
                                                    tokenCount += TextTokenEstimator.estimateOutputTokens(thinkDeltaText)
                                                    // ライブ TPS は最初の出力 (Thinking 含む) からの経過で算出
                                                    if (tokenCount >= 10f && firstOutputAtMs != null && !isCloudStreamModel) {
                                                        val elapsedThink = (SystemClock.elapsedRealtime() - firstOutputAtMs!!)
                                                            .coerceAtLeast(1L)
                                                        vm._currentTps.value = (tokenCount * 1000f) / elapsedThink
                                                    }
                                                } else if (mergedT.length < curT.length) {
                                                    Log.w(
                                                        ChatViewModel.TAG,
                                                        "Thinking chunk merge would shrink: ${curT.length} -> ${mergedT.length}, skipping"
                                                    )
                                                }
                                            }
                                        }
                                        toolCallChunk != null -> {
                                            toolCallInProgress.set(true)
                                            Log.d(ChatViewModel.TAG, "Tool call detected: $toolCallChunk")
                                            val toolNames = toolCallChunk.split(",").map { it.trim() }
                                            for (toolName in toolNames) {
                                                vm.viewModelScope.launch {
                                                    vm._toolCallState.value = ToolCallState.Executing(
                                                        toolName = toolName,
                                                        elapsedMs = System.currentTimeMillis() - lastChunkAt.get()
                                                    )
                                                    // 画像生成以外のツール実行時は、前回の画像生成進捗をクリア
                                                    if (!toolName.equals("generate_image", ignoreCase = true)) {
                                                        vm._imageGenProgress.value = null
                                                    }
                                                }
                                                val executingMsg = when (toolName) {
 "set_alarm"-> vm.appContext.getString(R.string.tool_exec_set_alarm)
 "send_message"-> vm.appContext.getString(R.string.tool_exec_send_message)
 "search"-> vm.appContext.getString(R.string.tool_exec_search)
 else -> vm.appContext.getString(R.string.tool_exec_generic, toolName)
                                                }
                                                Log.d(ChatViewModel.TAG, "Tool execution started: $toolName")
                                            }
                                        }
                                        toolResultChunk != null -> {
                                            toolCallInProgress.set(false)
                                            Log.d(ChatViewModel.TAG, "Tool result received: $toolResultChunk")
                                            val parts = toolResultChunk.split(":", limit = 2)
                                            if (parts.size >= 2) {
                                                val toolName = parts[0].trim()
                                                val status = parts[1].trim()
                                                vm.viewModelScope.launch {
                                                    vm._toolCallState.value = ToolCallState.Result(
                                                        toolName = toolName,
                                                        status = if (status.contains("success", ignoreCase = true)) "success" else "error",
                                                        resultMessage = status
                                                    )
                                                }
                                                val resultMsg = when (status) {
 "success"-> vm.appContext.getString(R.string.tool_exec_success, toolName)
 "error"-> vm.appContext.getString(R.string.tool_exec_failed, toolName)
 else -> "$toolName: $status"
                                                }
                                                Log.d(ChatViewModel.TAG, "Tool execution completed: $toolName status=$status")
                                            }
                                        }
                                        toolResults != null -> {
                                            toolCallInProgress.set(false)
                                            if (toolResults != "[]") {
                                                toolResultsJson = toolResults
                                                // バグ修正 (生成中にツール結果がカードに見えない):
                                                //   以前は finalFromModelGlobal != null の persist しか toolResultsJson を DB に
                                                //   写さなかったため、モデルが最終回答を吐き終えるまでカードを展開しても
                                                //   result 行が “(モデルへ送信済み)” のプレースホルダーのままだった。
                                                //   ラウンド完了ごとに エンジン側 (Gguf/LiteRt) が送ってくるため、ここで即剋に
                                                //   toolResultsJson だけを preserve で persist する (content は他パスの
                                                //   タイミングで写かれるのでここでは触らない)。
                                                vm.viewModelScope.launch(Dispatchers.IO) {
                                                    runCatching {
                                                        vm.messageRepository.updateToolResultsJson(
                                                            messageId = activeStreamingMessageId,
                                                            toolResultsJson = toolResults
                                                        )
                                                    }.onFailure {
                                                        Log.w(ChatViewModel.TAG, "live updateToolResultsJson failed id=$activeStreamingMessageId", it)
                                                    }
                                                }
                                            }
                                            Log.d(ChatViewModel.TAG, "Tool results JSON received: length=${toolResults.length}")
                                        }
                                        tpsValue != null -> {
                                            // エンジン送りの TPS マーカーもクラウドでは無視する
                                            if (!isCloudStreamModel) {
                                                vm._currentTps.value = tpsValue
                                            }
                                        }
                                        else -> {
                                            val executedToolsList = InferenceStreamProtocol.decodeExecutedToolsList(seg)
                                            if (executedToolsList != null) {
                                                Log.d(ChatViewModel.TAG, "Executed tools list: $executedToolsList")
                                                if (executedToolsList.isNotEmpty()) {
                                                    val toolsDisplay = executedToolsList.joinToString(", ")
                                                }
                                            } else if (seg.isNotEmpty()) {
                                                if (vm._toolCallState.value is ToolCallState.Result) {
                                                    vm._toolCallState.value = ToolCallState.Responding
                                                }
                                                val currentContent = answerBuilder.toString()
                                                if (BuildConfig.DEBUG) {
                                                    // プライバシー保護: 生成中テキストはユーザー由来の内容を含み得るため本文は出力しない
                                                    Log.d(ChatViewModel.TAG, "RAW_CHUNK: length=${seg.length}")
                                                }
                                                val merged = vm.mergeStreamingChunk(currentContent, seg)
                                                if (merged != currentContent && merged.length >= currentContent.length) {
                                                    answerBuilder.clear()
                                                    answerBuilder.append(merged)
                                                    // Bug fix(#6): 以前は seg 全体を estimateOutputTokens に渡していたため、
                                                    // LiteRT から「累積テキスト」が送られたケースで token 数が過大計上されていた。
                                                    // merged と currentContent の実際の差分文字列を使ってトークン数を加算し、
                                                    // TPS 表示を実態に一致させる。
                                                    val deltaText = merged.substring(currentContent.length)
                                                    tokenCount += TextTokenEstimator.estimateOutputTokens(deltaText)
 // 本文の最初のトークン到達時刻を記録。既にシンキングフェーズに入っていたら
                                                    // その差分を thinkingElapsedMs に一度だけ収めて、シンキングフェーズを閉じる。
                                                    if (firstAnswerAtMs == null) {
                                                        firstAnswerAtMs = chunkArrivedAt
                                                        thinkingStartedAtMs?.let { start ->
                                                            val diff = chunkArrivedAt - start
                                                            if (diff > 0L) thinkingElapsedMs += diff
                                                            thinkingStartedAtMs = null
                                                        }
                                                    }
                                                    // 修正: ライブ TPS は Thinking を含む全デコード区間
                                                    //   (最初の出力トークンからの経過) で算出する。
                                                    if (tokenCount >= 10f && firstOutputAtMs != null && !isCloudStreamModel) {
                                                        val elapsed = (SystemClock.elapsedRealtime() - firstOutputAtMs!!)
                                                            .coerceAtLeast(1L)
                                                        vm._currentTps.value = (tokenCount * 1000f) / elapsed
                                                    }
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            ChatViewModel.TAG,
                                                            "Chunk merged: ${currentContent.length} -> ${merged.length} chars (added ${merged.length - currentContent.length} chars)"
                                                        )
                                                    }
                                                    if (merged.length - currentContent.length != seg.length) {
 Log.w(ChatViewModel.TAG, "OVERLAP DETECTED: chunk=${seg.length} chars, but added only ${merged.length - currentContent.length} chars")
                                                    }
                                                } else if (merged.length < currentContent.length) {
 Log.w(ChatViewModel.TAG, "Chunk merge would shrink content: ${currentContent.length} -> ${merged.length}, skipping merge")
                                                    if (BuildConfig.DEBUG) {
                                                        Log.w(ChatViewModel.TAG, "  original chunk: '${seg.take(80)}'")
                                                        Log.w(ChatViewModel.TAG, "  current: '${currentContent.take(80)}'")
                                                        Log.w(ChatViewModel.TAG, "  merged: '${merged.take(80)}'")
                                                    }
                                                } else if (merged == currentContent) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(ChatViewModel.TAG, "DUPLICATE_CHUNK: skipped (already present)")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                val messageIdToUpdate = streamingMessageId ?: activeStreamingMessageId
                                messageIdToUpdate?.let { id ->
                                    var contentForUi: String
                                    var thinkingForUi: String?
                                    if (nativeThinkingStream) {
                                        contentForUi =
                                            vm.sanitizeAssistantOutputForModel(
                                                engineModelName = engineModelName,
                                                text = Gemma4ThinkingParser.sanitizeVisibleText(
                                                    answerBuilder.toString(),
                                                    preserveToolCallTags = true
                                                )
                                            )
                                        thinkingForUi =
                                            Gemma4ThinkingParser.sanitizeVisibleText(thinkingBuilder.toString())
                                                .ifBlank { null }
                                        if (BuildConfig.DEBUG && (contentForUi.isNotEmpty() || !thinkingForUi.isNullOrBlank())) {
                                            Log.d(ChatViewModel.TAG, "CONTENT_THINKING_STATE: content_len=${contentForUi.length} thinking_len=${thinkingForUi?.length ?: 0}")
                                        }
                                    } else {
                                        // GGUF かつネイティブ chat_template 適用済みのときは、
                                        // llama.cpp 側のパーサー (部分パース) を優先する。
                                        // テンプレ未適用時は null が返るため Kotlin パーサーにフォールバックする。
                                        val nativeStreamParsed =
                                            if (vm.isGgufEngineModel(engineModelName)) {
                                                runCatching {
                                                    manager.parseGgufChatOutput(
                                                        rawForThinkParse(answerBuilder.toString()),
                                                        isPartial = true
                                                    )
                                                }.getOrNull()
                                            } else {
                                                null
                                            }
                                        if (nativeStreamParsed != null) {
                                            // バグ修正 (Thinking ON 時、最初の出力が本文に混入する):
                                            //   llama.cpp のネイティブパーサーは thinking_forced_open な
                                            //   テンプレート (Gemma4 等) でも、</think> を実際に検出するまでは
                                            //   partial 解析中の reasoning_content を確定させない実装になっており
                                            //   (未閉鎖 thinking タグを許容する既知の緩さ、
                                            //   upstream issue #13812 / #13877 系)、その間に生成された
                                            //   思考テキストが reasoningContent="" のまま content 側に
                                            //   一時的に出てしまうケースがある。
                                            //   完了時 (isPartial=false) 側は reasoningContent が空のとき
                                            //   Kotlin パーサーで再解析して救済しているのに、ストリーミング中
                                            //   だけこの救済が無く、Thinking ON の最初の出力がそのまま
                                            //   本文としてユーザーに見えてしまっていた。ここでも同じ救済を行う。
                                            // thinking の救済は「生の累積テキスト」に対して行う。ネイティブ partial 解析
                                            // が返す content には <think> が閉じる前の時点で既に思考冒頭が混入している
                                            // 場合があり、content だけ再解析しても取りこぼすため。
                                            val nativeReasoningBlank = nativeStreamParsed.reasoningContent.isBlank()
                                            val rawForNative = answerBuilder.toString()
                                            val unmarkedThinkingPhase =
                                                config.enableThinking &&
                                                    !markupSpec.containsThinkingClose(rawForNative)
                                            // 閉じタグがまだ無いあいだは llama.cpp の content を信じない。
                                            // Granite はタグを出さず reasoningContent="" / content=全文 になる。
                                            if (config.enableThinking &&
                                                answerBuilder.isNotEmpty() &&
                                                (nativeReasoningBlank || unmarkedThinkingPhase)
                                            ) {
                                                val salvaged = Gemma4ThinkingParser.parseStreaming(
                                                    rawInput = rawForThinkParse(answerBuilder.toString()),
                                                    treatUnmarkedInputAsThinking = treatUnmarkedAsThinking(
                                                        rawForThinkParse(answerBuilder.toString()),
                                                        streaming = true
                                                    ),
                                                    preserveToolCallTags = true,
                                                    spec = markupSpec,
                                                )
                                                contentForUi =
                                                    vm.sanitizeAssistantOutputForModel(
                                                        engineModelName = engineModelName,
                                                        text = salvaged.answer
                                                    )
                                                thinkingForUi =
                                                    salvaged.thinking?.let {
                                                        Gemma4ThinkingParser.sanitizeVisibleText(it)
                                                    }?.ifBlank { null }
                                            } else {
                                                contentForUi =
                                                    vm.sanitizeAssistantOutputForModel(
                                                        engineModelName = engineModelName,
                                                        text = Gemma4ThinkingParser.sanitizeVisibleText(
                                                            nativeStreamParsed.content,
                                                            preserveToolCallTags = true
                                                        )
                                                    )
                                                thinkingForUi =
                                                    Gemma4ThinkingParser.sanitizeVisibleText(
                                                        nativeStreamParsed.reasoningContent
                                                    ).ifBlank { null }
                                            }
                                        } else {
                                        // LiteRT-LM の channel 推測は nativeThinkingStream 分岐に任せる。
                                        // GGUF かつ Thinking ON のときだけ、開始タグなしでも閉じタグまで thinking へ先流しする。
                                        val rawAccum = rawForThinkParse(answerBuilder.toString())
                                        val parsedStream =
                                            Gemma4ThinkingParser.parseStreaming(
                                                rawInput = rawAccum,
                                                treatUnmarkedInputAsThinking = treatUnmarkedAsThinking(
                                                    rawAccum,
                                                    streaming = true
                                                ),
                                                preserveToolCallTags = true,
                                                spec = markupSpec,
                                            )
                                        // Instant / Thinking OFF 中でも、モデルが実際に <think> を吐いた場合は
                                        // それを捨てずに UI へ表示する。本文側は従来どおり visible answer のみを使う。
                                        val extractedThinking = parsedStream.thinking?.let {
                                            Gemma4ThinkingParser.sanitizeVisibleText(it)
                                        }?.ifBlank { null }
                                        if (config.enableThinking) {
                                            contentForUi =
                                                vm.sanitizeAssistantOutputForModel(
                                                    engineModelName = engineModelName,
                                                    text = parsedStream.answer
                                                )
                                            thinkingForUi = extractedThinking
                                        } else {
                                            // Thinking OFF 中は本文から <think> ブロックだけ除去し、
                                            // もしモデルが思考を漏らしたら disclosure 側へそのまま載せる。
                                            val rawNoThink = vm.stripThinkSectionsForDisplay(answerBuilder.toString())
                                            contentForUi =
                                                vm.sanitizeAssistantOutputForModel(
                                                    engineModelName = engineModelName,
                                                    text = Gemma4ThinkingParser.sanitizeVisibleText(
                                                        rawNoThink,
                                                        preserveToolCallTags = true
                                                    )
                                                )
                                            thinkingForUi = extractedThinking
                                        }
                                        }
                                    }
                                    // バグ修正 (ツールカードが応答完了まで出ない):
                                    //   ネイティブ partial パーサーはツールコール専用出力に対して
                                    //   content="" を返し続ける (Qwen3.5 GGUF のログで
                                    //   STREAM_INMEMORY_UPDATE contentLen=0 が <tool_call> 生成中ずっと継続)。
                                    //   インラインカードは message.content のタグを parseSegments して描くため、
                                    //   未完の <tool_call> も contentForUi に残す。タグ開始時点で Running、
                                    //   ツール名が読めた時点でタイトルが埋まる。
                                    val rawForCards = answerBuilder.toString()
                                    val thinkingOnlyNoClose =
                                        config.enableThinking &&
                                            contentForUi.isEmpty() &&
                                            !thinkingForUi.isNullOrBlank() &&
                                            !markupSpec.containsThinkingClose(rawForCards)
                                    // 思考中（閉じタグなし）の本文埋め戻しはしない。
                                    // ツールカード用の復元は実際に tool マークアップがあるときだけ。
                                    if (contentForUi.isEmpty() &&
                                        !thinkingOnlyNoClose &&
                                        vm.hasStreamingToolCallMarkup(rawForCards)
                                    ) {
                                        contentForUi = vm.restoreStreamingContentForToolCards(
                                            engineModelName = engineModelName,
                                            rawAnswer = rawForCards
                                        )
                                    }
                                    if (config.enableThinking) {
                                        val dedupedLive = ThinkingLeakSalvage.stripDuplicateThinkingFromContent(
                                            thinking = thinkingForUi,
                                            content = contentForUi,
                                        )
                                        thinkingForUi = dedupedLive.first
                                        contentForUi = dedupedLive.second
                                    }

                                    lastStreamContentForFinal = contentForUi
                                    lastStreamThinkingForFinal = thinkingForUi
                                    if (contentForUi.isNotBlank() &&
                                        !thinkingForUi.isNullOrBlank() &&
                                        contentForUi.trim() != thinkingForUi.trim()
                                    ) {
                                        lastGoodStreamContent = contentForUi
                                        lastGoodStreamThinking = thinkingForUi
                                    }

                                    val now = SystemClock.elapsedRealtime()
                                    val persistInterval = if (vm.isLikelyMarkdownTable(contentForUi)) {
                                        ChatViewModel.STREAM_PERSIST_INTERVAL_TABLE_MS
                                    } else {
                                        ChatViewModel.STREAM_PERSIST_INTERVAL_MS
                                    }
                                    val isFirstVisibleContent =
                                        contentForUi.isNotEmpty() && lastPersistedContent.isEmpty()
                                    val isFirstThinkingPersist =
                                        !thinkingForUi.isNullOrBlank() && lastPersistedThinking.isNullOrBlank()
                                    // Thinking フェーズのみで content が空の場合は persist を遅延させる
                                    // （content が来た時、または最終確定時のみ persist する）
                                    val isThinkingOnlyPhase = contentForUi.isEmpty() && !thinkingForUi.isNullOrBlank()
                                    // Bug fix (開始タグを出さないモデルで Thinking 欄と本文欄が高速で入れ替わる):
                                    //   Qwen 3.5 系は開始タグを省略して思考本文だけを吐くため、終端タグ到達までは
                                    //   チャンクが全て content 側として persist される。終端タグ到達後にパーサーが
                                    //   全文を thinking 側へ移し替えると contentForUi は空に戻るが、そのまま
                                    //   空 content を persist すると、Room Flow が「思考=本文」「思考=Thinking欄」の
                                    //   スナップショットを交互に再配信し、カードの高さが伸び縮みを繰り返して見える。
                                    //   Thinking のみのフェーズでは空 content への置き換えを DB へ書かず、
                                    //   最終的な退避は finally の ThinkingLeakSalvage 経路に任せる。
                                    val suppressThinkingOnlyEmptyContentPersist =
                                        isThinkingOnlyPhase && lastPersistedContent.isNotEmpty()
 // Bug fix(#Thinking-Realtime-1):
                                    //   旧実装は Thinking のみのフェーズ中は shouldPersistToDb=false で in-memory 更新のみ
                                    //   行っていたが、その直後に Room の messagesCollectionJob 側の Flow が古い
                                    //   スナップショット (thinkingContent=null, isStreaming=true) を配信してきて、
                                    //   in-memory 更新をすぐ上書きしていたため、UI にリアルタイム表示されない
                                    //   ケースがあった。 Thinking の更新も定期的に DB へ persist することで、
                                    //   Flow 経由の再配信でも Thinking 内容を保持する。
                                    val thinkingChangedSinceLastPersist =
                                        thinkingForUi != lastPersistedThinking
                                    val shouldPersistToDb =
                                        if (suppressThinkingOnlyEmptyContentPersist) {
                                            false
                                        } else if (isThinkingOnlyPhase) {
                                            // Thinking のみでも、初回、または一定間隔経過、または最終確定時は persist する。
                                            thinkingChangedSinceLastPersist && (
                                                finalFromModelGlobal != null ||
                                                    isFirstThinkingPersist ||
                                                    now - lastPersistAt >= persistInterval
                                            )
                                        } else {
                                            // Content が存在すれば通常の persist ロジック。
                                            // Bug fix(#streaming-persist-debounce):
                                            //   トークン1個ごとに DB 更新・Flow 再発行・再バインドが
                                            //   走っていたため、定期 persist は「一定時間経過」かつ
                                            //   「本文が STREAM_PERSIST_MIN_CHARS 以上増えたか
                                            //   Thinking が変化した」ときだけに間引く。
                                            (contentForUi != lastPersistedContent ||
                                                thinkingForUi != lastPersistedThinking) &&
                                                (finalFromModelGlobal != null ||
                                                    isFirstVisibleContent ||
                                                    isFirstThinkingPersist ||
                                                    (now - lastPersistAt >= persistInterval &&
                                                        (contentForUi.length - lastPersistedContent.length >= ChatViewModel.STREAM_PERSIST_MIN_CHARS ||
                                                            thinkingForUi != lastPersistedThinking)))
                                        }
                                    if (shouldPersistToDb) {
                                        vm.messageRepository.updateMessageContent(
                                            messageId = id,
                                            content = contentForUi,
                                            isStreaming = finalFromModelGlobal == null,
                                            thinkingContent = thinkingForUi,
                                            toolResultsJson = toolResultsJson
                                        )
                                        lastPersistedContent = contentForUi
                                        lastPersistedThinking = thinkingForUi
                                        lastPersistAt = now
                                    }
 // Bug fix(#Thinking-Realtime-2):
                                    //   persist 間隔の合間でも UI へは即座に反映する必要があるため、
                                    //   Thinking フェーズかどうかに関わらず _messages を in-memory 更新する。
                                    //   Room Flow 再配信で古いスナップショットが届いても、次の persist で
                                    //   thinkingContent が保存されているため上書きされない。
                                    if (!shouldPersistToDb) {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                ChatViewModel.TAG,
                                                "STREAM_INMEMORY_UPDATE: id=$id contentLen=${contentForUi.length} " +
                                                    "thinkingLen=${thinkingForUi?.length ?: 0} thinkingOnly=$isThinkingOnlyPhase"
                                            )
                                        }
                                        val currentMsgs = vm._messages.value.toMutableList()
                                        val idx = currentMsgs.indexOfFirst { it.id == id }
                                        if (idx >= 0) {
                                            // persist 間引き中も content / thinkingContent を UI へ即時反映する。
                                            // isStreaming=true を維持しないと MessageAdapter が生成完了と誤認し、
                                            // Thinking トグル行が出てしまう。
                                            val original = currentMsgs[idx]
                                            val updated = original.copy(
                                                content = contentForUi,
                                                thinkingContent = thinkingForUi,
                                                isStreaming = true
                                            )
                                            currentMsgs[idx] = updated
                                            vm._messages.value = currentMsgs.toList()
                                        } else if (BuildConfig.DEBUG) {
                                            Log.w(ChatViewModel.TAG, "STREAM_INMEMORY_UPDATE: could not find message id=$id in ${currentMsgs.map { it.id }}")
                                        }
                                    }
                                }
                                if (BuildConfig.DEBUG) Log.d(ChatViewModel.TAG, "Received chunk: $chunk")
                            }
                        } finally {
                            generationEndAtMs = SystemClock.elapsedRealtime()
                            firstTokenTimeoutJob.cancel()
                            stallWatchJob.cancel()
                            Log.d(ChatViewModel.TAG, "Flow collection completed")
                        }
                    }
                }
            } catch (collectionError: Throwable) {
                when {
                    collectionError is ChatViewModel.FirstTokenTimeoutException -> {
                        Log.d(ChatViewModel.TAG, "First token timeout during flow collection")
                    }
                    collectionError is ChatViewModel.GenerationStalledException -> {
                        Log.w(ChatViewModel.TAG, "Generation stalled (no chunks); finalizing partial", collectionError)
                        streamAbortNote =
                            vm.appContext.getString(R.string.gen_truncated_stall_notice)
                        withContext(Dispatchers.Main) {
 vm._uiMessage.emit(vm.appContext.getString(R.string.vm_response_paused_saved))
                        }
                        // バグ修正 (LiteRT-LM: ストール後も次回送信で再度ハングする):
                        //   cancelInference()/cancelProcess() は upstream の完全デッドロック状態には
                        //   効かないことがある (google-ai-edge/LiteRT-LM#2202)。stallWatchJob が
                        //   検知した時点で :litert プロセスごと強制終了し、次回送信では
                        //   確実に新しい Conversation で再スタートさせる。GGUF はこの経路の対象外
                        //   (recoverFromStalledLiteRtInference 内で isLiteRtEngine 相当の判定を行う)。
                        if (isLiteRtEngine) {
                            runCatching {
                                vm.requireModelManager().recoverFromStalledLiteRtInference()
                            }.onFailure {
                                Log.w(ChatViewModel.TAG, "recoverFromStalledLiteRtInference failed after stall", it)
                            }
                        }
                    }
                    collectionError is ChatViewModel.GenerationWallTimeoutException -> {
                        Log.w(ChatViewModel.TAG, "Generation wall timeout; finalizing partial", collectionError)
                        streamAbortNote =
                            vm.appContext.getString(R.string.gen_truncated_time_notice)
                        withContext(Dispatchers.Main) {
 vm._uiMessage.emit(vm.appContext.getString(R.string.vm_response_gen_time_limit))
                        }
                        // 上と同じ理由。壁時計タイムアウト側でも念のため回復させる。
                        if (isLiteRtEngine) {
                            runCatching {
                                vm.requireModelManager().recoverFromStalledLiteRtInference()
                            }.onFailure {
                                Log.w(ChatViewModel.TAG, "recoverFromStalledLiteRtInference failed after wall timeout", it)
                            }
                        }
                    }
                    collectionError is UserStopCancellationException -> {
                        collectionCancelledByUser = true
                        Log.d(ChatViewModel.TAG, "Flow collection was cancelled by user stop")
                    }
                    else -> {
                        Log.e(ChatViewModel.TAG, "Error during flow collection", collectionError)
                        throw collectionError
                    }
                }
            }

            val completeResponse: String
            val finalThinking: String?
            // 生成中と同じ splitter で raw を再分割する。sanitize は分割後にだけかける。
            val finalParsed = Gemma4ThinkingParser.parse(
                rawInput = rawForThinkParse(answerBuilder.toString()),
                treatUnmarkedInputAsThinking = treatUnmarkedAsThinking(
                    rawForThinkParse(answerBuilder.toString()),
                    streaming = false
                ),
                preserveToolCallTags = true,
                spec = markupSpec,
            )
            val previousThinkingForFinal =
                lastGoodStreamThinking?.takeIf { lastGoodStreamContent.isNotBlank() }
                    ?: lastStreamThinkingForFinal
            val previousContentForFinal =
                lastGoodStreamContent.takeIf { it.isNotBlank() }
                    ?: lastStreamContentForFinal
            val restoredMerged = ThinkingLeakSalvage.restoreSeparatedThinkingIfFinalMerged(
                previousThinking = previousThinkingForFinal,
                previousContent = previousContentForFinal,
                newThinking = finalParsed.thinking,
                newContent = finalParsed.answer,
            )
            val restored = ThinkingLeakSalvage.restoreUnmarkedAnswerIfNoThinkBoundary(
                thinking = restoredMerged.first,
                content = restoredMerged.second,
                raw = rawForThinkParse(answerBuilder.toString()),
                spec = markupSpec,
                implicitPrefill = implicitThinkPrefill || promptOpenedThink,
                keepUnmarkedAsThinking = config.enableThinking,
            )
            val deduped = ThinkingLeakSalvage.stripDuplicateThinkingFromContent(
                thinking = restored.first,
                content = restored.second,
            )
            // 完了時に再パース結果でストリーム表示を丸ごと差し替えない。
            // 差分がある場合でもストリーム中に見えていた本文を優先し、ガクつきを防ぐ。
            val streamedThinking = (
                lastGoodStreamThinking?.takeIf { it.isNotBlank() }
                    ?: lastStreamThinkingForFinal?.takeIf { it.isNotBlank() }
                    ?: deduped.first
                )
                ?.let { Gemma4ThinkingParser.sanitizeVisibleText(it) }
                ?.ifBlank { null }
            val streamedContent = lastGoodStreamContent.takeIf { it.isNotBlank() }
                ?: lastStreamContentForFinal.takeIf { it.isNotBlank() }
                ?: deduped.second
            completeResponse = vm.sanitizeAssistantOutputForModel(
                engineModelName = engineModelName,
                text = Gemma4ThinkingParser.sanitizeVisibleText(
                    if (config.enableThinking) streamedContent
                    else vm.stripThinkSectionsForDisplay(streamedContent),
                    preserveToolCallTags = true
                ).ifBlank {
                    // Thinking ON で思考側に本文相当が既にあるなら、空の本文へ
                    // lastPersistedContent を埋め戻して重複表示しない。
                    if (config.enableThinking && !streamedThinking.isNullOrBlank()) ""
                    else lastPersistedContent
                }
            )
            finalThinking = streamedThinking
            // 完了前の停止で、タグの無い途中出力を Thinking 枠へ入れない。
            val stoppedThinking = if (!collectionCancelledByUser) {
                finalThinking
            } else if (markupSpec.containsThinkingOpen(answerBuilder.toString()) ||
                markupSpec.containsThinkingOpen(finalThinking.orEmpty())
            ) {
                finalThinking
            } else {
                null
            }
            val note = streamAbortNote
            val stoppedWithoutPayload =
                collectionCancelledByUser && completeResponse.isEmpty() && stoppedThinking.isNullOrEmpty()
            val stoppedDuringThinkingOnly =
                collectionCancelledByUser && completeResponse.isEmpty() && !stoppedThinking.isNullOrEmpty()
            val contentToSave =
                when {
                    stoppedWithoutPayload -> ""  // 空の場合は空文字列を保存（後でフォールバックメッセージに置換）
                    stoppedDuringThinkingOnly -> vm.appContext.getString(R.string.assistant_stopped_by_user)
                    note == null -> completeResponse
                    completeResponse.isNotEmpty() -> completeResponse + note
                    else -> note.trim()
                }

 // ユーザー停止時はツール実行結果カードとして保存
            val finalToolResultsJson =
                if (collectionCancelledByUser) vm.withUserStopCard(toolResultsJson) else toolResultsJson

            val hasPayload =
                contentToSave.isNotEmpty() || !stoppedThinking.isNullOrEmpty()

            Log.d(ChatViewModel.TAG, "generateAIResponse finalization: hasPayload=$hasPayload, activeStreamingMessageId=$activeStreamingMessageId, completeResponse.len=${completeResponse.length}, finalThinking=${!finalThinking.isNullOrEmpty()}")

 // TTFT: 送信・推論開始から最初の (シンキングも含む) トークンまでの時間。
            //   generationStartAtMs 相当のベースラインとして requestStartedAtMs を参照したいが、
            //   当スコープでは firstOutputAtMs を得ているのでストリーミング開始 (streamStartedAtMs) を別途使う。
            //   ここでは streamStartedAtMs の代わりに activeStreamingMessageId 取得直後の基準時刻を →
            //   値がなければ firstOutputAtMs 相当を利用 (この場合 TTFT = 0)。
            val ttftMs: Long? = firstOutputAtMs?.let { first ->
                val start = streamStartedAtMs
                if (start != null && first >= start) (first - start).coerceAtLeast(0L) else null
            }
 // 修正: Thinking も生成トークンとして計上する方針に統一したため、
            //   分母の生成時間も Thinking を含む全デコード区間
            //   (最初の出力トークン → 終了) とする。
            //   (旧: Thinking 時間を差し引いていたが、分子のトークン数に Thinking が
            //    含まれていなかったため分子分母が不一致で TPS が不正確だった)
            val generationTimeMs = firstOutputAtMs?.let { first ->
                val end = generationEndAtMs ?: SystemClock.elapsedRealtime()
                (end - first).coerceAtLeast(0L)
            }
            // トークン速度正確化:
            //   LiteRT-LM は getBenchmarkInfo() の実測デコード TPS / トークン数を優先する。
            //   (enableBenchmark=true で初期化済み。推論完了時にエンジン側が記録する)
            //   文字数ヒューリスティック (TextTokenEstimator) より正確。
            // クラウドモデルは LiteRT-LM ではないため、実測ベンチマーク問い合わせの対象外。
            // (以前はクラウドも「非 GGUF = LiteRT」と誤判定され、Binder 経由で LiteRT 側の
            //  前回ベンチマーク値をクラウド応答の TPS として拾う可能性があった)
            val isCloudModelHere =
                com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(engineModelName)
            val isLiteRtEngineHere = !vm.isGgufEngineModel(engineModelName) && !isCloudModelHere
            val litertExactTps = if (isLiteRtEngineHere) manager.getLastDecodeTpsSync() else null
            val litertExactDecodeTokens = if (isLiteRtEngineHere) manager.getLastDecodeTokenCountSync() else null
            val tps = if (generationTimeMs != null && generationTimeMs > 0L) {
                val tokensAfterFirst = if (vm.isGgufEngineModel(engineModelName)) {
                    val nativeTokens = manager.getLastGenerationTokenCount()
                    (nativeTokens?.minus(1f))?.coerceAtLeast(0f)
                        ?: (tokenCount - 1f).coerceAtLeast(0f).takeIf { tokenCount > 0f }
                        ?: (TextTokenEstimator.estimateOutputTokens(
                            completeResponse + (finalThinking ?: "")
                        ) - 1f).coerceAtLeast(0f)
                } else {
                    // LiteRT-LM: 実測デコードトークン数 (Thinking 含む) を優先、
                    //   なければストリーム計上値 (Thinking 含む)、最後に文字数推定
                    litertExactDecodeTokens?.let { (it - 1).toFloat().coerceAtLeast(0f) }
                        ?: (tokenCount - 1f).coerceAtLeast(0f).takeIf { tokenCount > 0f }
                        ?: (TextTokenEstimator.estimateOutputTokens(
                            completeResponse + (finalThinking ?: "")
                        ) - 1f).coerceAtLeast(0f)
                }
                if (tokensAfterFirst > 0f) {
                    tokensAfterFirst * 1000f / generationTimeMs
                } else {
                    null
                }
            } else {
                null
            }
            // LiteRT-LM: ネイティブ実測 TPS が取れた場合はそちらを優先する
            // クラウドモデルでは TPS を表示しない。ネットワーク往復・レート制限待ちを含む
            // 見かけ速度はオンデバイスの tok/s と意味が異なり誤解を招くため null を保存する。
            val finalTps = if (isCloudModelHere) null else (litertExactTps ?: tps)

            Log.d(ChatViewModel.TAG, "Inference collection completed: hasPayload=$hasPayload, completeResponse.length=${completeResponse.length}, finalThinking=${!finalThinking.isNullOrEmpty()}, generationTimeMs=$generationTimeMs, tps=$tps finalTps=$finalTps")

            // コンテキストメーター正確化: 推論完了時点の KV 実測値 (画像・音声含む) で更新し、
            // セッションに永続化する (アプリ再起動後の復元用)。
            // クラウドモデルではローカル KV の実測値は存在しない。前回ローカル推論の
            // 残り値をクラウドセッションへ誤って保存すると、セッション移動時に
            // 別セッションのコンテキスト量がメーターへ表示される原因になるため保存しない。
            val isCloudCompletion =
                com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(engineModelName)
            if (!isCloudCompletion) runCatching {
                val exactContextTokens = manager.getCurrentContextTokenCountSync()
                val mediaTokens = manager.getLastPromptTokenInfoSync()?.second ?: vm._contextMediaTokens.value
                // バグ修正 (Thinking がコンテキストメーターに含まれない):
                //   KV 実測値には Thinking チャンネルのデコード分が含まれないため、
                //   最終 thinkingContent を推定トークン化して加算し、実際のセッション
                //   コンテキスト量に近づける。
                val thinkingTokens =
                    TextTokenEstimator.estimateOutputTokens(finalThinking ?: "").toInt().coerceAtLeast(0)
                val generatedEstimate = TextTokenEstimator.estimateOutputTokens(
                    completeResponse + (finalThinking ?: "")
                ).toInt().coerceAtLeast(0)
                val decodeTokens = if (isLiteRtEngineHere) {
                    manager.getLastDecodeTokenCountSync()?.takeIf { it > 0 }
                } else {
                    null
                }
                val generatedTokens = decodeTokens ?: generatedEstimate
                val prefillTokens = if (isLiteRtEngineHere) {
                    manager.getLastPromptTokenInfoSync()?.first?.takeIf { it > 0 }
                } else {
                    null
                }
                // llama.cpp は n_past がプロンプト以下のとき生成分を含んでいない。
                // LiteRT-LM は getTokenCount が取れない回があるので prefill+decode に落とす。
                val totalTokens = when {
                    exactContextTokens != null && exactContextTokens > 0 &&
                        (prefillTokens == null || exactContextTokens > prefillTokens) ->
                        exactContextTokens + thinkingTokens
                    prefillTokens != null -> prefillTokens + generatedTokens
                    exactContextTokens != null && exactContextTokens > 0 ->
                        exactContextTokens + generatedTokens
                    else -> vm._contextUsageTokens.value + generatedTokens
                }
                if (vm.isCurrentContextSession(sessionId)) {
                    vm.applyMeterAfterSend(
                        manager,
                        engineModelName,
                        totalTokens,
                        mediaTokens,
                        generatedTokens
                    )
                }
                // メーター計算はプロセス内だけ。アプリを閉じたら捨てる。
                Log.d(ChatViewModel.TAG, "CONTEXT_METER: exact context tokens=$exactContextTokens (media=$mediaTokens generated=$generatedTokens) session=$sessionId")
            }.onFailure { Log.w(ChatViewModel.TAG, "persist context tokens failed", it) }

            val finalizationContext =
                if (collectionCancelledByUser) Dispatchers.IO + NonCancellable else Dispatchers.IO

            if (hasPayload) {
                withContext(finalizationContext) {
                    Log.d(ChatViewModel.TAG, "Updating message content with final response")
                    vm.messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = (contentToSave).ifBlank {
                        // Thinking ON で思考だけ出た場合は raw を本文へ埋め戻さない。
                        // Granite 等はタグが無いので stripThink しても全文が本文になり、
                        // thinkingContent と二重表示される。
                        if (config.enableThinking && !stoppedThinking.isNullOrBlank() && !collectionCancelledByUser) {
                            ""
                        } else {
                            vm.stripThinkSectionsForDisplay(answerBuilder.toString()).trim()
                        }
                    },
                        isStreaming = false,
                        thinkingContent = if (collectionCancelledByUser) stoppedThinking else finalThinking,
                        toolResultsJson = finalToolResultsJson,
                        generationTps = finalTps,
                        generationTimeMs = generationTimeMs,
                        ttftMs = ttftMs
                    )
                    normalCompletionPersisted = true
                    if (!finalToolResultsJson.isNullOrBlank() && finalToolResultsJson != "[]") {
                        runCatching {
                            val db = NezumiAiDatabase.getInstance(vm.appContext)
                            ToolCallHistoryRepository(
                                db.toolCallHistoryDao(),
                                db.chatSessionDao()
                            ).recordFromToolResultsJson(
                                sessionId = sessionId,
                                messageId = activeStreamingMessageId,
                                toolResultsJson = finalToolResultsJson
                            )
                        }.onFailure { e ->
                            Log.w(ChatViewModel.TAG, "Failed to record tool call history", e)
                        }
                    }
                    Log.d(ChatViewModel.TAG, "Message content update complete")
                }
                // Bug fix(#finalization-jank): 以前はタイトル生成・タイトル再読み込み・
                //   メモリ抽出投入を本文確定と直列で実行しており、推論完了直後の
                //   全文確定 + 再描画と重なって UI が一瞬固まっていた。メッセージ本文の
                //   確定 (上の updateMessageContent) だけを同期的に待ち、残りは確定描画の
                //   後追いとなる別コルーチンへ遅延させる。
                vm.viewModelScope.launch(Dispatchers.IO) {
                    if (contentToSave.isNotEmpty() && !stoppedWithoutPayload) {
                        Log.d(ChatViewModel.TAG, "Generating session title")
                        vm.maybeGenerateSessionTitle(sessionId, userMessage, contentToSave)
                        Log.d(ChatViewModel.TAG, "Session title generation complete")
                    }
                    vm.syncSessionTitleFromDb(sessionId)
                    Log.d(ChatViewModel.TAG, "Session title sync complete")
                    if (!stoppedWithoutPayload) {
                        vm.enqueueMemoryExtraction(sessionId)
                        Log.d(ChatViewModel.TAG, "Memory extraction enqueued")
                    }
                }
                if (BuildConfig.DEBUG) {
                    // プライバシー保護: AI応答は記憶内容等を含み得るため本文は出力しない
                    Log.d(ChatViewModel.TAG, "AI response saved to database: length=${completeResponse.length}")
                }
            } else {
                Log.w(ChatViewModel.TAG, "No payload generated, saving default message")
                // ユーザー停止で何も出力が無かった場合は「生成を停止しました」を表示し、
                // 空出力エラーダイアログは表示しない。
                val emptyExplanation = if (collectionCancelledByUser) {
                    vm.appContext.getString(R.string.assistant_stopped_by_user)
                } else {
                    vm.messageForEmptyInferencePayload(hasMediaInput, engineModelName)
                }
                if (!collectionCancelledByUser) {
                    withContext(Dispatchers.Main) {
                        vm._modelErrorDialogMessage.value = vm.formatModelErrorDialogMessage(
                            title = vm.appContext.getString(R.string.assistant_error_empty_output_title),
                            message = emptyExplanation,
                            details = if (engineModelName.isNotBlank()) vm.appContext.getString(R.string.model_error_detail_with_engine, engineModelName) else null
                        )
                    }
                }
                withContext(finalizationContext) {
                    vm.messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = emptyExplanation,
                        isStreaming = false,
                        thinkingContent = null
                    )
                    normalCompletionPersisted = true
                    Log.d(ChatViewModel.TAG, "Empty payload message saved to DB")
                    vm.syncSessionTitleFromDb(sessionId)
                    Log.d(ChatViewModel.TAG, "Session title sync complete (empty payload)")
                }
            }
        } catch (t: Throwable) {
            if (t is ChatViewModel.FirstTokenTimeoutException) {
                val id = streamingMessageId
                if (id != null) {
                    vm.messageRepository.updateMessageContent(
                        messageId = id,
                        content = vm.appContext.getString(R.string.vm_response_start_timeout),
                        isStreaming = false,
                        thinkingContent = null
                    )
                    normalCompletionPersisted = true
                }
                withContext(Dispatchers.Main) {
 vm._uiMessage.emit(vm.appContext.getString(R.string.vm_response_timeout))
                }
                return
            }
            if (t is CancellationException) {
                val id = streamingMessageId
                if (id != null) {
                    withContext(Dispatchers.IO + NonCancellable) {
 // 既存の内容を取得して保存（上書きしない）
                        val current = vm.messageRepository.getMessageById(id)
                        val existingContent = current?.content?.trim() ?: ""
                        // Bug fix(#47) + Granite タグ無し:
                        //   閉じタグがある場合は従来どおり content から <think> を剥がす。
                        //   タグが無い場合は content を Thinking へ移し、すでに Thinking に
                        //   ある本文は二重に足さない。
                        val stopSpec = runCatching {
                            GgufFormatResolver.resolveMarkupSpec(
                                currentEngineModelName.orEmpty(),
                                vm.appContext
                            )
                        }.getOrDefault(ChatMarkupSpec.DEFAULT)
                        val stopEnableThinking =
                            stopSpec.containsThinkingOpen(existingContent) ||
                                stopSpec.containsThinkingOpen(current?.thinkingContent.orEmpty())
                        val (contentAfterThinkStrip, salvagedThinking) =
                            ThinkingLeakSalvage.resolveStopWithoutThinkTags(
                                persistedContent = existingContent,
                                persistedThinking = current?.thinkingContent,
                                enableThinking = stopEnableThinking,
                                spec = stopSpec,
                            )
                        val mergedThinkingRaw = salvagedThinking
                        val finalThinking = vm.closePartialThinking(mergedThinkingRaw)

                        // Bug fix(#47) 仕様変更:
                        //   シンキング中 (本文がまだ一字も出ていない状態) で停止されたときは、
                        //   本文欄には「生成を停止しました」と表示し、Thinking フェーズは普段通り
                        //   (折りたたみで見える) のままにしておく。
                        //   これによりユーザーは「どこまで思考してキャンセルされたか」を後から確認できる。
                        val stoppedDuringThinking =
                            contentAfterThinkStrip.isEmpty() && !finalThinking.isNullOrBlank()

                        val finalContent = when {
                            stoppedDuringThinking ->
                                vm.appContext.getString(R.string.assistant_stopped_by_user)
                            contentAfterThinkStrip.isNotEmpty() ->
                                // 停止時の「途中 assistant 出力」を表示上閉じるための終端補完。
                                //   コードフェンスの未閉鎖によるレンダリング崩れを防ぐ。
                                vm.closePartialAssistantContent(contentAfterThinkStrip)
                            else -> ""  // 空の場合は空文字列（後でフォールバックメッセージに置換）
                        }

                        val updatedToolResultsJson = vm.withUserStopCard(current?.toolResultsJson)

                        vm.messageRepository.updateMessageContent(
                            messageId = id,
                            content = finalContent,
                            isStreaming = false,
                            thinkingContent = finalThinking,
                            toolResultsJson = updatedToolResultsJson
                        )
                        normalCompletionPersisted = true
                    }
                }
 // 停止直後はネイティブ KV に途中トークンが残っているため、
                //   次回推論開始前に必ずクリアさせる。実際のクリアは stopGenerationInternal
                //   と、次回 inference へのエントリで二重に守られる。
                runCatching { vm.requireModelManager().requestForceClearBeforeNextInference() }
                    .onFailure { Log.w(ChatViewModel.TAG, "requestForceClearBeforeNextInference (on cancel) failed", it) }
                Log.d(ChatViewModel.TAG, "Generation cancelled: ${t.message}")
                return
            }
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(ChatViewModel.TAG, "Error generating AI response", e)

            // エラーメッセージを詳細化
            val errorMessage = when {
                e.message?.contains("Web用モデル") == true ->
                    vm.appContext.getString(R.string.web_model_unsupported)
                e.message?.contains("END header") == true || e.message?.contains("zip END header") == true ->
                    vm.appContext.getString(R.string.model_task_incomplete_download)
                e.message?.contains("ZIPファイルが破損") == true ->
                    vm.appContext.getString(R.string.model_task_corrupted)
                e.message?.contains("Unable to open zip archive") == true ->
                    vm.appContext.getString(R.string.model_file_corrupted_generic)
                e.message?.contains("ZIP archive") == true ->
                    vm.appContext.getString(R.string.model_integrity_check_failed)
                e.message?.contains("Model not loaded") == true ->
                    vm.appContext.getString(R.string.model_load_error_short)
                else -> vm.appContext.getString(R.string.model_generic_error, e.message ?: vm.appContext.getString(R.string.unknown_error))
            }
            // 従来は errorMessage を assistant レコードの content に書き込んでいたため、
            // LiteRT-LM の一部エラーが「エラーウィンドウに完全には出ず、チャット履歴に
            // 残ってしまう」現象になっていた (Snackbar は 30 文字の短い途中例外のみ)。
            // 以下の素早リターン判定に当てはまるケースだけ assistant レコードへ上書きし、
            // それ以外は DB を汚さないでモーダル (_modelErrorDialogMessage) で見せる。
            val looksLikeInferenceError = e.message?.let { msg ->
                msg.contains("Failed to invoke the compiled model", ignoreCase = true) ||
                        msg.contains("Status Code:", ignoreCase = true) ||
                        msg.contains("TF_LITE_AUX", ignoreCase = true) ||
                        msg.contains("not found", ignoreCase = true) ||
                        msg.contains("OpenCL", ignoreCase = true) ||
                        msg.contains("Vulkan", ignoreCase = true) ||
                        msg.contains("NPU", ignoreCase = true) ||
                        msg.contains("IllegalStateException", ignoreCase = true)
            } ?: false
            val id = streamingMessageId
            if (looksLikeInferenceError) {
                // ストリーミング途中の付け飛ばしレコードがあれば削除して履歴を汚さない。
                if (id != null) {
                    runCatching { vm.messageRepository.deleteMessageById(id) }
                        .onFailure { Log.w(ChatViewModel.TAG, "Failed to delete streaming error placeholder id=$id", it) }
                    normalCompletionPersisted = true
                }
                // Bug fix (Status Code: 3 tool-call パース失敗後に生成中のまま):
                //   finally でも落とすが、モーダル表示時点で同期的に UI 状態を戻す。
                //   LiteRT は壊れた Conversation が残りやすいので回復も試みる。
                vm._isLoading.value = false
                vm._toolCallState.value = null
                runCatching {
                    vm.requireModelManager().recoverFromStalledLiteRtInference()
                }.onFailure {
                    Log.w(ChatViewModel.TAG, "recoverFromStalledLiteRtInference failed after inference error", it)
                }
                withContext(Dispatchers.Main) {
                    vm._modelErrorDialogMessage.value = vm.formatModelErrorDialogMessage(
                        title = vm.appContext.getString(R.string.inference_error_title),
                        message = vm.appContext.getString(R.string.inference_error_body),
                        details = e.message
                    )
                }
            } else {
                if (id != null) {
                    vm.messageRepository.updateMessageContent(
                        messageId = id,
                        content = errorMessage,
                        isStreaming = false,
                        thinkingContent = null
                    )
                    normalCompletionPersisted = true
                } else {
                    vm.messageRepository.addMessage(
                        sessionId = sessionId,
                        role = "assistant",
                        content = errorMessage
                    )
                }
                // エラーを UI に通知
                withContext(Dispatchers.Main) {
 vm._uiMessage.emit(" "+ (e.message?.take(30) ?: vm.appContext.getString(R.string.vm_generic_error_short)))
                }
            }
        } finally {
            Log.d(ChatViewModel.TAG, "generateAIResponse finally entered")
            if (vm.heldMeterSessionId == sessionId) {
                vm.holdContextMeterDuringGeneration = false
                vm.heldMeterSessionId = null
            }
            // Ensure cleanup runs even if the coroutine job was cancelled.
            withContext(NonCancellable) {
                vm.streamingAssistantMessageIdForTools = null
                // Safety fallback: if the streaming message still exists and is still marked as streaming,
                // clear the flag so the UI does not stay stuck in "生成中".
                // Bug fix: 上記の各正常完了/エラー/キャンセルパスは既に isStreaming=false を
                //   明示的に persist 済みなので、ここでの DB 再読み込みベースの判定を通す必要が
                //   ない。normalCompletionPersisted が立っている場合はこの safety fallback を
                //   完全にスキップする。これにより、正常完了直後の DB 反映タイミングのズレで
                //   誤って ThinkingLeakSalvage が発火し、分離済みの thinking が本文へ
                //   再混入する不具合を防ぐ。この fallback はあくまで「どの完了パスも通らずに
                //   コルーチンが終わった」真の異常系だけを対象にする。
                if (!normalCompletionPersisted && streamingMessageId != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            val current = vm.messageRepository.getMessageById(streamingMessageId)
                            if (current?.isStreaming == true) {
                                Log.w(
                                    ChatViewModel.TAG,
                                    "generateAIResponse finally: message $streamingMessageId still streaming after completion, clearing flag"
                                )
                                // Bug fix (開始タグを出さないモデルの思考が本文に残る):
                                //   Qwen 3.5 系は開始タグを省略するため、ストリーミング中は
                                //   思考本文が content 側に persist される。終端タグ未到達のまま
                                //   収束した場合は content に思考が残ったままになるので、
                                //   ThinkingLeakSalvage で思考ブロックを thinkingContent 側へ退避する。
                                val stopSpec = runCatching {
                                    GgufFormatResolver.resolveMarkupSpec(
                                        currentEngineModelName.orEmpty(),
                                        vm.appContext
                                    )
                                }.getOrDefault(ChatMarkupSpec.DEFAULT)
                                val stopEnableThinking =
                                    stopSpec.containsThinkingOpen(current.content) ||
                                        stopSpec.containsThinkingOpen(current.thinkingContent.orEmpty())
                                val salvaged = ThinkingLeakSalvage.resolveStopWithoutThinkTags(
                                    persistedContent = current.content,
                                    persistedThinking = current.thinkingContent,
                                    enableThinking = stopEnableThinking,
                                    spec = stopSpec,
                                )
                                val salvagedContent = salvaged.first
                                val salvagedThinking = salvaged.second
                                vm.messageRepository.updateMessageContent(
                                    messageId = streamingMessageId,
                                    content = salvagedContent.ifBlank {
                                        if (salvagedThinking.isNullOrBlank()) {
                                            vm.messageForEmptyInferencePayload(
                                                currentHasMediaInput,
                                                currentEngineModelName ?: ""
                                            )
                                        } else {
                                            ""
                                        }
                                    },
                                    isStreaming = false,
                                    thinkingContent = vm.closePartialThinking(salvagedThinking)
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(ChatViewModel.TAG, "Failed to clear streaming flag on message $streamingMessageId", t)
                    }
                }

                // Bug fix(#5): 過去ターンの画像をロードした Bitmap を recycle する。
                pastHistoryBitmapsToRecycle.forEach { bmp ->
                    if (!bmp.isRecycled) {
                        runCatching { bmp.recycle() }
                    }
                }
                pastHistoryBitmapsToRecycle.clear()

                // Gallery パターン: 全パスで _isLoading を false にする
                Log.d(ChatViewModel.TAG, "Generation concluded, setting isLoading=false")
                vm._isLoading.value = false

                // Tool Call State マシンを Done に設定
                vm._toolCallState.value = ToolCallState.Done
                vm._currentTps.value = null

                // Phase 11: 全体のロード時間をログ出力
                val aiTotalMs = System.currentTimeMillis() - aiStartMs
                Log.d(ChatViewModel.TAG, "generateAIResponse TOTAL_DURATION: ${aiTotalMs}ms (model load, inference, and all processing)")

                // Release WakeLock when generation completes
                vm.releaseScreenWakeLock()
            }
        }
    }
}
