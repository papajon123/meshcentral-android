package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable

/**
 * True while the soft keyboard is on screen. Phone screens then give the terminal or text field
 * the space that app, device and tab headers would otherwise take; they return with the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun imeVisible(): Boolean = WindowInsets.isImeVisible
