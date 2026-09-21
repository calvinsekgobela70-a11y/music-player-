@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ceecept.music.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ceecept.music.data.MusicRepository
import com.ceecept.music.data.Track
import com.ceecept.music.ui.theme.CeeceptColors
import com.ceecept.music.ui.theme.CeeceptMotion
import java.util.Locale
import kotlin.math.roundToInt

fun formatDuration(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}

fun formatFreq(hz: Float): String = if (hz >= 1000f) {
    val k = hz / 1000f
    if (k == k.roundToInt().toFloat()) "${k.roundToInt()}k" else String.format(Locale.US, "%.1fk", k)
} else {
    "${hz.roundToInt()}"
}

/**
 * Base pressable: bouncy scale-down on press with haptic tick.
 * Every control in Ceecept builds on its own variation of this.
 */
@Composable
fun BouncyIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressedScale: Float = 0.8f,
    contentDescription: String? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by androidx.compose.foundation.interaction.collectIsPressedAsState(interaction)
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press"
    )
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false),
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Hero play button: gradient disc, glow shadow, icon morphs between play/pause. */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by androidx.compose.foundation.interaction.collectIsPressedAsState(interaction)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = CeeceptMotion.bouncy(),
        label = "playPress"
    )
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (playing) 16.dp else 8.dp,
                shape = CircleShape,
                ambientColor = CeeceptColors.Accent,
                spotColor = CeeceptColors.Accent
            )
            .clip(CircleShape)
            .background(CeeceptColors.accentGradient())
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = playing, label = "playIcon") { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(size * 0.46f)
            )
        }
    }
}

/** Skip button: press nudges it sideways in the skip direction, then springs back. */
@Composable
fun SkipButton(
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 40.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by androidx.compose.foundation.interaction.collectIsPressedAsState(interaction)
    val nudge by animateFloatAsState(
        targetValue = if (pressed) (if (forward) 10f else -10f) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "skipNudge"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = CeeceptMotion.snappy(),
        label = "skipPress"
    )
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = nudge
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (forward) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious,
            contentDescription = if (forward) "Next" else "Previous",
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}

/** Shuffle toggle: flips 180° on the Y axis whenever state changes. */
@Composable
fun ShuffleButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlipToggleButton(
        active = active,
        onClick = onClick,
        icon = Icons.Filled.Shuffle,
        contentDescription = "Shuffle",
        modifier = modifier
    )
}

/** Repeat toggle: flips + swaps repeat / repeat-one glyph. */
@Composable
fun RepeatButton(mode: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlipToggleButton(
        active = mode != androidx.media3.common.Player.REPEAT_MODE_OFF,
        onClick = onClick,
        icon = if (mode == androidx.media3.common.Player.REPEAT_MODE_ONE) {
            Icons.Filled.RepeatOne
        } else {
            Icons.Filled.Repeat
        },
        contentDescription = "Repeat",
        modifier = modifier
    )
}

@Composable
private fun FlipToggleButton(
    active: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (active) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "flip"
    )
    BouncyIconButton(onClick = onClick, modifier = modifier, contentDescription = contentDescription) {
        val tint = androidx.compose.animation.animateColorAsState(
            targetValue = if (active) CeeceptColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "flipTint"
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.value,
            modifier = Modifier
                .padding(10.dp)
                .size(24.dp)
                .graphicsLayer { rotationY = rotation }
        )
    }
}

/** Apple-style slider: slim track, round thumb, accent fill. */
@Composable
fun CeeceptSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
}

/** Labelled studio parameter row: name + live value over a slider. */
@Composable
fun StudioSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = display,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        CeeceptSlider(value = value, onValueChange = onChange, valueRange = valueRange)
    }
}

/**
 * Logarithmic studio slider for frequency / time parameters.
 * The thumb travels linearly while the value sweeps logarithmically.
 */
@Composable
fun LogStudioSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    display: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val ratio = ((kotlin.math.ln(value / min)) / kotlin.math.ln(max / min)).toFloat().coerceIn(0f, 1f)
    StudioSlider(
        label = label,
        value = ratio,
        valueRange = 0f..1f,
        display = display,
        onChange = { r -> onChange((min * Math.pow((max / min).toDouble(), r.toDouble())).toFloat()) },
        modifier = modifier
    )
}

/** Horizontally scrolling preset chips with a pop animation on the selected one. */
@Composable
fun PresetChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options, key = { it }) { option ->
            val isSelected = option == selected
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.06f else 1f,
                animationSpec = CeeceptMotion.bouncy(),
                label = "chipPop"
            )
            val bg by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                label = "chipBg"
            )
            val fg by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "chipFg"
            )
            Surface(
                color = bg,
                shape = CircleShape,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    modifier = Modifier
                        .clickable { onSelect(option) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
        }
    }
}

/** Segmented tab row with a sliding accent pill. */
@Composable
fun CeeceptTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = index == selected
            val bg by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                label = "tabBg"
            )
            val fg by androidx.compose.animation.animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabFg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .then(
                        if (isSelected) Modifier.shadow(4.dp, RoundedCornerShape(12.dp)) else Modifier
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = title, style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

/** Album artwork with async load, crossfade + scale-in, gradient placeholder. */
@Composable
fun ArtworkView(
    track: Track?,
    repository: MusicRepository?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    thumbSize: Int = 512
) {
    var bitmap by remember(track?.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track?.id) {
        bitmap = if (track != null && repository != null) {
            repository.artwork(track, thumbSize)
        } else {
            null
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF3A3A4A), Color(0xFF1C1C26), Color(0xFF2B1B2E))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = bitmap, label = "art") { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = track?.title,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/** Gain-reduction meter: fills leftwards from 0 dB, springs smoothly. */
@Composable
fun GainMeter(
    grDb: Float,
    label: String,
    modifier: Modifier = Modifier
) {
    val frac by animateFloatAsState(
        targetValue = ((-grDb) / 24f).coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "gr"
    )
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (grDb < -0.05f) String.format(Locale.US, "%.1f dB", grDb) else "0.0 dB",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(CeeceptColors.accentGradientHorizontal())
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}
