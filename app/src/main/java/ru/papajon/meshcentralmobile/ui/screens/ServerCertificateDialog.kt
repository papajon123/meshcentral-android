package ru.papajon.meshcentralmobile.ui.screens

import android.text.BidiFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.style.TextDirection
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateIssuerExplanation
import ru.papajon.meshcentralmobile.mesh.tls.formatFingerprint
import ru.papajon.meshcentralmobile.mesh.MeshConnectionError
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ServerCertificateDialog(
    failure: ServerCertificateFailure,
    confirmingReplacement: Boolean,
    onTrust: () -> Unit,
    onRequestReplacement: () -> Unit,
    onDismiss: () -> Unit,
) {
    val changed = failure.kind == ServerCertificateFailureKind.Changed
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    val bidi = BidiFormatter.getInstance(locale)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(when {
            !failure.canApprove -> R.string.tls_blocked_title
            confirmingReplacement -> R.string.tls_replace_title
            changed -> R.string.tls_changed_title
            else -> R.string.tls_untrusted_title
        })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md)) {
                Text(stringResource(when {
                    !failure.canApprove -> MeshConnectionError.describeCertificate(failure).id
                    changed -> R.string.tls_replace_warning
                    else -> R.string.tls_compare
                }))
                if (failure.canApprove) {
                    if (failure.hostnameMismatch) {
                        Text(stringResource(R.string.tls_hostname_warning), color = MaterialTheme.colorScheme.error)
                    }
                    when (failure.issuerExplanation) {
                        ServerCertificateIssuerExplanation.MeshCentralRoot -> Text(stringResource(R.string.tls_issuer_meshcentral))
                        ServerCertificateIssuerExplanation.UnknownAuthority -> Text(stringResource(R.string.tls_issuer_unknown))
                        null -> Unit
                    }
                }
                Text(stringResource(R.string.tls_server, bidi.unicodeWrap(failure.serverAddress)))
                Text(stringResource(R.string.tls_subject, bidi.unicodeWrap(failure.subject)))
                Text(stringResource(R.string.tls_validity,
                    dateFormat.format(Date(failure.notBeforeMillis)), dateFormat.format(Date(failure.notAfterMillis))))
                if (changed) {
                    failure.previousSpkiSha256?.let { previousPin ->
                        Text(stringResource(R.string.tls_previous_fingerprint), style = MaterialTheme.typography.labelLarge)
                        ServerKeyFingerprint(previousPin)
                    }
                }
                Text(stringResource(if (changed) R.string.tls_new_fingerprint else R.string.tls_fingerprint),
                    style = MaterialTheme.typography.labelLarge)
                ServerKeyFingerprint(failure.spkiSha256)
                Text(stringResource(R.string.tls_certificate_fingerprint), style = MaterialTheme.typography.labelLarge)
                ServerKeyFingerprint(failure.certificateSha256)
                if (failure.canApprove) {
                    Text(stringResource(R.string.tls_verify_instructions))
                    SelectionContainer {
                        Text(stringResource(R.string.tls_openssl_command),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
                    }
                    Text(stringResource(R.string.tls_independent_warning))
                }
            }
        },
        confirmButton = {
            if (failure.canApprove) TextButton(onClick = if (changed && !confirmingReplacement) onRequestReplacement else onTrust, shape = PillShape) {
                Text(stringResource(when {
                    confirmingReplacement -> R.string.tls_confirm_replace
                    changed -> R.string.tls_replace
                    else -> R.string.tls_trust
                }))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = PillShape) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ServerKeyFingerprint(value: String) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
    val measurer = rememberTextMeasurer()
    SelectionContainer {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val byteWidth = measurer.measure("00:", style).size.width.coerceAtLeast(1)
            val bytesPerLine = (constraints.maxWidth / byteWidth).coerceIn(1, 32)
            // Keep each hexadecimal byte intact, including narrow windows and enlarged fonts.
            val displayed = value.chunked(bytesPerLine * 2).joinToString(":\n") { formatFingerprint(it) }
            Text(displayed, style = style, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                text = AnnotatedString(formatFingerprint(value))
            })
        }
    }
}

@Composable
internal fun ForgetServerKeyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tls_forget_title)) },
        text = { Text(stringResource(R.string.tls_forget_warning)) },
        confirmButton = { TextButton(onClick = onConfirm, shape = PillShape) { Text(stringResource(R.string.tls_forget)) } },
        dismissButton = { TextButton(onClick = onDismiss, shape = PillShape) { Text(stringResource(R.string.cancel)) } },
    )
}
