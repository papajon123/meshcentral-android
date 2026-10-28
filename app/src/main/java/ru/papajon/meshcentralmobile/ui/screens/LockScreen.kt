package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

/**
 * Full-screen lock. The composition is deliberately quiet: a shield/lock mark, one status surface
 * with the masked PIN progress and feedback, then a single keypad surface whose primary action is
 * Unlock. Every key clears the 48 dp touch target, the pressed state has both visual and haptic
 * feedback, and the whole screen scrolls when the font scale grows.
 *
 * Behaviour is unchanged: [onPinSubmit] gets the raw digits and returns whether the PIN was
 * accepted, the entry is capped at 12 digits, zero appends a digit, backspace removes one, and the
 * biometric result is reported through [onBiometricUnlockRequested].
 */
@Composable
fun LockScreen(
    biometricEnabled: Boolean,
    onPinSubmit: (String) -> Boolean,
    onBiometricUnlockRequested: ((Boolean, String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<UiText>(UiText.resource(R.string.lock_message_locked)) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var shakeTrigger by rememberSaveable { mutableIntStateOf(0) }
    val languageTags = LocalConfiguration.current.locales.toLanguageTags()
    // A keyboard left open by the screen underneath would cover the PIN keypad.
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { softwareKeyboard?.hide() }
    LaunchedEffect(languageTags) {
        // A previous OS biometric error cannot be retranslated; show the current-language summary.
        if (message is UiText.Literal) message = UiText.resource(R.string.lock_biometric_failed)
    }

    val spacing = MeshTheme.spacing
    val motion = MeshTheme.motion
    val shake = remember { Animatable(0f) }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger == 0) return@LaunchedEffect
        // Springs, not a tween: an interrupted shake retargets from its current offset.
        listOf(-14f, 14f, -9f, 9f, -4f, 0f).forEach { target ->
            shake.animateTo(targetValue = target, animationSpec = motion.effectFast)
        }
    }

    val submitPin: () -> Unit = {
        if (onPinSubmit(pin)) {
            pin = ""
            failed = false
        } else {
            message = UiText.resource(R.string.lock_invalid_pin)
            failed = true
            shakeTrigger += 1
        }
    }

    val requestBiometric: () -> Unit = {
        onBiometricUnlockRequested { success, error ->
            failed = !success
            message = if (success) {
                UiText.resource(R.string.lock_unlocked)
            } else {
                error?.let(UiText::literal) ?: UiText.resource(R.string.lock_biometric_failed)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(spacing.xl))
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            LockHero()

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.lock_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            PinStatus(
                filled = pin.length,
                message = message.asString(),
                failed = failed,
                shakeOffset = shake.value,
            )

            KeypadPanel(
                biometricEnabled = biometricEnabled,
                onDigit = { digit ->
                    pin = (pin + digit).take(PIN_MAX_LENGTH)
                    failed = false
                },
                onBackspace = {
                    pin = pin.dropLast(1)
                    failed = false
                },
                onBiometric = requestBiometric,
                onUnlock = submitPin,
            )
        }
        Spacer(modifier = Modifier.height(spacing.xxl))
    }
}

/** Small identity mark; the PIN entry and keypad remain the focus. */
@Composable
private fun LockHero() {
    Icon(
        imageVector = Icons.Outlined.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
    )
}

@Composable
private fun PinStatus(
    filled: Int,
    message: String,
    failed: Boolean,
    shakeOffset: Float,
) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = MeshTheme.motion.sizeDefault),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            PinProgress(
                filled = filled,
                modifier = Modifier.offset(x = shakeOffset.dp),
            )
            LockMessage(message = message, failed = failed)
        }
    }
}

/** Twelve fixed slots make the cap readable; the merged node announces the exact count. */
@Composable
private fun PinProgress(filled: Int, modifier: Modifier = Modifier) {
    val spacing = MeshTheme.spacing
    val progressLabel = stringResource(R.string.lock_pin_progress, filled)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = progressLabel },
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(maxOf(4, filled)) { index ->
            val isFilled = index < filled
            val dotScale by animateFloatAsState(
                targetValue = if (isFilled) 1f else 0.65f,
                animationSpec = MeshTheme.motion.effectFast,
                label = "pin-dot-scale",
            )
            val dotColor by animateColorAsState(
                targetValue = if (isFilled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = MeshTheme.motion.effectColor,
                label = "pin-dot-color",
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(dotScale)
                    .clip(PillShape)
                    .background(dotColor),
            )
        }
    }
}

/** Single feedback line: neutral hint before the first attempt, error styling after a rejection. */
@Composable
private fun LockMessage(message: String, failed: Boolean) {
    val spacing = MeshTheme.spacing
    val contentColor by animateColorAsState(
        targetValue = if (failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MeshTheme.motion.effectColor,
        label = "lock-message-color",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Lock,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** One keypad surface: the digit grid, optional biometrics, and the only filled action (Unlock). */
@Composable
private fun KeypadPanel(
    biometricEnabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
    onUnlock: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    val deleteDigitLabel = stringResource(R.string.lock_delete_digit)
    val biometricLabel = stringResource(R.string.lock_action_unlock_biometric)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            keypadRows.forEach { digits ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    digits.forEach { digit ->
                        LockKey(
                            modifier = Modifier.weight(1f),
                            onClick = { onDigit(digit) },
                        ) {
                            Text(text = digit, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (biometricEnabled) {
                    LockKey(
                        modifier = Modifier.weight(1f),
                        label = biometricLabel,
                        onClick = onBiometric,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                LockKey(
                    modifier = Modifier.weight(1f),
                    onClick = { onDigit("0") },
                ) {
                    Text(text = "0", style = MaterialTheme.typography.headlineSmall)
                }

                LockKey(
                    modifier = Modifier.weight(1f),
                    label = deleteDigitLabel,
                    onClick = onBackspace,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Backspace,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.xs))

            Button(
                onClick = onUnlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = PillShape,
            ) {
                Text(
                    text = stringResource(R.string.lock_action_unlock),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Rounded key with a spring press state, color change and a light haptic tick. */
@Composable
private fun LockKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val spacing = MeshTheme.spacing
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) MeshTheme.motion.pressScale else 1f,
        animationSpec = MeshTheme.motion.effectFast,
        label = "lock-key-press",
    )
    val container by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MeshTheme.motion.effectColor,
        label = "lock-key-container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = MeshTheme.motion.effectColor,
        label = "lock-key-content",
    )

    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .scale(scale)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .semantics {
                label?.let { contentDescription = it }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .padding(horizontal = spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

private const val PIN_MAX_LENGTH = 12

private val keypadRows = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
)
