package com.nezumi_ai.presentation.ui.fragment

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.databinding.FragmentImageGenerationResultBinding
import java.io.File
import java.io.FileOutputStream

class ImageGenerationResultFragment : Fragment() {

    private var _binding: FragmentImageGenerationResultBinding? = null
    private val binding get() = _binding!!

    private var generatedBitmap: Bitmap? = null
    private var promptText: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageGenerationResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 引数から画像とプロンプトを受け取る
        arguments?.let {
            promptText = it.getString("prompt", "")
        }

        setupUI()
        setupButtons()
    }

    private fun setupUI() {
        binding.promptText.text = promptText

        // TODO: ビットマップを取得してセット
        // binding.resultImage.setImageBitmap(generatedBitmap)
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.retryButton.setOnClickListener {
            // 同じ条件で再生成
            findNavController().navigateUp()
        }

        binding.saveButton.setOnClickListener {
            saveBitmapToDevice()
        }

        binding.shareButton.setOnClickListener {
            shareBitmap()
        }
    }

    private fun saveBitmapToDevice() {
        generatedBitmap?.let { bitmap ->
            try {
                val cacheDir = requireContext().cacheDir
                val fileName = "generated_${System.currentTimeMillis()}.jpg"
                val file = File(cacheDir, fileName)

                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }

                // TODO: MediaStoreに追加してギャラリーに表示
                // showToast("画像を保存しました")
            } catch (e: Exception) {
                // TODO: エラー処理
            }
        }
    }

    private fun shareBitmap() {
        generatedBitmap?.let { bitmap ->
            try {
                val cacheDir = requireContext().cacheDir
                val fileName = "share_${System.currentTimeMillis()}.jpg"
                val file = File(cacheDir, fileName)

                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    file
                )

                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = "image/jpeg"
                }

                startActivity(Intent.createChooser(shareIntent, "共有する"))
            } catch (e: Exception) {
                // TODO: エラー処理
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}