package ru.papajon.meshcentralmobile.mesh

import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.chat.ChatPhase
import ru.papajon.meshcentralmobile.mesh.chat.ChatProtocol

/**
 * Real HTTPS MockWebServer with a paired administrator relay and remote browser.
 *
 * Covers the contract added with the new controller: pin/authcookie isolation, sign-out/revoke/pin
 * closure, agent offline without closing the browser chat, silent control/relay recovery, the real
 * 15 s unpaired timeout and 5 s invitation cooldown, and unread state driven by screen visibility.
 */
class ChatClientTest {
    @Test fun fileOfferReceivesCancellationOnBrowserSide() = fixture { f ->
        f.login(); val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend("""{"action":"file","id":0.25,"name":"fixture.txt","size":10}""")
        f.await { f.browserFrames.any { JSONObject(it).optString("action") == "fileUploadCancel" } }
        val reply = JSONObject(f.browserFrames.first { JSONObject(it).optString("action") == "fileUploadCancel" })
        assertEquals(0.25, reply.getDouble("id"), 0.0)
        assertTrue(chat.state.value.messages.isEmpty())
    }
    @Test fun pinnedRelayUsesAuthCookieAndForwardsBothWays() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        val request = f.adminRequests.single()
        assertEquals(ChatRelayFixture.ADMIN_COOKIE, request.requestUrl!!.queryParameter("auth"))
        assertEquals(
            "meshmessenger/${ChatRelayFixture.NODE_ID}/${ChatRelayFixture.USER_ID}",
            request.requestUrl!!.queryParameter("id"),
        )
        assertNull(request.getHeader("x-meshauth"))
        assertFalse(request.getHeader("Cookie").orEmpty().contains("twofactor"))
        assertEquals(1, f.invites.get())
        assertEquals(ChatRelayFixture.NODE_ID, f.inviteNodeIds.single())

        f.onMain { chat.setDraft("Message"); assertTrue(chat.send()) }
        f.await { f.browserFrames.any { JSONObject(it).optString("msg") == "Message" } }
        f.browserSend(ChatProtocol.message("Reply"))
        f.await { chat.state.value.messages.size == 2 }
        assertEquals(listOf(true, false), chat.state.value.messages.map { it.outgoing })
        assertEquals(listOf("Message", "Reply"), chat.state.value.messages.map { it.text })
        assertEquals("", chat.state.value.draft)
    }

    @Test fun logoutClosesChatAndErasesHistory() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend(ChatProtocol.message("Private"))
        f.await { chat.state.value.messages.size == 1 }

        f.onMain { f.client.logout(clearStoredCredentials = true) }
        f.await { chat.state.value.phase == ChatPhase.Closed }
        assertTrue(chat.state.value.messages.isEmpty())
        f.onMain { chat.requestWindow(); assertFalse(chat.send()) }
        f.await { chat.state.value.phase == ChatPhase.Closed }
        assertEquals(1, f.invites.get())
    }

    @Test fun revokedChatGrantClosesRelayEvenWhenPeerIsSilent() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        // Remote control alone is not chat: CHAT_NOTIFY and a desktop/view right are required.
        f.publishMeshes(MeshRights.REMOTE_CONTROL)
        f.await { chat.state.value.phase == ChatPhase.Closed }
        assertTrue(chat.state.value.messages.isEmpty())
    }

    @Test fun changedPinInvalidatesChatAndDoesNotReuseItsConnection() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.onMain { f.client.setPinnedServerKey("0".repeat(64)) }
        f.await { chat.state.value.phase == ChatPhase.Closed }
        f.onMain { chat.requestWindow() }
        f.await { chat.state.value.phase == ChatPhase.Closed }
        assertEquals(1, f.adminRequests.size)
    }

    @Test fun offlineAgentKeepsPairedBrowserChatAndHistory() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend(ChatProtocol.message("Before offline"))
        f.await { chat.state.value.messages.size == 1 }
        // Let the initial snapshot responses settle so no older conn=1 frame can overwrite this.
        Thread.sleep(500)

        // A full nodes snapshot with conn=0 is authoritative: the agent is known offline.
        f.publishNodes(conn = 0)
        f.await {
            f.client.clientState.value.devices.single().let { !it.online && it.connectivityKnown }
        }
        assertEquals(ChatPhase.Connected, chat.state.value.phase)

        f.onMain { chat.setDraft("Still here"); assertTrue(chat.send()) }
        f.await { f.browserFrames.any { JSONObject(it).optString("msg") == "Still here" } }
        f.browserSend(ChatProtocol.message("After offline"))
        f.await { chat.state.value.messages.size == 3 }
        assertEquals(
            listOf("Before offline", "Still here", "After offline"),
            chat.state.value.messages.map { it.text },
        )
        assertEquals(1, f.invites.get())
    }

    @Test fun controlLossKeepsHistoryAndReconnectsWithoutInvite() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend(ChatProtocol.message("Before control loss"))
        f.await { chat.state.value.messages.size == 1 }

        val controlsBefore = f.controls.size
        f.dropControl()
        f.await { f.controls.size == controlsBefore + 1 }
        f.await { f.client.clientState.value.devicesCurrent }
        f.await { chat.state.value.phase == ChatPhase.Connected }
        assertEquals(listOf("Before control loss"), chat.state.value.messages.map { it.text })
        assertEquals(1, f.invites.get())

        f.browserSend(ChatProtocol.message("After control loss"))
        f.await { chat.state.value.messages.size == 2 }
        f.onMain { chat.setDraft("Still paired"); assertTrue(chat.send()) }
        f.await { f.browserFrames.any { JSONObject(it).optString("msg") == "Still paired" } }
        assertEquals(1, f.invites.get())
    }

    @Test fun phoneNetworkReturnRefreshesControlCookieAndRepairsPairWithoutInvite() = fixture { f ->
        f.login(); val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend(ChatProtocol.message("Before network change"))
        f.await { chat.state.value.messages.size == 1 }
        val controls = f.controls.size
        f.reopenBrowserAfterLoss = true
        f.onMain { f.client.onNetworkLost() }
        Thread.sleep(750)
        f.onMain { f.client.onNetworkAvailable() }
        f.await(12_000) { f.controls.size > controls && chat.state.value.phase == ChatPhase.Connected }
        assertEquals(1, f.invites.get())
        assertEquals("Before network change", chat.state.value.messages.first().text)
        f.browserSend(ChatProtocol.message("After network change"))
        f.await { chat.state.value.messages.size == 2 }
    }

    @Test fun unpairedRelayTimesOutThenManualRetrySendsOneNewInvite() = fixture(autoOpen = false) { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Waiting }
        f.await { f.invites.get() == 1 }
        assertEquals(1, f.adminRequests.size)

        // The relay is kept for the real MeshCentral 15 s; the fixture then closes the lone peer.
        f.await(ChatRelayFixture.UNPAIRED_TIMEOUT_MILLIS + 10_000) {
            chat.state.value.phase == ChatPhase.NotOpened
        }
        assertTrue(chat.state.value.canRequest)
        assertEquals(1, f.invites.get())

        // A passive chat must not tear down control/tools on an otherwise healthy network handoff.
        val controlsBeforeRetry = f.controls.size
        f.requiredAdminCookie = "synthetic-cookie-new-network"
        f.issuedAdminCookie = f.requiredAdminCookie
        f.onMain { f.client.onNetworkAvailable() }
        Thread.sleep(500)
        assertEquals(controlsBeforeRetry, f.controls.size)
        assertTrue(f.client.clientState.value.devicesCurrent)

        f.autoOpen = true
        val requestedAt = System.currentTimeMillis()
        f.onMain { chat.requestWindow() }
        // The manual repeat probes for 2.5 s before asking MeshCentral for another window.
        Thread.sleep(1_500)
        assertEquals(1, f.invites.get())
        f.await(6_000) { f.invites.get() == 2 }
        assertTrue(
            "The manually requested window must wait for the probe",
            System.currentTimeMillis() - requestedAt >= 2_400,
        )
        f.await(6_000) { chat.state.value.phase == ChatPhase.Connected }
        assertEquals(controlsBeforeRetry + 1, f.controls.size)
        assertEquals("synthetic-cookie-new-network", f.adminRequests.last().requestUrl!!.queryParameter("auth"))
        assertEquals(2, f.adminRequests.size)
        assertEquals(2, f.invites.get())
        assertEquals(1, f.browserConnections.get())
    }

    @Test fun immediateRepeatDuringCooldownSendsNoSecondInvite() = fixture { f ->
        f.login()
        // Hold the browser back so the manual repeat lands while the controller is Waiting.
        f.browserDelayMillis = 2_000
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Waiting }
        f.await { f.invites.get() == 1 }
        assertFalse(chat.state.value.canRequest)

        f.onMain { chat.requestWindow() }
        Thread.sleep(500)
        assertEquals("The real 5 s cooldown must reject the immediate repeat", 1, f.invites.get())
        assertEquals(1, f.adminRequests.size)

        f.await { chat.state.value.phase == ChatPhase.Connected }
        assertEquals(1, f.invites.get())
        assertEquals(1, f.browserConnections.get())
    }
    @Test fun passiveNetworkChangeRefreshesCookieOnlyOnExplicitRequest() = fixture { f ->
        f.login(); val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend("""{"action":"close"}""")
        f.await { chat.state.value.phase == ChatPhase.Failed && chat.state.value.canRequest }
        val controls = f.controls.size
        f.requiredAdminCookie = "synthetic-cookie-after-handoff"
        f.issuedAdminCookie = f.requiredAdminCookie
        f.onMain { f.client.onNetworkAvailable() }
        // A late cookie on the old control socket must not clear the network invalidation.
        f.publishCookie(ChatRelayFixture.ADMIN_COOKIE)
        Thread.sleep(500)
        assertEquals(controls, f.controls.size)
        f.onMain { chat.requestWindow() }
        f.await { chat.state.value.phase == ChatPhase.Connected }
        assertEquals(controls + 1, f.controls.size)
        assertEquals("synthetic-cookie-after-handoff", f.adminRequests.last().requestUrl!!.queryParameter("auth"))
    }

    @Test fun returningBrowserRepairsBriefRelayLossWithoutInvite() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend(ChatProtocol.message("Before relay loss"))
        f.await { chat.state.value.messages.size == 1 }

        f.reopenBrowserAfterLoss = true
        f.dropRelays()
        f.await { f.adminRequests.size == 2 }
        f.await { f.browserConnections.get() == 2 }
        f.await { chat.state.value.phase == ChatPhase.Connected }
        assertEquals(1, f.invites.get())
        assertEquals(listOf("Before relay loss"), chat.state.value.messages.map { it.text })

        f.browserSend(ChatProtocol.message("After relay loss"))
        f.await { chat.state.value.messages.size == 2 }
        assertEquals(1, f.invites.get())
    }

    @Test fun closingBrowserRelayAlsoClosesAdminAndRecoversSilently() = fixture { f ->
        f.login()
        val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.closeBrowser()
        // The fixture closes the administrator relay as soon as its peer goes away; the controller
        // reopens it without sending another invitation.
        f.await(3_000) { f.adminRequests.size == 2 }
        assertEquals(1, f.invites.get())
        f.await { chat.state.value.phase == ChatPhase.Waiting }
    }

    @Test fun unseenChatIncrementsUnreadAndCallbackUntilVisible() = fixture { f ->
        f.login()
        val callbacks = AtomicInteger()
        val chat = f.chat { callbacks.incrementAndGet() }
        f.await { chat.state.value.phase == ChatPhase.Connected }

        f.browserSend(ChatProtocol.message("Hidden one"))
        f.await { chat.state.value.messages.size == 1 && callbacks.get() == 1 }
        assertEquals(1, chat.state.value.unreadCount)
        assertEquals(1, callbacks.get())

        // Foreground app but another tab: the chat screen is still invisible.
        f.onMain { f.client.onAppForeground() }
        f.browserSend(ChatProtocol.message("Hidden two"))
        f.await { chat.state.value.messages.size == 2 && callbacks.get() == 2 }
        assertEquals(2, chat.state.value.unreadCount)
        assertEquals(2, callbacks.get())

        f.onMain { chat.setVisible(true) }
        assertEquals(0, chat.state.value.unreadCount)
        f.browserSend(ChatProtocol.message("Visible"))
        f.await { chat.state.value.messages.size == 3 }
        assertEquals(0, chat.state.value.unreadCount)
        assertEquals(2, callbacks.get())

        f.onMain { chat.setVisible(false) }
        f.browserSend(ChatProtocol.message("Hidden again"))
        f.await { chat.state.value.messages.size == 4 && callbacks.get() == 3 }
        assertEquals(1, chat.state.value.unreadCount)
        assertEquals(3, callbacks.get())
    }

    @Test fun thirdSocketIsRejectedWithoutDisturbingEstablishedPair() = fixture { f ->
        f.login(); val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        val extra = f.duplicateAdmin()
        try {
            f.await { f.duplicates.get() == 1 }
            f.browserSend(ChatProtocol.message("Original pair survives"))
            f.await { chat.state.value.messages.size == 1 }
            assertEquals(ChatPhase.Connected, chat.state.value.phase)
            assertEquals(1, f.pairings.get())
        } finally { extra.cancel() }
    }
    @Test fun abruptConnectedLossRetainsPairUntilExpiryAndRecoversWithoutInvitation() = fixture { f ->
        f.login(); val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Connected }
        f.browserSend(ChatProtocol.message("History survives"))
        f.await { chat.state.value.messages.size == 1 }
        f.reopenBrowserAfterLoss = true
        f.retainAbruptAdminLoss()
        f.await { chat.state.value.phase == ChatPhase.ServerRetained && f.duplicates.get() >= 1 }
        assertFalse(chat.state.value.canSend)
        assertEquals(1, f.invites.get())
        // More than one attempt is required; duplicates must not masquerade as PeerLeft.
        f.await(12_000) { f.duplicates.get() >= 2 }
        assertEquals(ChatPhase.ServerRetained, chat.state.value.phase)
        f.expireRetainedPair()
        f.await(12_000) { chat.state.value.phase == ChatPhase.Connected }
        assertEquals(1, f.invites.get())
        f.browserSend(ChatProtocol.message("Recovered"))
        f.await { chat.state.value.messages.size == 2 }
        f.onMain { chat.setDraft("Return direction"); assertTrue(chat.send()) }
        f.await { f.browserFrames.any { JSONObject(it).optString("msg") == "Return direction" } }
    }
    @Test fun abruptWaitingLossCannotConfirmPairWithAbandonedAdminSocket() = fixture(autoOpen = false) { f ->
        f.login(); val chat = f.chat()
        f.await { chat.state.value.phase == ChatPhase.Waiting && f.invites.get() == 1 }
        f.retainAbruptAdminLoss()
        f.await { chat.state.value.phase == ChatPhase.Verifying }
        assertFalse(chat.state.value.canSend)
        f.onMain { chat.setDraft("Do not send to ghost"); assertFalse(chat.send()) }
        f.await { chat.state.value.phase == ChatPhase.ServerRetained }
        assertEquals(1, f.invites.get())
        f.expireRetainedPair()
        f.await(12_000) { chat.state.value.phase == ChatPhase.Waiting || chat.state.value.phase == ChatPhase.ServerRetained && f.adminRequests.size >= 3 }
        // Open the original invitation, without issuing another meshmessenger request.
        f.openExistingBrowser()
        f.await(12_000) { chat.state.value.phase == ChatPhase.Connected }
        assertEquals(1, f.invites.get())
    }
    private fun fixture(autoOpen: Boolean = true, block: (ChatRelayFixture) -> Unit) {
        ChatRelayFixture(autoOpen).use(block)
    }
}
