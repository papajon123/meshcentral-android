package ru.papajon.meshcentralmobile.mesh.chat

import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

/** MeshCentral 1.3.1 messenger.handlebars. Text is raw Unicode, never HTML. */
object ChatProtocol {
    const val MAX_TEXT = 4096
    const val MAX_HISTORY = 200

    private const val MAX_FIELDS = 256
    private const val MAX_KEY = 128
    private const val MAX_ACTION = 32
    private const val MAX_META = 64
    private const val MAX_NUMBER = 64
    private const val MAX_DEPTH = 64
    private const val CTRL_CHANNEL = "102938"

    /** Chat rights alone are not enough: the account must also be allowed to see the remote screen. */
    fun authorized(device: MeshDevice): Boolean = device.isMeshAgent && !device.isAndroidAgent &&
        device.rights?.let {
            it == MeshRights.FULL || it >= 0 && it and MeshRights.CHAT_NOTIFY != 0 &&
                it and (MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW) != 0
        } == true

    /** Invitations additionally require a known-online target; [authorized] alone gates live chat. */
    fun allowed(device: MeshDevice, current: Boolean): Boolean = current && authorized(device) &&
        device.connectivityKnown && device.conn and 1 != 0

    fun message(text: String): String {
        require(text.isNotBlank() && text.length <= MAX_TEXT)
        return JSONObject().put("action", "chat").put("msg", text).toString()
    }

    fun invite(nodeId: String): JSONObject = JSONObject().put("action", "meshmessenger").put("nodeid", nodeId)

    fun cancelFile(id: Double): String {
        require(id.isFinite() && id >= 0.0 && id < 1.0)
        return JSONObject().put("action", "fileUploadCancel").put("id", id).toString()
    }

    fun request(base: HttpUrl, nodeId: String, userId: String, auth: String): Request {
        require(nodeId.startsWith("node/") && nodeId.split('/').size == 3)
        require(userId.startsWith("user/") && userId.split('/').size == 3)
        require(auth.isNotBlank())
        // HttpUrl retains the domain/reverse-proxy path and encodes each query parameter once.
        val url = base.newBuilder().query(null).fragment(null)
            .encodedPath(base.encodedPath.trimEnd('/') + "/meshrelay.ashx")
            .addQueryParameter("id", "meshmessenger/$nodeId/$userId")
            .addQueryParameter("auth", auth).build()
        return Request.Builder().url(url).build()
    }

    /**
     * OkHttp has already buffered the whole relay message as [text]; this decoder bounds only the
     * extra copies it creates. It never rejects a frame for size alone and never builds a
     * [JSONObject], so a huge foreign field cannot become an unbounded decoded copy: only
     * `action`, `msg`, `id`, `random`, `ctrlChannel` and `type` are decoded, while every other value is
     * validated and skipped with a nesting limit. A chat message is cut to [MAX_TEXT] UTF-16
     * units without splitting a surrogate pair; [ChatFrame.Text.truncated] records that cut.
     */
    fun parse(text: String): ChatFrame {
        if (text == "c" || text == "cr") return ChatFrame.Ready(text == "cr")
        if (!text.startsWith('{')) return ChatFrame.Ignore
        return runCatching { Scanner(text).read() }.getOrNull() ?: ChatFrame.Ignore
    }

    fun pong(): String = JSONObject().put("ctrlChannel", CTRL_CHANNEL).put("type", "pong").toString()

    private class Scanner(private val text: String) {
        private var index = 0
        private var action: String? = null
        private var message: String? = null
        private var messageTruncated = false
        private var id: Double? = null
        private var random: Double? = null
        private var ctrlChannel: String? = null
        private var type: String? = null

        fun read(): ChatFrame {
            whitespace()
            require(take() == '{')
            whitespace()
            if (peek() == '}') {
                index++
                finish()
                return ChatFrame.Ignore
            }
            var fields = 0
            while (true) {
                require(++fields <= MAX_FIELDS)
                val key = string(MAX_KEY)
                whitespace()
                require(take() == ':')
                whitespace()
                when (key) {
                    "action" -> if (peek() == '"') action = string(MAX_ACTION) else skipValue()
                    "msg" -> if (peek() == '"') readMessage() else skipValue()
                    "ctrlChannel" -> if (peek() == '"') ctrlChannel = string(MAX_META) else skipValue()
                    "type" -> if (peek() == '"') type = string(MAX_META) else skipValue()
                    "id" -> readId()
                    "random" -> {
                        random = null
                        if (peek() == '-' || peek() in '0'..'9') random = number() else skipValue()
                    }
                    else -> skipValue()
                }
                whitespace()
                when (take()) {
                    '}' -> break
                    ',' -> whitespace()
                    else -> error("Invalid object")
                }
            }
            finish()
            return frame()
        }

        private fun finish() {
            whitespace()
            require(index == text.length)
        }

        private fun frame(): ChatFrame {
            if (ctrlChannel == CTRL_CHANNEL) return when (type) {
                "ping" -> ChatFrame.Ping
                // meshrelay can emit unsolicited pongs (agentpong); they do not prove a browser.
                else -> ChatFrame.Ignore
            }
            return when (action) {
                "random" -> if (random?.let { it >= 0.0 && it < 1.0 } == true) ChatFrame.PeerProof else ChatFrame.Ignore
                "chat" -> message?.takeIf { it.isNotEmpty() }?.let { ChatFrame.Text(it, messageTruncated) }
                    ?: ChatFrame.Ignore
                "close" -> ChatFrame.Refused
                "file", "fileUploadStart" -> id?.takeIf { it >= 0.0 && it < 1.0 }
                    ?.let(ChatFrame::FileOffer) ?: ChatFrame.Ignore
                else -> ChatFrame.Ignore
            }
        }

        private fun readMessage() {
            require(take() == '"')
            val out = StringBuilder(256)
            var truncated = false
            while (true) {
                var ch = take()
                if (ch == '"') break
                require(ch >= ' ')
                if (ch == '\\') ch = escape()
                if (truncated) continue
                if (out.length < MAX_TEXT) {
                    out.append(ch)
                } else {
                    // The cut falls inside a surrogate pair: drop its high half and stop, so a later
                    // unit after the pair cannot shift the prefix.
                    if (ch.isLowSurrogate() && out[MAX_TEXT - 1].isHighSurrogate()) {
                        out.setLength(MAX_TEXT - 1)
                    }
                    truncated = true
                }
            }
            message = out.toString()
            messageTruncated = truncated
        }

        private fun readId() {
            id = null
            val first = peek()
            if (first == '-' || first in '0'..'9') id = number() else skipValue()
        }

        /** Strict JSON number, or null when it is too long or not finite. */
        private fun number(): Double? {
            val start = index
            if (peek() == '-') index++
            if (peek() == '0') {
                index++
            } else {
                require(peek() in '1'..'9')
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (peek() == '.') {
                index++
                require(index < text.length && text[index] in '0'..'9')
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                require(index < text.length && text[index] in '0'..'9')
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index - start > MAX_NUMBER) return null
            return text.substring(start, index).toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        private fun skipValue(depth: Int = 0) {
            require(depth <= MAX_DEPTH)
            val ch = peek()
            when {
                ch == '"' -> string(0)
                ch == '{' -> skipObject(depth + 1)
                ch == '[' -> skipArray(depth + 1)
                ch == 't' -> literal("true")
                ch == 'f' -> literal("false")
                ch == 'n' -> literal("null")
                ch == '-' || ch in '0'..'9' -> number()
                else -> error("Invalid value")
            }
        }

        private fun skipObject(depth: Int) {
            require(depth <= MAX_DEPTH)
            require(take() == '{')
            whitespace()
            if (peek() == '}') {
                index++
                return
            }
            while (true) {
                whitespace()
                string(0)
                whitespace()
                require(take() == ':')
                whitespace()
                skipValue(depth)
                whitespace()
                when (take()) {
                    ',' -> {}
                    '}' -> return
                    else -> error("Invalid object")
                }
            }
        }

        private fun skipArray(depth: Int) {
            require(depth <= MAX_DEPTH)
            require(take() == '[')
            whitespace()
            if (peek() == ']') {
                index++
                return
            }
            while (true) {
                whitespace()
                skipValue(depth)
                whitespace()
                when (take()) {
                    ',' -> {}
                    ']' -> return
                    else -> error("Invalid array")
                }
            }
        }

        private fun string(limit: Int): String {
            require(take() == '"')
            if (limit <= 0) {
                while (true) {
                    var ch = take()
                    if (ch == '"') return ""
                    require(ch >= ' ')
                    if (ch == '\\') escape()
                }
            }
            val out = StringBuilder(minOf(limit, 64))
            while (true) {
                var ch = take()
                if (ch == '"') return out.toString()
                require(ch >= ' ')
                if (ch == '\\') ch = escape()
                if (out.length < limit) out.append(ch)
            }
        }

        private fun escape(): Char = when (val escaped = take()) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                var code = 0
                repeat(4) {
                    val digit = take()
                    val value = when (digit) {
                        in '0'..'9' -> digit - '0'
                        in 'a'..'f' -> digit - 'a' + 10
                        in 'A'..'F' -> digit - 'A' + 10
                        else -> error("Invalid escape")
                    }
                    code = code * 16 + value
                }
                code.toChar()
            }
            else -> error("Invalid escape")
        }

        private fun literal(value: String) {
            require(text.startsWith(value, index))
            index += value.length
        }

        private fun whitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char = text.getOrElse(index) { error("Incomplete frame") }

        private fun take(): Char = peek().also { index++ }
    }
}

sealed interface ChatFrame {
    data class Ready(val recorded: Boolean) : ChatFrame
    data class Text(val text: String, val truncated: Boolean = false) : ChatFrame
    data class FileOffer(val id: Double) : ChatFrame
    data object Ping : ChatFrame
    data object PeerProof : ChatFrame
    data object Refused : ChatFrame
    data object Ignore : ChatFrame
}

enum class ChatPhase { Idle, Connecting, Reconnecting, Waiting, Verifying, ServerRetained, Connected, NotOpened, PeerLeft, Failed, Closed }
data class ChatMessage(val id: Long, val text: String, val outgoing: Boolean, val timeMillis: Long, val truncated: Boolean = false)
data class ChatState(
    val phase: ChatPhase = ChatPhase.Idle,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val recorded: Boolean = false,
    val sendFailed: Boolean = false,
    val canRequest: Boolean = true,
    val unreadCount: Int = 0,
    val invitationUnavailable: Boolean = false,
) {
    val active: Boolean get() = phase in setOf(ChatPhase.Idle, ChatPhase.Connecting, ChatPhase.Reconnecting, ChatPhase.Waiting, ChatPhase.Verifying, ChatPhase.ServerRetained, ChatPhase.Connected)
    val canSend: Boolean get() = phase == ChatPhase.Connected && draft.isNotBlank() && draft.length <= ChatProtocol.MAX_TEXT
}
