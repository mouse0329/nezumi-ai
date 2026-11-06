package com.nezumi_ai.presentation.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics
import com.nezumi_ai.utils.PreferencesHelper

internal fun effortSegmentsAlpha(thinkingOn: Boolean): Float = 1.0f

internal fun availableEffortLevels(
    granularity: ModelNameHeuristics.ReasoningEffortGranularity
): List<String> = ModelNameHeuristics.supportedEffortLevels(granularity)

@Composable
fun AttachmentOptionsSheet(
    imageEnabled: Boolean,
    onImageClick: () -> Unit,
    onCameraClick: () -> Unit,
    onFileClick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    thinkingOn: Boolean? = null,
    thinkingSwitchable: Boolean = true,
    thinkingEffort: String = PreferencesHelper.THINKING_EFFORT_LOW,
    effortLevels: List<String> = PreferencesHelper.ALL_THINKING_EFFORTS,
    onThinkingChange: (Boolean) -> Unit = {},
    onEffortChange: (String) -> Unit = {},
) {
    SharedAttachmentOptionsSheet(
        imageIcon = painterResource(R.drawable.ic_image),
        cameraIcon = painterResource(R.drawable.ic_camera),
        fileIcon = painterResource(R.drawable.ic_description),
        title = stringResource(R.string.attachment_options_title),
        imageLabel = stringResource(R.string.attachment_options_image),
        cameraLabel = stringResource(R.string.attachment_options_camera),
        fileLabel = stringResource(R.string.attachment_options_file),
        cancelLabel = stringResource(R.string.attachment_options_cancel),
        thinkingLabel = stringResource(R.string.chat_thinking_label),
        effortLabels = mapOf(
            PreferencesHelper.THINKING_EFFORT_LOW to stringResource(R.string.thinking_effort_low),
            PreferencesHelper.THINKING_EFFORT_MEDIUM to stringResource(R.string.thinking_effort_medium),
            PreferencesHelper.THINKING_EFFORT_HIGH to stringResource(R.string.thinking_effort_high),
            "minimal" to stringResource(R.string.thinking_effort_minimal),
            "max" to stringResource(R.string.thinking_effort_max),
            "xhigh" to stringResource(R.string.thinking_effort_xhigh),
        ),
        imageEnabled = imageEnabled,
        onImageClick = onImageClick,
        onCameraClick = onCameraClick,
        onFileClick = onFileClick,
        onCancel = onCancel,
        modifier = modifier,
        thinkingOn = thinkingOn,
        thinkingSwitchable = thinkingSwitchable,
        thinkingEffort = thinkingEffort,
        effortLevels = effortLevels,
        onThinkingChange = onThinkingChange,
        onEffortChange = onEffortChange,
    )
}
