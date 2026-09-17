package com.nezumi_ai.presentation.ui.fragment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
import kotlinx.coroutines.delay

/**
 * 設定画面の検索機能。
 *
 * 「設定項目を検索してそこまでジャンプできるようにする」ため、
 * 各設定項目の表示名 (string リソース) と所属セクション index の対応表を保持し、
 * キーワード (ひらがな/カタカナ・大小文字を正規化) で部分一致検索する。
 * 結果タップで [onJumpToSection] にセクション index を返し、
 * 呼び出し側 (SettingsComposeFragment) がそのセクションへ遷移する。
 */

/** 検索対象の1項目。 [sectionIndex] は SettingsComposeFragment の selectedSection に対応する。 */
private data class SettingsSearchEntry(
    val sectionIndex: Int,
    @StringRes val labelRes: Int,
    @StringRes val breadcrumbRes: Int,
    /**
     * 実際の設定行に付与された [Modifier.settingsSearchAnchor] のキー。
     * 通常は [labelRes] と同じ（行のタイトルTextがそのままアンカーになる）が、
     * 見出しが動的フォーマット文字列などラベルと異なる場合はここで上書きする。
     */
    @StringRes val anchorRes: Int
)

/**
 * 検索インデックス。 sectionTitles と同じ並び:
 *   0:全般 1:推論 2:画像 3:メモリ 4:チャット 5:ログ 6:ツール 7:スキル 8:ストレージ 9:デバッグ(DEBUGのみ)
 * 項目の文字列キーは各カード Composable 内で実際に使われているものから選んでいる。
 */
private val SETTINGS_SEARCH_ENTRIES: List<SettingsSearchEntry> = buildList {
    fun e(section: Int, @StringRes label: Int, @StringRes crumb: Int, @StringRes anchor: Int = label) =
        add(SettingsSearchEntry(section, label, crumb, anchor))

    // 0: 全般
    // 注: テーマ/言語の見出しは実際の画面では動的フォーマット文字列
    // (settings_theme_current_format / settings_language_current_format) で
    // 描画されており、settings_theme_label / settings_language_label 自体は
    // 行として表示されない。検索結果のラベル文言はそのまま使うが、
    // ジャンプ先アンカーとしては実際に画面へ出ている current_format 側を使う。
    e(0, R.string.settings_theme_label, R.string.settings_section_general, anchor = R.string.settings_theme_current_format)
    e(0, R.string.settings_language_label, R.string.settings_section_general, anchor = R.string.settings_language_current_format)
    e(0, R.string.settings_secret_mode_title, R.string.settings_section_general)
    e(0, R.string.settings_always_lock_title, R.string.settings_section_general)
    e(0, R.string.settings_stop_kb_learning_title, R.string.settings_section_general)
    e(0, R.string.settings_show_context_meter_title, R.string.settings_section_general)
    e(0, R.string.settings_show_tps_title, R.string.settings_section_general)
    e(0, R.string.settings_show_ttft_title, R.string.settings_section_general)
    e(0, R.string.settings_disable_screenshot_title, R.string.settings_section_general)
    e(0, R.string.settings_miniapp_dev_mode_title, R.string.settings_section_general)

    // 1: 推論 (パラメータ / GGUF / LiteRT-LM)
    e(1, R.string.settings_inference_params_title, R.string.settings_section_inference)
    e(1, R.string.settings_inference_context_size, R.string.settings_section_inference)
    e(1, R.string.settings_inference_max_tokens, R.string.settings_section_inference)
    e(1, R.string.settings_inference_temperature_label, R.string.settings_section_inference)
    e(1, R.string.settings_inference_topp_label, R.string.settings_section_inference)
    e(1, R.string.settings_inference_topk_label, R.string.settings_section_inference)
    e(1, R.string.settings_inference_preload_warning_title, R.string.settings_section_inference)
    e(1, R.string.settings_gguf_title, R.string.settings_section_inference)
    e(1, R.string.settings_cpu_threads, R.string.settings_section_inference)
    e(1, R.string.settings_batch_size, R.string.settings_section_inference)
    e(1, R.string.settings_gpu_layers, R.string.settings_section_inference)
    e(1, R.string.settings_llamacpp_gpu_backend, R.string.settings_section_inference)
    e(1, R.string.settings_flash_attention, R.string.settings_section_inference)
    e(1, R.string.settings_dynamic_batch, R.string.settings_section_inference)
    e(1, R.string.settings_inference_kv_cache_title, R.string.settings_section_inference)
    e(1, R.string.settings_inference_context_shift_title, R.string.settings_section_inference)
    e(1, R.string.settings_inference_generation_batch_title, R.string.settings_section_inference)
    e(1, R.string.settings_mtp_title, R.string.settings_section_inference)
    e(1, R.string.settings_rope_base, R.string.settings_section_inference)
    e(1, R.string.settings_rope_scale, R.string.settings_section_inference)
    e(1, R.string.settings_inference_literlm_settings_title, R.string.settings_section_inference)
    e(1, R.string.settings_inference_backend_title, R.string.settings_section_inference)
    e(1, R.string.settings_inference_speculative_decoding_title, R.string.settings_section_inference)
    e(1, R.string.settings_require_multimodal, R.string.settings_section_inference)
    e(1, R.string.settings_inference_check_engine_version, R.string.settings_section_inference)

    // 2: 画像
    e(2, R.string.settings_image_generation_title, R.string.settings_section_image)
    e(2, R.string.settings_steps_title, R.string.settings_section_image)
    e(2, R.string.settings_cfg_scale_title, R.string.settings_section_image)
    e(2, R.string.settings_seed_title, R.string.settings_section_image)
    e(2, R.string.settings_scheduler_title, R.string.settings_section_image)

    // 3: メモリ
    e(3, R.string.settings_memory_management_title, R.string.settings_section_memory)
    e(3, R.string.settings_memory_save_mode_title, R.string.settings_section_memory)
    // 一覧表示/全削除ともにダイアログのタイトル文言だが、実際に押す行はそれぞれのボタン
    // (settings_memory_list_show / settings_memory_delete_all_title 自体は
    // ボタンのラベルにも使われているためそのままアンカーとして使える)。
    e(3, R.string.settings_memory_list_title, R.string.settings_section_memory, anchor = R.string.settings_memory_list_show)
    e(3, R.string.settings_memory_delete_all_title, R.string.settings_section_memory)

    // 4: チャット
    e(4, R.string.settings_chat_history_management_title, R.string.settings_section_chat)
    e(4, R.string.settings_chat_history_count_title, R.string.settings_section_chat)

    // 5: ログ
    e(5, R.string.logs_tab_tool_history, R.string.settings_section_logs)
    e(5, R.string.logs_tab_logcat, R.string.settings_section_logs)
    e(5, R.string.settings_logcat_title, R.string.settings_section_logs)

    // 6: ツール
    e(6, R.string.tools_web_fetch_js_render, R.string.tools_settings)
    e(6, R.string.preset_edit_mcp_server_label, R.string.tools_settings)

    // 7: スキル
    e(7, R.string.skills_settings_title, R.string.settings_section_skills)

    // 8: ストレージ
    e(8, R.string.settings_section_storage, R.string.settings_section_storage)

    // 9: デバッグ (リリースビルドではセクション自体が非表示のため登録しない)
    if (BuildConfig.DEBUG) {
        e(9, R.string.settings_debug_section_title, R.string.settings_section_debug)
        e(9, R.string.settings_debug_nsfw_title, R.string.settings_section_debug)
        e(9, R.string.settings_debug_similarity_title, R.string.settings_section_debug)
        e(9, R.string.settings_debug_tts_title, R.string.settings_section_debug)
        e(9, R.string.settings_debug_tts_history_title, R.string.settings_section_debug)
    }
}

/** 検索結果1件分の表示用データ。 */
data class SettingsSearchResult(
    val sectionIndex: Int,
    val label: String,
    val breadcrumb: String,
    /** この結果が対応する実際の設定行の [labelRes]。行側の [Modifier.settingsSearchAnchor] と紐付けるキー。 */
    val anchorKey: Int
)

/**
 * 検索ジャンプ機能の共有状態。
 *
 * これまでは検索結果をタップしても「該当ラベルを複製した偽の行」を
 * リストの先頭に一時的に挿入して点滅させるだけで、実際の設定行までは
 * スクロールもハイライトもされていなかった。
 *
 * この状態を [SettingsComposeFragment] の SettingsScreen() 直下で1つ remember し、
 * 各設定行の [Modifier.settingsSearchAnchor] に同じインスタンスを渡すことで、
 * 「どの行が画面内のどこにいるか」を実行時に登録してもらい、
 * ジャンプ先が確定した時点でその実際の行へスクロール＆ハイライトできるようにする。
 */
class SettingsSearchJumpState {
    /** labelRes -> その行の BringIntoViewRequester。Composition中に各行が自己登録する。 */
    private val requesters = mutableMapOf<Int, BringIntoViewRequester>()

    /** labelRes -> 現在ハイライト中かどうか。行側はこれを読んで背景色を変える。 */
    val highlighted = mutableStateMapOf<Int, Boolean>()

    fun register(labelRes: Int, requester: BringIntoViewRequester) {
        requesters[labelRes] = requester
    }

    fun unregister(labelRes: Int) {
        requesters.remove(labelRes)
    }

    fun requesterFor(labelRes: Int): BringIntoViewRequester? = requesters[labelRes]

    /**
     * 指定ラベルの行を実際に画面内へスクロールし、一定時間点滅させる。
     * 対象行がまだコンポーズされていない（別セクション表示中など）場合は
     * [maxWaitMillis] の間、登録されるのを待ってからスクロールする。
     */
    suspend fun jumpTo(labelRes: Int, maxWaitMillis: Long = 2000) {
        var waited = 0L
        val pollInterval = 50L
        while (requesterFor(labelRes) == null && waited < maxWaitMillis) {
            delay(pollInterval)
            waited += pollInterval
        }
        val requester = requesterFor(labelRes) ?: return
        runCatching { requester.bringIntoView() }
        highlighted[labelRes] = true
        repeat(6) {
            delay(300)
            highlighted[labelRes] = highlighted[labelRes] != true
        }
        highlighted[labelRes] = false
    }
}

/**
 * 設定行のタイトル [Text] などに付与する。
 * 1) [BringIntoViewRequester] を [jumpState] に自己登録し、ジャンプ対象として発見可能にする
 * 2) [jumpState] のハイライト状態を読み、点滅する背景色を自分自身に描画する
 *
 * これにより「検索結果を模した別要素」ではなく、実際にその設定行そのものが
 * 画面内へスクロールされ、そのままハイライトされる。
 */
@Composable
fun Modifier.settingsSearchAnchor(
    @StringRes labelRes: Int,
    jumpState: SettingsSearchJumpState
): Modifier {
    val requester = remember(labelRes) { BringIntoViewRequester() }
    androidx.compose.runtime.DisposableEffect(labelRes) {
        jumpState.register(labelRes, requester)
        onDispose { jumpState.unregister(labelRes) }
    }
    val isHighlighted = jumpState.highlighted[labelRes] == true
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) Color(0xFFFFEB3B) else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "settingsSearchHighlight"
    )
    return this
        .bringIntoViewRequester(requester)
        .background(highlightColor, RoundedCornerShape(6.dp))
}

/**
 * ひらがな→カタカナ・小文字化で正規化して部分一致検索する。
 * 「てーま」「テーマ」「Theme」のどれで打っても同じ項目にヒットさせるため。
 */
fun searchSettingsEntries(context: Context, query: String): List<SettingsSearchResult> {
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                if (c in 'ぁ'..'ん') (c.code + 0x60).toChar() else c.lowercaseChar()
            )
        }
        return sb.toString()
    }

    val q = normalize(query.trim())
    if (q.isEmpty()) return emptyList()

    return SETTINGS_SEARCH_ENTRIES.mapNotNull { entry ->
        val label = context.getString(entry.labelRes)
        if (!normalize(label).contains(q)) return@mapNotNull null
        SettingsSearchResult(
            sectionIndex = entry.sectionIndex,
            label = label,
            breadcrumb = context.getString(entry.breadcrumbRes),
            anchorKey = entry.anchorRes
        )
    }.distinctBy { it.sectionIndex to it.label }
}

/**
 * 設定検索のボトムシート。
 * テキスト入力のたびに [searchSettingsEntries] で絞り込み、
 * 結果行タップで [onJumpToSection] に選択結果を渡して閉じる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchSheet(
    onJumpToSection: (SettingsSearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { searchSettingsEntries(context, query) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(id = R.string.settings_search_hint)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = stringResource(id = R.string.common_clear)
                            )
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (query.isNotBlank() && results.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.settings_search_no_results),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn {
                    items(results, key = { it.sectionIndex.toString() + ":" + it.label }) { result ->
                        SettingsSearchResultRow(
                            result = result,
                            onClick = {
                                onJumpToSection(result)
                                onDismiss()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 検索結果の1行。 項目名 + 所属セクションをパンくずとして表示する。 */
@Composable
private fun SettingsSearchResultRow(
    result: SettingsSearchResult,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.surface_card)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorResource(id = R.color.text_primary)
                )
                Text(
                    text = result.breadcrumb,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right_24),
                contentDescription = null,
                tint = colorResource(id = R.color.text_secondary),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
