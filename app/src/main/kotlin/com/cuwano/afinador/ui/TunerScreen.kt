package com.cuwano.afinador.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.cuwano.afinador.dsp.SensitivityProfile
import com.cuwano.afinador.tuner.TunerUiState

object TunerScreenTags {
    const val Root = "tuner_root"
    const val Note = "tuner_note"
    const val CentsGauge = "cents_gauge"
    const val PermissionAction = "permission_action"
    const val Sensitivity = "sensitivity_slider"
    const val SensitivityLabel = "sensitivity_label"
}

private val DeepNavy = Color(0xFF071019)
private val CharcoalNavy = Color(0xFF111C27)
private val CardNavy = Color(0xFF172531)
private val MutedText = Color(0xFFA8B7C4)
private val Cyan = Color(0xFF65D8E5)
private val TunedGreen = Color(0xFF62E6A7)
private val Amber = Color(0xFFFFC66D)

private const val STALE_ALPHA = 0.55f

@Composable
fun TunerScreen(
    state: TunerUiState,
    sensitivity: SensitivityProfile,
    onSensitivityChange: (SensitivityProfile) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(CharcoalNavy, DeepNavy),
                    ),
                )
                .testTag(TunerScreenTags.Root),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                val compact = maxHeight < 620.dp
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Header(compact)
                    Spacer(Modifier.height(if (compact) 16.dp else 28.dp))
                    when (state) {
                        is TunerUiState.Reading -> ReadingPanel(state, compact)
                        TunerUiState.ListeningSilence -> SilencePanel(compact)
                        TunerUiState.PermissionNeeded -> MessagePanel(
                            title = "Se necesita acceso al micrófono",
                            body = "Permite el acceso para escuchar y afinar tu instrumento.",
                            action = "Permitir micrófono",
                            actionDescription = "Solicitar permiso del micrófono",
                            onAction = onRequestPermission,
                        )
                        TunerUiState.PermissionDeniedPermanently -> MessagePanel(
                            title = "El permiso del micrófono está desactivado",
                            body = "Actívalo en los ajustes de la aplicación para usar el afinador.",
                            action = "Abrir ajustes",
                            actionDescription = "Abrir ajustes de la aplicación",
                            onAction = onOpenSettings,
                        )
                        is TunerUiState.AudioError -> MessagePanel(
                            title = "Error de audio",
                            body = state.message,
                        )
                    }
                    Spacer(Modifier.height(if (compact) 18.dp else 28.dp))
                    SensitivityControl(sensitivity, onSensitivityChange)
                }
            }
        }
    }
}

@Composable
private fun Header(compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Afinador cromático",
            color = Color.White,
            fontSize = if (compact) 21.sp else 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Afinación precisa en tiempo real",
            color = MutedText,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ReadingPanel(state: TunerUiState.Reading, compact: Boolean) {
    Card(
        // A held note stays on screen but is dimmed, so a stale reading can never
        // be mistaken for a live one.
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (state.isLive) 1f else STALE_ALPHA),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavy),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = if (compact) 18.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusPill(state)
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {}
                    .testTag(TunerScreenTags.Note),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.noteName,
                    color = Color.White,
                    fontSize = if (compact) 72.sp else 92.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = if (compact) 76.sp else 96.sp,
                )
                Text(
                    text = state.octave.toString(),
                    color = Cyan,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 11.dp, start = 4.dp),
                )
            }
            Text(
                text = state.centsText,
                color = if (state.isTuned) TunedGreen else Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(if (compact) 14.dp else 24.dp))
            CentsGauge(state.cents)
            Spacer(Modifier.height(if (compact) 16.dp else 26.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard("Frecuencia", state.frequencyText, Modifier.weight(1f))
                MetricCard("Confianza", state.confidenceText, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusPill(state: TunerUiState.Reading) {
    val text = state.statusText
    val accent = when {
        state.isStale -> MutedText
        state.isTuned -> TunedGreen
        else -> Amber
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(8.dp))
        Text(text, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CentsGauge(cents: Double) {
    val clamped = cents.coerceIn(-50.0, 50.0).toFloat()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−50", color = MutedText, fontSize = 11.sp)
            Text("0", color = MutedText, fontSize = 11.sp)
            Text("+50", color = MutedText, fontSize = 11.sp)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .semantics {
                    contentDescription = "Desviación de afinación, $clamped cents"
                    progressBarRangeInfo = ProgressBarRangeInfo(clamped, -50f..50f)
                }
                .testTag(TunerScreenTags.CentsGauge),
        ) {
            val railY = size.height * 0.63f
            val railHeight = 7.dp.toPx()
            drawRoundRect(
                color = Color(0xFF324350),
                topLeft = Offset(0f, railY - railHeight / 2f),
                size = Size(size.width, railHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(railHeight),
            )
            val zoneWidth = size.width * 0.12f
            drawRoundRect(
                color = TunedGreen.copy(alpha = 0.55f),
                topLeft = Offset(size.width / 2f - zoneWidth / 2f, railY - railHeight / 2f),
                size = Size(zoneWidth, railHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(railHeight),
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width / 2f, railY - 8.dp.toPx()),
                end = Offset(size.width / 2f, railY + 8.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
                alpha = 0.45f,
            )
            val needleX = ((clamped + 50f) / 100f) * size.width
            drawLine(
                color = if (kotlin.math.abs(clamped) <= 5f) TunedGreen else Amber,
                start = Offset(needleX, 2.dp.toPx()),
                end = Offset(needleX, railY + 10.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFF0E1A24),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(label.uppercase(), color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                value,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun SensitivityControl(
    sensitivity: SensitivityProfile,
    onSensitivityChange: (SensitivityProfile) -> Unit,
) {
    val selectedIndex = SensitivityUi.indexOf(sensitivity)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Sensibilidad",
            color = MutedText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { onSensitivityChange(SensitivityUi.profileAt(it)) },
            valueRange = 0f..SensitivityUi.levels.lastIndex.toFloat(),
            steps = SensitivityUi.sliderSteps,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TunerScreenTags.Sensitivity),
        )
        // The five level names are always readable as tick labels under the
        // slider, so the player can see every option and not only the current
        // one.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SensitivityUi.labels.forEachIndexed { index, label ->
                Text(
                    text = label,
                    color = if (index == selectedIndex) Cyan else MutedText,
                    fontSize = 11.sp,
                    fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.testTag("SensitivityTick_$index"),
                )
            }
        }
        Text(
            text = SensitivityUi.label(sensitivity),
            color = Cyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag(TunerScreenTags.SensitivityLabel),
        )
    }
}

@Composable
private fun SilencePanel(compact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavy),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = if (compact) 42.dp else 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(Cyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("♪", color = Cyan, fontSize = 36.sp)
            }
            Text(
                "Toca una nota sostenida",
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 22.dp),
            )
            Text(
                "Acerca tu instrumento al micrófono",
                color = MutedText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MessagePanel(
    title: String,
    body: String,
    action: String? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavy),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                color = MutedText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (action != null && actionDescription != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .padding(top = 26.dp)
                        .semantics { contentDescription = actionDescription }
                        .testTag(TunerScreenTags.PermissionAction),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan,
                        contentColor = DeepNavy,
                    ),
                ) {
                    Text(action, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
