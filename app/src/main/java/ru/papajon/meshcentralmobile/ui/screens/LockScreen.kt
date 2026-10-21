package ru.papajon.meshcentralmobile.ui.screens

import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.localization.UiText
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

/**
 * Full-screen lock. The screen is deliberately quiet: an identity block, the digit indicators, one
 * line of feedback and a large keypad. Everything a finger can hit is at least 60 dp tall, and the
 * only filled button is the unlock action itself.
 *
 * Behaviour is unchanged from the original screen: [onPinSubmit] gets the raw digits and returns
 * whether the PIN was accepted, the entry is capped at 12 digits, and the biometric result is
 * reported through the callback passed to [onBiometricUnlockRequested].
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
    LaunchedEffect(languageTags) {
        // A previous OS biometric error cannot be retranslated; show the current-language summary.
        if (message is UiText.Literal) message = UiText.resource(R.string.lock_biometric_failed)
    }

    val spacing = MeshTheme.spacing
    val motion = MeshTheme.motion
    val shake = remember { Animatable(0f) }
    val deleteDigitLabel = stringResource(R.string.lock_delete_digit)

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
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Spacer(modifier = Modifier.height(spacing.xl))

        IconBadge(
            icon = Icons.Outlined.Lock,
            size = 64.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        PinDots(
            filled = pin.length,
            modifier = Modifier.offset(x = shake.value.dp),
        )

        LockMessage(message = message.asString(), failed = failed)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
            ).forEach { digits ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    digits.forEach { digit ->
                        LockKey(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                pin = (pin + digit).take(12)
                                failed = false
                            },
                        ) {
                            Text(
                                text = digit,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                // Biometrics live under the primary action, so the keypad keeps its 3x3 rhythm and
                // the visible slot in the bottom row stays empty.
                Spacer(modifier = Modifier.weight(1f))

                LockKey(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        pin = (pin + "0").take(12)
                        failed = false
                    },
                ) {
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                LockKey(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = deleteDigitLabel },
                    onClick = {
                        pin = pin.dropLast(1)
                        failed = false
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Backspace,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Button(
            onClick = submitPin,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            shape = PillShape,
        ) {
            Text(
                text = stringResource(R.string.lock_action_unlock),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        if (biometricEnabled) {
            OutlinedButton(
                onClick = requestBiometric,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = PillShape,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    text = stringResource(R.string.lock_action_unlock_biometric),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xxl))
    }
}

/** Digit indicators: filled dots grow while the PIN is typed, plus one empty slot as an affordance. */
@Composable
private fun PinDots(filled: Int, modifier: Modifier = Modifier) {
    val spacing = MeshTheme.spacing
    val slots = (filled + 1).coerceIn(4, 12)
    val progressLabel = stringResource(R.string.lock_pin_progress, filled)
    Row(
        modifier = modifier
            .animateContentSize()
            .semantics(mergeDescendants = true) { contentDescription = progressLabel },
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(slots) { index ->
            val isFilled = index < filled
            val dotScale by animateFloatAsState(
                targetValue = if (isFilled) 1f else 0.6f,
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
                    .size(14.dp)
                    .scale(dotScale)
                    .clip(PillShape)
                    .background(dotColor),
            )
        }
    }
}

/** Single feedback line: neutral hint before the first attempt, error styling after a rejection. */
@Composable
private fun LockMessage(message: String, failed: Boolean, modifier: Modifier = Modifier) {
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
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Lock,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** Pill-shaped keypad key with a spring press state; min 60 dp tall so it clears the 48 dp target. */
@Composable
private fun LockKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val spacing = MeshTheme.spacing
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
    Row(
        modifier = modifier
            .heightIn(min = 60.dp)
            .scale(scale)
            .clip(PillShape)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
