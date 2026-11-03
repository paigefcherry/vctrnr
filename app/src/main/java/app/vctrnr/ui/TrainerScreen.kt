package app.vctrnr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vctrnr.OutputRoute
import app.vctrnr.R
import app.vctrnr.TrainerSettings
import app.vctrnr.UiState
import app.vctrnr.audio.Mic
import app.vctrnr.audio.TrainerEngine.Phase
import app.vctrnr.storage.RecordingStore
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TrainerScreen(
    state: UiState,
    onToggle: () -> Unit,
    onOpenFolder: () -> Unit,
    onSettingsChange: (TrainerSettings) -> Unit,
    onRequestMic: () -> Unit,
    onMessageShown: () -> Unit,
    onClose: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state, onClose)
            Spacer(Modifier.height(28.dp))

            BigButton(state, onToggle)
            Spacer(Modifier.height(20.dp))
            StatusLine(state)
            Spacer(Modifier.height(12.dp))
            LevelMeter(state)
            Spacer(Modifier.height(8.dp))
            Text(
                text = takesLabel(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            if (!state.micGranted) {
                MicPermissionCard(state.micPermanentlyDenied, onRequestMic)
                Spacer(Modifier.height(12.dp))
            }
            SettingsCard(state.settings, enabled = !state.running, onSettingsChange)
            Spacer(Modifier.height(8.dp))
            FolderLink(onOpenFolder)
        }
    }
}

@Composable
private fun Header(state: UiState, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("vctrnr", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(
                "Speak, pause, hear yourself back",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Closing is explicit now (vctrnr keeps running with the screen off until you do).
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Close")
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteChip(state.route)
        MicChip(state.micInUse ?: state.settings.mic)
    }
}

@Composable
private fun MicChip(mic: Mic) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(50), color = colors.surfaceVariant, contentColor = colors.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (mic == Mic.Earbuds) "Earbud mic" else "Phone mic", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RouteChip(route: OutputRoute) {
    val (icon, label, warn) = when (route) {
        is OutputRoute.Earbuds -> Triple(R.drawable.ic_headphones, route.name.ifBlank { "Bluetooth earbuds" }, false)
        OutputRoute.Wired -> Triple(R.drawable.ic_headphones, "Wired headphones", false)
        OutputRoute.Speaker -> Triple(R.drawable.ic_speaker, "Phone speaker", true)
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (warn) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (warn) colors.onErrorContainer else colors.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BigButton(state: UiState, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        targetValue = when {
            !state.running -> colors.primary
            state.phase == Phase.Hearing -> colors.tertiary
            state.phase == Phase.Playing || state.phase == Phase.Preparing -> colors.secondary
            else -> colors.primary
        },
        label = "accent",
    )
    val level by animateFloatAsState(
        targetValue = if (state.running && state.phase in LISTENING_PHASES) meterFraction(state.levelDb) else 0f,
        animationSpec = tween(80),
        label = "level",
    )
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension / 2 * 0.72f
            if (state.running) {
                // Outer halo grows with your voice, and breathes gently while waiting.
                val grow = maxOf(level, if (state.phase == Phase.Listening) breathe * 0.12f else 0f)
                drawCircle(accent.copy(alpha = 0.16f), radius = base + (size.minDimension / 2 - base) * grow)
                drawCircle(accent.copy(alpha = 0.5f), radius = base + 6.dp.toPx(), style = Stroke(2.dp.toPx()))
            } else {
                drawCircle(accent.copy(alpha = 0.25f), radius = base + 6.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        }
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = if (state.running) accent else colors.primaryContainer,
            contentColor = if (state.running) colors.surface else colors.onPrimaryContainer,
            modifier = Modifier
                .size(186.dp)
                .semantics { contentDescription = if (state.running) "Stop" else "Start" },
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painterResource(if (state.running) R.drawable.ic_stop else R.drawable.ic_mic),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.running) "STOP" else "START",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: UiState) {
    val text = when {
        !state.running -> "Tap the button, or tap an earbud, to start"
        state.phase == Phase.Preparing -> "Getting the earbud mic ready…"
        state.phase == Phase.Hearing -> "Hearing you…"
        state.phase == Phase.Playing -> "Playing it back…"
        else -> "Listening. Say something"
    }
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}

/** Mic level with a tick showing where speech starts to count. Handy for tuning sensitivity. */
@Composable
private fun LevelMeter(state: UiState) {
    val colors = MaterialTheme.colorScheme
    val active = state.running && state.phase in LISTENING_PHASES
    val level by animateFloatAsState(if (active) meterFraction(state.levelDb) else 0f, tween(60), label = "meter")
    val over = active && state.levelDb >= state.thresholdDb
    BoxWithConstraints(
        Modifier
            .fillMaxWidth(0.8f)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level)
                .background(if (over) colors.tertiary else colors.outline),
        )
        if (active) {
            Box(
                Modifier
                    .offset(x = maxWidth * meterFraction(state.thresholdDb))
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(colors.onSurface),
            )
        }
    }
}

@Composable
private fun MicPermissionCard(permanentlyDenied: Boolean, onRequestMic: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("vctrnr needs the microphone to hear you.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestMic) {
                Text(if (permanentlyDenied) "Open app settings" else "Allow microphone")
            }
        }
    }
}

@Composable
private fun SettingsCard(settings: TrainerSettings, enabled: Boolean, onChange: (TrainerSettings) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Microphone", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.mic == Mic.Earbuds,
                    onClick = { onChange(settings.copy(mic = Mic.Earbuds)) },
                    label = { Text("Earbuds") },
                    enabled = enabled,
                )
                FilterChip(
                    selected = settings.mic == Mic.Phone,
                    onClick = { onChange(settings.copy(mic = Mic.Phone)) },
                    label = { Text("Phone") },
                    enabled = enabled,
                )
            }
            Text(
                when {
                    !enabled -> "Stop to change the microphone."
                    settings.mic == Mic.Earbuds -> "Hands-free. Call-quality mic, and a 1–2 s switch before each playback."
                    else -> "Best quality and instant playback. Keep the phone in front of you."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LabeledValue("Pause before playback", String.format(Locale.getDefault(), "%.1f s", settings.pauseMs / 1000f))
            Slider(
                value = settings.pauseMs.toFloat(),
                onValueChange = { onChange(settings.copy(pauseMs = ((it / 100f).roundToInt() * 100))) },
                valueRange = TrainerSettings.MIN_PAUSE_MS.toFloat()..TrainerSettings.MAX_PAUSE_MS.toFloat(),
            )
            LabeledValue("Mic sensitivity", sensitivityLabel(settings.sensitivity))
            Slider(
                value = settings.sensitivity,
                onValueChange = { onChange(settings.copy(sensitivity = it)) },
                valueRange = 0f..1f,
            )
            Text(
                "Raise sensitivity if quiet speech is missed; lower it if background noise sets it off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FolderLink(onOpenFolder: () -> Unit) {
    TextButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Open recordings folder", style = MaterialTheme.typography.titleSmall)
            Text(
                RecordingStore.DISPLAY_PATH,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun takesLabel(state: UiState): String = when {
    state.takes == 1 -> "1 take saved this session"
    state.takes > 1 -> "${state.takes} takes saved this session"
    state.running -> "You can lock the phone. Takes save automatically"
    else -> " "
}

private fun sensitivityLabel(s: Float) = when {
    s < 0.34f -> "Low"
    s < 0.67f -> "Medium"
    else -> "High"
}

private val LISTENING_PHASES = setOf(Phase.Listening, Phase.Hearing)

/** Map dBFS onto the meter: -75 dB = empty, -15 dB = full. */
private fun meterFraction(db: Float): Float = ((db + 75f) / 60f).coerceIn(0f, 1f)
