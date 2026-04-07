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
                R.string.license_project_title,
                R.string.license_project_desc,
                R.string.license_project_url
            ),
            LicenseItem(
                R.string.license_androidx_title,
                R.string.license_androidx_desc,
                R.string.license_androidx_url
            ),
            LicenseItem(
                R.string.license_constraintlayout_title,
                R.string.license_constraintlayout_desc,
                R.string.license_constraintlayout_url
            ),
            LicenseItem(
                R.string.license_navigation_title,
                R.string.license_navigation_desc,
                R.string.license_navigation_url
            ),
            LicenseItem(
                R.string.license_room_title,
                R.string.license_room_desc,
                R.string.license_room_url
            ),
            LicenseItem(
                R.string.license_workmanager_title,
                R.string.license_workmanager_desc,
                R.string.license_workmanager_url
            ),
            LicenseItem(
                R.string.license_lifecycle_title,
                R.string.license_lifecycle_desc,
                R.string.license_lifecycle_url
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
                R.string.license_mediapipe_genai_title,
                R.string.license_mediapipe_genai_desc,
                R.string.license_mediapipe_genai_url
            ),
            LicenseItem(
                R.string.license_litertlm_title,
                R.string.license_litertlm_desc,
                R.string.license_litertlm_url
            ),
            LicenseItem(
                R.string.license_tflite_title,
                R.string.license_tflite_desc,
                R.string.license_tflite_url
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
                R.string.license_gemma4_title,
                R.string.license_gemma4_desc,
                R.string.license_gemma4_url
            ),
            LicenseItem(
                R.string.license_appauth_title,
                R.string.license_appauth_desc,
                R.string.license_appauth_url
            ),
            LicenseItem(
                R.string.license_markwon_title,
                R.string.license_markwon_desc,
                R.string.license_markwon_url
            ),
            LicenseItem(
                R.string.license_junit_title,
                R.string.license_junit_desc,
                R.string.license_junit_url
            ),
            LicenseItem(
                R.string.license_androidtest_title,
                R.string.license_androidtest_desc,
                R.string.license_androidtest_url
            )
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = LicenseAdapter(licenses)
        }
    }
}
