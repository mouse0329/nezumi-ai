package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.R

class LicenseFragment : Fragment(R.layout.fragment_license) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val root = view.findViewById<View>(R.id.license_root)
        val backButton = view.findViewById<ImageButton>(R.id.back_button)
        val recyclerView = view.findViewById<RecyclerView>(R.id.license_recycler_view)

        val initialTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = initialTop + topInset)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Setup RecyclerView with licenses
        val licenses = listOf(
            LicenseItem(
                R.string.license_androidx_title,
                R.string.license_androidx_desc,
                R.string.license_androidx_url
            ),
            LicenseItem(
                R.string.license_kotlin_title,
                R.string.license_kotlin_desc,
                R.string.license_kotlin_url
            ),
            LicenseItem(
                R.string.license_mediapipe_title,
                R.string.license_mediapipe_desc,
                R.string.license_mediapipe_url
            ),
            LicenseItem(
                R.string.license_huggingface_title,
                R.string.license_huggingface_desc,
                R.string.license_huggingface_url
            ),
            LicenseItem(
                R.string.license_gemma_title,
                R.string.license_gemma_desc,
                R.string.license_gemma_url
            ),
            LicenseItem(
                R.string.license_e2b_title,
                R.string.license_e2b_desc,
                R.string.license_e2b_url
            )
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = LicenseAdapter(licenses)
        }
    }
}
