package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.databinding.FragmentImageGenerationBinding

class ImageGenerationFragment : Fragment() {

    private var _binding: FragmentImageGenerationBinding? = null
    private val binding get() = _binding!!

    private var selectedSize = "512x512"
    private var isNegativePromptVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageGenerationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupModelSpinner()
        setupSizeButtons()
        setupNegativePromptToggle()
        setupBackButton()
        setupGenerateButton()
    }

    private fun setupModelSpinner() {
        val models = arrayOf("anythingv5_cpu", "dream-shaper-v8")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter
    }

    private fun setupSizeButtons() {
        binding.size512.isSelected = true

        binding.size256.setOnClickListener {
            selectSize("256x256", binding.size256)
        }

        binding.size512.setOnClickListener {
            selectSize("512x512", binding.size512)
        }

        binding.size768.setOnClickListener {
            selectSize("768x768", binding.size768)
        }
    }

    private fun selectSize(size: String, button: com.google.android.material.button.MaterialButton) {
        selectedSize = size
        binding.sizeDisplay.text = size

        // ボタンのスタイル更新
        binding.size256.isSelected = (size == "256x256")
        binding.size512.isSelected = (size == "512x512")
        binding.size768.isSelected = (size == "768x768")
    }

    private fun setupNegativePromptToggle() {
        binding.negativePromptToggle.setOnClickListener {
            isNegativePromptVisible = !isNegativePromptVisible
            binding.negativePromptSection.visibility = 
                if (isNegativePromptVisible) View.VISIBLE else View.GONE
            binding.negativePromptToggle.text = 
                if (isNegativePromptVisible) "▼ ネガティブプロンプトを入力" 
                else "▶ ネガティブプロンプトを入力"
        }
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupGenerateButton() {
        binding.generateButton.setOnClickListener {
            val prompt = binding.promptInput.text.toString()
            val negativePrompt = binding.negativePromptInput.text.toString()
            val model = binding.modelSpinner.selectedItem.toString()

            // 生成処理（実装はViewModel等で行う）
            generateImage(prompt, negativePrompt, model, selectedSize)
        }
    }

    private fun generateImage(
        prompt: String,
        negativePrompt: String,
        model: String,
        size: String
    ) {
        // TODO: ViewModelを通じて生成を開始
        // 現在はプレースホルダー
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}