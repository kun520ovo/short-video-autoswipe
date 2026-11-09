package com.shortvideo.autoswipe.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Center the icon and label together as one group. */
@Composable
fun ControlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    background: Color,
    foreground: Color,
    height: Dp = 52.dp,
    compact: Boolean = false,
) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(if (compact) 8.dp else 13.dp))
            .background(background).alpha(if (enabled) 1f else .48f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 7.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(if (compact) 16.dp else 24.dp), tint = foreground)
            Text(text, color = foreground, fontSize = if (compact) 12.sp else 16.sp,
                lineHeight = if (compact) 16.sp else 22.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}
