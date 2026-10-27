package ru.papajon.meshcentralmobile.integration

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R

/**
 * Synchronous launcher-shortcut writer. Production callers enqueue it through
 * [LauncherCoordinator] so shortcut and JSON work happens on IO; the worker variants stay
 * synchronous where a background thread already owns the call.
 */
object DeviceShortcuts {
    const val ACTION_OPEN = "ru.papajon.meshcentralmobile.OPEN_DEVICE"
    internal const val PREFIX = ShortcutRevocation.PREFIX
    private const val MAX_DYNAMIC = 4

    fun intent(context: Context, token: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN).setData(Uri.Builder().scheme("mesh-device").authority("open").appendPath(token).build())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun token(intent: Intent): String? = intent.takeIf { it.action == ACTION_OPEN }?.data?.let {
        if (it.scheme == "mesh-device" && it.host == "open" && it.pathSegments.size == 1 && it.query == null && it.fragment == null)
            it.lastPathSegment?.takeIf { value -> value.matches(Regex("[a-f0-9-]{36}")) } else null
    }

    /**
     * A normal A -> B switch must not disable A's pinned copies: revocation only applies when a
     * token is absent from every retained profile snapshot. Unchanged projections are not written.
     */
    fun sync(context: Context, snapshot: LauncherSnapshot? = LauncherStore(context).read(),
        hidden: Boolean = LauncherPrivacy.hidden(context)) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (hidden) {
            hide(context, manager)
            return
        }
        val store = LauncherStore(context)
        // Keep the passed snapshot in the union: it may be fresher than (or not yet written by)
        // the active catalog, and no profile token may be revoked on a stale input.
        val retained = if (snapshot == null) store.all() else store.all() + snapshot
        visible(context, manager, snapshot, retained)
    }

    /**
     * Removes the named profile (or all profiles when null) and disables pinned copies whose
     * tokens no longer belong to any retained snapshot, including under shortcut rate limiting.
     */
    fun clear(context: Context, profileId: String? = null) {
        val store = LauncherStore(context)
        if (profileId == null || store.read()?.profileId == profileId) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("favorites-widget-refresh")
        }
        store.clear(profileId)
        sync(context)
        FavoritesWidget.updateAll(context)
    }

    private fun visible(context: Context, manager: ShortcutManager, active: LauncherSnapshot?,
        retained: List<LauncherSnapshot>) {
        val byToken = retained.flatMap { it.devices }.associateBy { it.token }
        val tokens = byToken.keys
        val existing = (manager.dynamicShortcuts + manager.pinnedShortcuts)
            .distinctBy { it.id }
            .filter { it.id.startsWith(PREFIX) }
            .map { ShortcutRevocation.Entry(it.id, it.isEnabled) }
        val revoked = ShortcutRevocation.revokedEnabled(existing, tokens)
        // Disable first: this path is not rate limited and must survive a rate-limited rejection
        // of a subsequent dynamic-shortcut replacement after account change/logout/deletion.
        if (revoked.isNotEmpty()) manager.disableShortcuts(revoked, context.getString(R.string.integration_unavailable))

        val pinned = manager.pinnedShortcuts.filter {
            it.id.startsWith(PREFIX) && ShortcutRevocation.isRetained(it.id, tokens)
        }
        val labelUpdates = pinned.mapNotNull { info ->
            val token = ShortcutRevocation.tokenOf(info.id) ?: return@mapNotNull null
            val device = byToken[token] ?: return@mapNotNull null
            if (info.shortLabel?.toString() == device.name && info.longLabel?.toString() == device.name) null
            else shortcut(context, info.id, device, device.recentRank.coerceAtLeast(0))
        }
        if (labelUpdates.isNotEmpty()) runCatching { manager.updateShortcuts(labelUpdates) }
        val reEnabled = ShortcutRevocation.reenabled(pinned.map { ShortcutRevocation.Entry(it.id, it.isEnabled) }, tokens)
        if (reEnabled.isNotEmpty()) runCatching { manager.enableShortcuts(reEnabled) }

        val entries = active?.devices.orEmpty().filter { it.recentRank >= 0 }.sortedBy { it.recentRank }
            .take(minOf(MAX_DYNAMIC, manager.maxShortcutCountPerActivity))
        val desired = entries.mapIndexed { index, device -> shortcut(context, PREFIX + device.token, device, index) }
        val current = manager.dynamicShortcuts.filter { it.id.startsWith(PREFIX) }
        if (desired.isEmpty()) {
            if (current.isNotEmpty()) manager.removeDynamicShortcuts(current.map { it.id })
            return
        }
        val desiredIds = desired.map { it.id }.toSet()
        val desiredDisabled = (manager.dynamicShortcuts + manager.pinnedShortcuts)
            .filter { it.id in desiredIds && !it.isEnabled }.map { it.id }
        if (desiredDisabled.isNotEmpty()) runCatching { manager.enableShortcuts(desiredDisabled) }
        val unchanged = current.sortedBy { it.rank }.map { projection(it) } == desired.map { projection(it) }
        if (!unchanged) {
            // If the system rate-limits this write, already disabled stale tokens stay unusable.
            runCatching { manager.dynamicShortcuts = desired }
        }
    }

    private fun hide(context: Context, manager: ShortcutManager) {
        val dynamic = manager.dynamicShortcuts.filter { it.id.startsWith(PREFIX) }
        if (dynamic.isNotEmpty()) {
            val ids = dynamic.map { it.id }
            // If removal is rejected, disabling still keeps the entries from opening.
            runCatching { manager.removeDynamicShortcuts(ids) }
                .onFailure { runCatching { manager.disableShortcuts(ids, context.getString(R.string.integration_private_hidden)) } }
        }
        val pinned = manager.pinnedShortcuts.filter { it.id.startsWith(PREFIX) }
        if (pinned.isEmpty()) return
        // Only tokens retained by every cached profile may stay reachable while hidden. A token
        // absent from LauncherStore.all() is revoked: it is never re-enabled, even if disabled
        // before this privacy transition.
        val retained = LauncherStore(context).all().flatMap { snapshot -> snapshot.devices.map { it.token } }.toSet()
        val generic = context.getString(R.string.integration_private_device)
        val updates = pinned.mapNotNull { info ->
            val token = ShortcutRevocation.tokenOf(info.id)
            val valid = token != null && token in retained
            val opaqueToken = token.takeIf { valid }
            val nextIntent = if (opaqueToken != null) intent(context, opaqueToken)
                else Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            val intentMatches = if (opaqueToken != null) token(info.intent ?: Intent()) == opaqueToken
                else token(info.intent ?: Intent()) == null
            if (info.shortLabel?.toString() == generic && info.longLabel?.toString() == generic && intentMatches)
                return@mapNotNull null
            ShortcutInfo.Builder(context, info.id).setShortLabel(generic).setLongLabel(generic).setRank(0)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
                .setIntent(nextIntent).build()
        }
        if (updates.isNotEmpty()) runCatching { manager.updateShortcuts(updates) }
        val entries = pinned.map { ShortcutRevocation.Entry(it.id, it.isEnabled) }
        val reEnable = ShortcutRevocation.reenabled(entries, retained)
        if (reEnable.isNotEmpty()) runCatching { manager.enableShortcuts(reEnable) }
        // A revoked pin that is already disabled stays untouched: re-disabling it would replace the
        // original reason. Enabled ones get the same unavailable message as the visible path.
        val revoked = ShortcutRevocation.revokedEnabled(entries, retained)
        if (revoked.isNotEmpty()) {
            runCatching { manager.disableShortcuts(revoked, context.getString(R.string.integration_unavailable)) }
        }
    }

    private fun projection(info: ShortcutInfo): Triple<String, String?, Int> =
        Triple(info.id, info.shortLabel?.toString(), info.rank)

    private fun shortcut(context: Context, id: String, device: LauncherDevice, rank: Int): ShortcutInfo =
        ShortcutInfo.Builder(context, id).setShortLabel(device.name).setLongLabel(device.name).setRank(rank)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
            .setIntent(intent(context, device.token)).build()
}
