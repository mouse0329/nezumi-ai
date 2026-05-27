package com.nezumi_ai.presentation.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.benchmark.BenchmarkPrompt
import com.nezumi_ai.data.benchmark.BenchmarkResult
import com.nezumi_ai.data.benchmark.BenchmarkSummary
import com.nezumi_ai.presentation.viewmodel.BenchmarkViewModel
import com.nezumi_ai.presentation.viewmodel.BenchmarkViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BenchmarkFragment : Fragment() {

    private lateinit var viewModel: BenchmarkViewModel

    // Views
    private lateinit var btnBack: View
    private lateinit var spinnerModel: Spinner
    private lateinit var cbShort: CheckBox
    private lateinit var cbMedium: CheckBox
    private lateinit var cbLong: CheckBox
    private lateinit var spinnerRepeat: Spinner
    private lateinit var btnStart: Button
    private lateinit var layoutProgress: LinearLayout
    private lateinit var tvProgressMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var cardSummary: View
    private lateinit var tvSummaryTitle: TextView
    private lateinit var tvAvgTps: TextView
    private lateinit var tvTpsRange: TextView
    private lateinit var tvAvgTtft: TextView
    private lateinit var tvAvgMem: TextView
    private lateinit var cardDetails: View
    private lateinit var tvDetails: TextView
    private lateinit var btnCopy: Button
    private var modelOptions: List<BenchmarkViewModel.ModelOption> = emptyList()
    private val repeatValues = listOf(1, 3, 5)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_benchmark, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ViewModelの初期化
        val factory = BenchmarkViewModelFactory(requireContext().applicationContext)
        viewModel = ViewModelProvider(this, factory)[BenchmarkViewModel::class.java]

        // Viewの参照を取得
        btnBack = view.findViewById(R.id.btn_back)
        spinnerModel = view.findViewById(R.id.spinner_model)
        cbShort = view.findViewById(R.id.cb_short)
        cbMedium = view.findViewById(R.id.cb_medium)
        cbLong = view.findViewById(R.id.cb_long)
        spinnerRepeat = view.findViewById(R.id.spinner_repeat)
        btnStart = view.findViewById(R.id.btn_start)
        layoutProgress = view.findViewById(R.id.layout_progress)
        tvProgressMessage = view.findViewById(R.id.tv_progress_message)
        progressBar = view.findViewById(R.id.progress_bar)
        tvError = view.findViewById(R.id.tv_error)
        cardSummary = view.findViewById(R.id.card_summary)
        tvSummaryTitle = view.findViewById(R.id.tv_summary_title)
        tvAvgTps = view.findViewById(R.id.tv_avg_tps)
        tvTpsRange = view.findViewById(R.id.tv_tps_range)
        tvAvgTtft = view.findViewById(R.id.tv_avg_ttft)
        tvAvgMem = view.findViewById(R.id.tv_avg_mem)
        cardDetails = view.findViewById(R.id.card_details)
        tvDetails = view.findViewById(R.id.tv_details)
        btnCopy = view.findViewById(R.id.btn_copy)

        // 繰り返し数Spinner
        val repeatOptions = listOf("1回", "3回", "5回")
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            repeatOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerRepeat.adapter = spinnerAdapter
        spinnerRepeat.setSelection(1) // デフォルト3回

        // ボタン
        btnBack.setOnClickListener { findNavController().popBackStack() }
        configureStartButton("ベンチマーク開始")

        viewModel.refreshModelOptions()

        btnCopy.setOnClickListener {
            copyResultsToClipboard()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.modelOptions.collectLatest { options ->
                modelOptions = options
                val labels = if (options.isEmpty()) {
                    listOf("利用可能なモデルがありません")
                } else {
                    options.map { it.label }
                }
                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    labels
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                spinnerModel.adapter = adapter
                spinnerModel.isEnabled = options.isNotEmpty()
                val selectedIndex = options.indexOfFirst { it.model == viewModel.selectedModel }
                if (selectedIndex >= 0) spinnerModel.setSelection(selectedIndex)
            }
        }

        // State観測
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                when (state) {
                    is BenchmarkViewModel.State.Idle -> {
                        configureStartButton("ベンチマーク開始")
                        layoutProgress.visibility = View.GONE
                        tvError.visibility = View.GONE
                        spinnerModel.isEnabled = modelOptions.isNotEmpty()
                    }
                    is BenchmarkViewModel.State.Running -> {
                        btnStart.text = "中断"
                        btnStart.isEnabled = true
                        btnStart.setOnClickListener { viewModel.cancelBenchmark() }
                        spinnerModel.isEnabled = false
                        layoutProgress.visibility = View.VISIBLE
                        tvError.visibility = View.GONE
                        tvProgressMessage.text = state.message
                        if (state.total > 0) {
                            progressBar.max = state.total
                            progressBar.progress = state.progress
                        }
                    }
                    is BenchmarkViewModel.State.Done -> {
                        configureStartButton("もう一度")
                        layoutProgress.visibility = View.GONE
                        tvError.visibility = View.GONE
                        spinnerModel.isEnabled = modelOptions.isNotEmpty()
                        showSummary(state.summary)
                    }
                    is BenchmarkViewModel.State.Error -> {
                        configureStartButton("ベンチマーク開始")
                        layoutProgress.visibility = View.GONE
                        spinnerModel.isEnabled = modelOptions.isNotEmpty()
                        tvError.visibility = View.VISIBLE
                        tvError.text = state.message
                    }
                }
            }
        }

        // 途中経過の詳細を観測
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.results.collectLatest { results ->
                if (results.isNotEmpty()) {
                    updateLiveDetails(results)
                }
            }
        }
    }

    private fun configureStartButton(label: String) {
        btnStart.text = label
        btnStart.isEnabled = true
        btnStart.setOnClickListener { startBenchmarkFromUi() }
    }

    private fun startBenchmarkFromUi() {
        val selectedPrompts = buildList {
            if (cbShort.isChecked) add(BenchmarkPrompt.SHORT)
            if (cbMedium.isChecked) add(BenchmarkPrompt.MEDIUM)
            if (cbLong.isChecked) add(BenchmarkPrompt.LONG)
        }
        if (selectedPrompts.isEmpty()) {
            Toast.makeText(requireContext(), "プロンプトを1つ以上選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedModel = modelOptions.getOrNull(spinnerModel.selectedItemPosition)
        if (selectedModel == null) {
            Toast.makeText(requireContext(), "モデルを選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.selectedModel = selectedModel.model
        viewModel.selectedPrompts = selectedPrompts
        viewModel.repeatCount = repeatValues.getOrElse(spinnerRepeat.selectedItemPosition) { 3 }
        viewModel.startBenchmark()
    }

    private fun showSummary(summary: BenchmarkSummary) {
        cardSummary.visibility = View.VISIBLE
        cardDetails.visibility = View.VISIBLE

        val modelShort = summary.modelName.substringAfterLast("/").take(30)
        tvSummaryTitle.text = "${summary.engineName} · $modelShort"
        tvAvgTps.text = "%.1f tok/s".format(summary.avgTokensPerSec)
        tvTpsRange.text = "%.1f – %.1f tok/s".format(summary.minTokensPerSec, summary.maxTokensPerSec)
        tvAvgTtft.text = "%.0f ms".format(summary.avgTtftMs)
        tvAvgMem.text = "+%.0f MB".format(summary.avgMemDeltaMB)

        tvDetails.text = buildDetailText(summary.results)
    }

    private fun updateLiveDetails(results: List<BenchmarkResult>) {
        cardDetails.visibility = View.VISIBLE
        tvDetails.text = buildDetailText(results)
    }

    private fun buildDetailText(results: List<BenchmarkResult>): String {
        return buildString {
            results.forEach { r ->
                if (r.error != null) {
                    appendLine("#${r.runIndex + 1} [${r.prompt.label}] エラー: ${r.error}")
                } else {
                    appendLine("#${r.runIndex + 1} [${r.prompt.label}]")
                    appendLine("  TTFT: ${r.ttftMs}ms")
                    appendLine("  TPS:  %.1f tok/s (%d tokens, %dms)".format(r.tokensPerSec, r.tokenCount, r.totalMs))
                    appendLine("  MEM:  before=${r.memBeforeMB}MB after=${r.memAfterMB}MB delta=+${r.memPeakDeltaMB}MB")
                }
            }
        }.trimEnd()
    }

    private fun copyResultsToClipboard() {
        val text = tvDetails.text.toString()
        if (text.isBlank()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("ベンチマーク結果", text))
        Toast.makeText(requireContext(), "クリップボードにコピーしました", Toast.LENGTH_SHORT).show()
    }
}
