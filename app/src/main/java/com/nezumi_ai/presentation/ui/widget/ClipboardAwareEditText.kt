package com.nezumi_ai.presentation.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.AppCompatEditText

class ClipboardAwareEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {
    
    var onClipboardImagePaste: (() -> Unit)? = null
    
    init {
        // ActionModeコールバックをカスタマイズ
        val defaultCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = true
            
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                if (menu != null) {
                    val pasteItem = menu.findItem(android.R.id.paste)
                    if (pasteItem != null) {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) 
                                as android.content.ClipboardManager
                            val primaryClip = clipboard.primaryClip
                            
                            if (primaryClip != null && primaryClip.itemCount > 0) {
                                val clipItem = primaryClip.getItemAt(0)
                                
                                val hasImage = clipItem.uri != null || 
                                    (clipItem.text != null && 
                                    (clipItem.text.toString().startsWith("content://") || 
                                     clipItem.text.toString().startsWith("file://")))
                                
                                if (hasImage) {
                                    pasteItem.title = "画像を貼り付け"
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ClipboardAwareEditText", "Error checking clipboard", e)
                        }
                    }
                }
                return false
            }
            
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                return when (item?.itemId) {
                    android.R.id.paste -> {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) 
                                as android.content.ClipboardManager
                            val primaryClip = clipboard.primaryClip
                            
                            if (primaryClip != null && primaryClip.itemCount > 0) {
                                val clipItem = primaryClip.getItemAt(0)
                                
                                if (clipItem.uri != null || 
                                    (clipItem.text != null && 
                                    (clipItem.text.toString().startsWith("content://") || 
                                     clipItem.text.toString().startsWith("file://")))) {
                                    // 画像またはURIの貼り付けを検出
                                    Log.d("ClipboardAwareEditText", "Image paste detected")
                                    onClipboardImagePaste?.invoke()
                                    mode?.finish()
                                    return true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ClipboardAwareEditText", "Error during paste", e)
                        }
                        false
                    }
                    else -> false
                }
            }
            
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        
        customSelectionActionModeCallback = defaultCallback
    }
}

