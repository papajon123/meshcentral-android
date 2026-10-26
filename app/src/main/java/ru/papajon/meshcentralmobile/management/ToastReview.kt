package ru.papajon.meshcentralmobile.management

/** Review is a separate action; repeated taps during the double-tap interval never send. */
class ToastReview(val title: String, val message: String, private val reviewedAtMillis: Long) {
    private var sent = false
    fun ready(nowMillis: Long): Boolean = !sent && nowMillis - reviewedAtMillis >= ARM_DELAY_MILLIS
    fun send(nowMillis: Long, deliver: (String, String) -> Unit): Boolean {
        if (!ready(nowMillis)) return false
        sent = true
        deliver(title, message)
        return true
    }
    companion object { const val ARM_DELAY_MILLIS = 750L }
}
