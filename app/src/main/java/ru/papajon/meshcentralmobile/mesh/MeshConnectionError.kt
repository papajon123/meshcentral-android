package ru.papajon.meshcentralmobile.mesh

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** User-facing diagnostics must not expose exception text, URLs or authentication material. */
internal object MeshConnectionError {
    fun describe(error: Throwable, httpCode: Int? = null): String = when {
        httpCode == 401 || httpCode == 403 -> "Сервер отклонил вход. Проверьте учётные данные и права доступа."
        error is UnknownHostException -> "Не удалось найти сервер. Проверьте интернет, VPN и адрес сервера."
        error is SocketTimeoutException -> "Сервер не ответил вовремя. Проверьте соединение и повторите попытку."
        error is ConnectException -> "Сервер недоступен. Проверьте соединение и повторите попытку."
        error is SSLException -> "Не удалось проверить защищённое соединение с сервером. Проверьте дату на телефоне и сертификат сервера."
        else -> "Связь с сервером прервана. Повторите подключение."
    }
}
