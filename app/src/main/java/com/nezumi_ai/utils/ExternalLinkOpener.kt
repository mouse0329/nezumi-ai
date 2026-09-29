package com.nezumi_ai.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 外部 URL を開く。PackageManager.resolveActivity / queryIntentActivities は使わない。
 * それらは https を扱える全アプリ (Claude 等) を列挙し、アプリフィルターに
 * 「他アプリへアクセスした」と記録される。
 */
object ExternalLinkOpener {
    fun openUrl(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
