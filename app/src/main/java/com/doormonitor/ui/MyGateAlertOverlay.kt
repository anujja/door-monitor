package com.doormonitor.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doormonitor.mygate.MyGateAlert
import com.doormonitor.mygate.MyGateAlerter
import com.doormonitor.mygate.MyGateNotificationListener
import java.util.Date

private data class AlertStyle(val label: String, val icon: ImageVector, val accent: Color)

private fun styleFor(type: String) = when (type) {
    MyGateNotificationListener.TYPE_APPROVAL ->
        AlertStyle("Visitor at the gate", Icons.Filled.DoorFront, Color(0xFF29B6F6))
    MyGateNotificationListener.TYPE_EMERGENCY ->
        AlertStyle("Emergency", Icons.Filled.Emergency, Color(0xFFEF5350))
    MyGateNotificationListener.TYPE_VEHICLE ->
        AlertStyle("Vehicle", Icons.Filled.DirectionsCar, Color(0xFFFFB300))
    else ->
        AlertStyle("MyGate", Icons.Filled.NotificationsActive, Color(0xFF66BB6A))
}

/**
 * Full-screen card for a MyGate notification, drawn above the dashboard and camera overlay.
 * Sized to be read from across the room. A tap anywhere dismisses it; otherwise it goes away
 * on its own when [MyGateAlerter] clears it, which the bar along the bottom counts down to.
 */
@Composable
fun MyGateAlertOverlay(alert: MyGateAlert?, onDismiss: () -> Unit) {
    // Keep drawing the last alert while the exit animation runs.
    var shown by remember { mutableStateOf(alert) }
    if (alert != null) shown = alert

    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(250))
    ) {
        val a = shown ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AlertCard(
                alert = a,
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(tween(220), initialScale = 0.92f) +
                        slideInVertically(tween(220)) { it / 8 }
                )
            )
        }
    }
}

@Composable
private fun AlertCard(alert: MyGateAlert, modifier: Modifier = Modifier) {
    val style = styleFor(alert.type)
    val context = LocalContext.current
    val time = remember(alert.postTime) {
        DateFormat.getTimeFormat(context).format(Date(alert.postTime))
    }

    // Countdown to auto-dismiss, resumed from the real deadline if we recompose mid-way.
    val totalMs = MyGateAlerter.DISPLAY_SECONDS * 1000f
    val remaining = remember(alert) { Animatable(1f) }
    LaunchedEffect(alert) {
        val leftMs = (alert.expiresAt - System.currentTimeMillis()).coerceIn(0L, totalMs.toLong())
        remaining.snapTo(leftMs / totalMs)
        remaining.animateTo(0f, tween(leftMs.toInt(), easing = LinearEasing))
    }

    Surface(
        modifier = modifier
            // Cap first: a fill applied first would pin an exact width the cap can't shrink.
            .widthIn(max = 760.dp)
            .fillMaxWidth(0.9f)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 24.dp
    ) {
        Column(Modifier.padding(start = 36.dp, end = 36.dp, top = 32.dp, bottom = 28.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(style.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        style.icon,
                        contentDescription = null,
                        tint = style.accent,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Column {
                    Text(
                        text = style.label.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = style.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "MyGate · $time",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = alert.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (alert.text.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = alert.text,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { remaining.value },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = style.accent,
                trackColor = style.accent.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}
