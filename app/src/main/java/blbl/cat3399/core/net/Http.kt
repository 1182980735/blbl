package blbl.cat3399.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 将OkHttp的Call转换为挂起函数
 *
 * 使用协程包装异步回调，使HTTP请求可以在协程中以同步方式编写
 *
 * @return HTTP响应
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/**
 * 创建仅支持IPv4的DNS解析器
 *
 * 当启用IPv4-only模式时，会过滤掉IPv6地址，只返回IPv4地址
 * 这在某些网络环境下可以避免IPv6连接问题
 *
 * @param ipv4OnlyEnabled 一个返回布尔值的函数，用于判断是否启用IPv4-only模式
 * @return 自定义的DNS解析器
 */
fun ipv4OnlyDns(ipv4OnlyEnabled: () -> Boolean): Dns =
    object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = hostname.trim()
            if (host.isBlank()) throw UnknownHostException("hostname is blank")

            val addresses = Dns.SYSTEM.lookup(host)
            if (!ipv4OnlyEnabled()) return addresses

            val ipv4 = ArrayList<InetAddress>(addresses.size)
            for (address in addresses) {
                if (address is Inet4Address) ipv4.add(address)
            }
            if (ipv4.isNotEmpty()) return ipv4
            throw UnknownHostException("No IPv4 address for $host")
        }
    }
