package com.nezumi_ai.presentation.ui.fragment

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.R
import com.nezumi_ai.databinding.ItemLicenseBinding

class LicenseAdapter(private val licenses: List<LicenseItem>) :
    RecyclerView.Adapter<LicenseAdapter.LicenseViewHolder>() {

    inner class LicenseViewHolder(private val binding: ItemLicenseBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(license: LicenseItem) {
            val context = binding.root.context

            binding.licenseItemTitle.text = context.getString(license.titleRes)
            binding.licenseItemDescription.text = context.getString(license.descriptionRes)

            binding.licenseItemButton.setOnClickListener {
                val url = context.getString(license.urlRes)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LicenseViewHolder {
        val binding = ItemLicenseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LicenseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LicenseViewHolder, position: Int) {
        holder.bind(licenses[position])
    }

    override fun getItemCount(): Int = licenses.size
}
