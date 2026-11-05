package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SharedChatHeader(
    title: String,
    titleContentDescription: String?,
    modelName: String,
    headerColor: Color,
    backButtonColor: Color,
    borderColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    menuIcon: Painter,
    logo: Painter,
    menuContentDescription: String?,
    logoContentDescription: String?,
    onBackClick: () -> Unit,
    onTitleClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(headerColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .requiredSize(36.dp)
                .background(backButtonColor, CircleShape)
                .border(1.dp, borderColor, CircleShape)
        ) {
            Icon(
                painter = menuIcon,
                contentDescription = menuContentDescription,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Image(
            painter = logo,
            contentDescription = logoContentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp)
                .size(28.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .clickable(onClick = onTitleClick)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    if (titleContentDescription != null) {
                        contentDescription = titleContentDescription
                    }
                }
            )
            Text(
                text = modelName,
                fontSize = 13.sp,
                color = secondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Box(modifier = Modifier.padding(start = 8.dp)) {
            actions()
        }
    }
}
