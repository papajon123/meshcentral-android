package ru.papajon.meshcentralmobile.mesh.files

import java.security.MessageDigest
import org.json.JSONObject

/** MeshCentral 1.2.6 CreateSshFilesRelay, distinct from Agent protocol 5.
 * The server owns SSH host verification; this adapter neither implements direct SSH nor saves keys.
 * Never trust uploaddone alone: that server also sends it on sftp.write errors.
 */
internal object SftpFilesAdapter {
    fun password(username: String, password: String): JSONObject = JSONObject()
        .put("action", "sshauth").put("username", username).put("password", password).put("keep", false)

    fun privateKey(username: String, key: String, passphrase: String): JSONObject = JSONObject()
        .put("action", "sshauth").put("username", username).put("key", key).put("keypass", passphrase).put("keep", false)

    fun savedKey(passphrase: String): JSONObject = JSONObject()
        .put("action", "sshkeyauth").put("keypass", passphrase).put("keep", false)

    fun entries(message: JSONObject): List<RemoteFile> {
        val directory = requireNotNull(message.optJSONArray("dir"))
        require(directory.length() <= AgentFilesProtocol.MAX_LIST_ENTRIES)
        for (i in 0 until directory.length()) {
            val entry = directory.getJSONObject(i)
            if (entry.optInt("t") == 3) {
                // A missing or negative size must never turn into a successful zero-byte download.
                val size = entry.getLong("s")
                require(size in 0..9_007_199_254_740_991L && entry.get("s") is Number)
                require(entry.get("s").toString() == size.toString())
            }
        }
        return AgentFilesProtocol.entries(message)
    }

    /** Exact-size and empty reads lack EOF in this upstream. A fresh stat/listing supplies the bound.
     * Another fresh listing MUST confirm it after stop; early EOF and excess bytes are failures.
     */
    fun reachedExpectedSize(expected: Long, received: Long, final: Boolean): Boolean {
        require(expected >= 0 && received in 0..expected) { "SFTP size mismatch" }
        require(!final || received == expected) { "SFTP early EOF" }
        return received == expected
    }

    fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")
    fun matches(expected: ByteArray, actual: ByteArray): Boolean = MessageDigest.isEqual(expected, actual)
}
