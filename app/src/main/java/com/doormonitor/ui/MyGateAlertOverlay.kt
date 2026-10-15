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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doormonitor.mygate.MyGateAlert
import com.doormonitor.mygate.MyGateAlerter
import com.doormonitor.mygate.MyGateAnswer
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

private val ApproveGreen = Color(0xFF2E7D32)
private val DenyRed = Color(0xFFE53935)

/**
 * Full-screen card for a MyGate alert, drawn above the dashboard and camera overlay. Sized to
 * be read from across the room: the visitor's photo, name and where they're from come first,
 * whenever MyGate sends them. A visitor waiting at the gate gets Approve / Deny buttons.
 *
 * A tap outside the card dismisses it; otherwise it goes away when [MyGateAlerter] clears it,
 * which the bar along the bottom counts down to.
 */
@Composable
fun MyGateAlertOverlay(
    alert: MyGateAlert?,
    onDismiss: () -> Unit,
    onRespond: (response: String) -> Unit
) {
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
                onRespond = onRespond,
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(tween(220), initialScale = 0.92f) +
                        slideInVertically(tween(220)) { it / 8 }
                )
            )
        }
    }
}

@Composable
private fun AlertCard(alert: MyGateAlert, onRespond: (String) -> Unit, modifier: Modifier = Modifier) {
    val style = styleFor(alert.type)
    val visitor = alert.visitor
    val context = LocalContext.current
    val time = remember(alert.postTime) {
        DateFormat.getTimeFormat(context).format(Date(alert.postTime))
    }

    Surface(
        modifier = modifier
            // Cap first: a fill applied first would pin an exact width the cap can't shrink.
            .widthIn(max = 860.dp)
            .fillMaxWidth(0.92f)
            // Taps on the card (a near-miss on a button) must not fall through to the scrim.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 24.dp
    ) {
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 24.dp)) {
            Text(
                text = "${style.label.uppercase()}  ·  $time",
                style = MaterialTheme.typography.labelLarge,
                color = style.accent,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                VisitorPhoto(url = visitor?.photo, fallback = style.icon, accent = style.accent)
                Column(Modifier.weight(1f)) {
                    // The name is what matters most; without one, MyGate's title stands in.
                    Text(
                        text = visitor?.name ?: alert.title,
                        style = if (visitor?.name != null) {
                            MaterialTheme.typography.displaySmall
                        } else {
                            MaterialTheme.typography.headlineMedium
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (visitor?.company != null || visitor?.companyLogo != null) {
                        Spacer(Modifier.height(12.dp))
                        Company(visitor.company, visitor.companyLogo, style.accent)
                    }
                    val detail = alert.text.ifEmpty { if (visitor?.name != null) alert.title else "" }
                    if (detail.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (alert.responses.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                AnswerArea(alert, style.accent, onRespond)
            }

            if (alert.answer !is MyGateAnswer.Done) {
                Spacer(Modifier.height(20.dp))
                Countdown(alert, style.accent)
            }
        }
    }
}

/** The visitor's photo, large; the alert's icon in its place when there is none (yet). */
@Composable
private fun VisitorPhoto(url: String?, fallback: ImageVector, accent: Color) {
    val photo = rememberRemoteImage(url)
    val size = if (url != null) 200.dp else 96.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (url != null) 24.dp else 48.dp))
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = "Visitor photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                if (url != null) Icons.Filled.Person else fallback,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(if (url != null) 96.dp else 52.dp)
            )
        }
    }
}

/** Where the visitor is from: "Swiggy", "Amazon"…, with the company's logo when sent. */
@Composable
private fun Company(name: String?, logoUrl: String?, accent: Color) {
    val logo = rememberRemoteImage(logoUrl)
    // A tinted pill with dark text: the accent colour alone is too faint on the light card.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.2f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
            )
            if (name != null) Spacer(Modifier.width(12.dp))
        }
        if (name != null) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AnswerArea(alert: MyGateAlert, accent: Color, onRespond: (String) -> Unit) {
    when (val answer = alert.answer) {
        is MyGateAnswer.Sending -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(72.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = accent,
                trackColor = accent.copy(alpha = 0.2f),
                strokeWidth = 4.dp
            )
            Spacer(Modifier.width(18.dp))
            Text(
                text = "Sending ${MyGateAlerter.RESPONSE_LABELS[answer.response].orEmpty()}…",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        is MyGateAnswer.Done -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(72.dp)
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = ApproveGreen,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = answer.message,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        else -> Column {
            if (answer is MyGateAnswer.Failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = DenyRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Couldn't send: ${answer.message}",
                        style = MaterialTheme.typography.titleMedium,
                        color = DenyRed,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            AnswerButtons(alert.responses, onRespond)
        }
    }
}

/** Deny on the left, Approve on the right, Leave at gate (deliveries) between them. */
@Composable
private fun AnswerButtons(responses: List<String>, onRespond: (String) -> Unit) {
    val buttonModifier = Modifier.height(72.dp)
    val label: @Composable (String) -> Unit = { response ->
        Text(
            MyGateAlerter.RESPONSE_LABELS.getValue(response),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        if ("deny" in responses) {
            OutlinedButton(
                onClick = { onRespond("deny") },
                modifier = buttonModifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(2.dp, DenyRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DenyRed)
            ) { label("deny") }
        }
        if ("leave_at_gate" in responses) {
            FilledTonalButton(
                onClick = { onRespond("leave_at_gate") },
                modifier = buttonModifier.weight(1f),
                shape = RoundedCornerShape(18.dp)
            ) { label("leave_at_gate") }
        }
        if ("approve" in responses) {
            Button(
                onClick = { onRespond("approve") },
                modifier = buttonModifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ApproveGreen, contentColor = Color.White)
            ) { label("approve") }
        }
    }
}

/** Time left before the card closes (for a visitor: before MyGate stops waiting). */
@Composable
private fun Countdown(alert: MyGateAlert, accent: Color) {
    val totalMs = (alert.expiresAt - alert.shownAt).coerceAtLeast(1L)
    val remaining = remember(alert.id, alert.expiresAt) { Animatable(1f) }
    LaunchedEffect(alert.id, alert.expiresAt) {
        val leftMs = (alert.expiresAt - System.currentTimeMillis()).coerceIn(0L, totalMs)
        remaining.snapTo(leftMs.toFloat() / totalMs)
        remaining.animateTo(0f, tween(leftMs.toInt(), easing = LinearEasing))
    }
    LinearProgressIndicator(
        progress = { remaining.value },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = accent,
        trackColor = accent.copy(alpha = 0.15f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}
