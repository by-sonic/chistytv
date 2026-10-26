package ru.bysonic.chistytv.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/** Что нашлось в сети: обычная отладка (5555), беспроводная отладка Android 11+ или окно «Подключить по коду» */
data class FoundTv(val host: String, val port: Int, val name: String, val kind: Kind) {
  enum class Kind { NETWORK_DEBUG, WIRELESS_DEBUG, PAIRING }

  val address get() = "$host:$port"
}

/**
 * Поиск телевизора — как Find-Tvs у скрипта: перебор адресов домашней сети на порт 5555,
 * плюс mDNS для беспроводной отладки Android 11+.
 */
class Discovery(private val context: Context) {
  private val cm = context.getSystemService(ConnectivityManager::class.java)

  private fun caps(n: Network) = cm.getNetworkCapabilities(n)

  /** Домашняя сеть: Wi-Fi или кабель, даже если поверх включён VPN */
  fun homeNetwork(): Network? = cm.allNetworks.firstOrNull { n ->
    caps(n)?.let { (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
  }

  fun vpnActive(): Boolean = cm.allNetworks.any { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }

  /** Свои IPv4-адреса в частных сетях: 192.168.x.x, 10.x.x.x, 172.16–31.x.x */
  fun localAddresses(): List<LinkAddress> {
    val net = homeNetwork() ?: return emptyList()
    return cm.getLinkProperties(net)?.linkAddresses.orEmpty().filter { la ->
      val a = la.address
      a is Inet4Address && (a.isSiteLocalAddress) && !a.isLinkLocalAddress
    }
  }

  /**
   * Перебирает /24 вокруг своего адреса и ищет открытый порт 5555. Сокеты привязываем к домашней сети:
   * VPN на телефоне не помешает найти телевизор.
   */
  suspend fun scanNetworkDebug(onFound: (FoundTv) -> Unit): List<String> = withContext(Dispatchers.IO) {
    val net = homeNetwork()
    val prefixes = localAddresses().map { it.address.hostAddress!!.substringBeforeLast('.') }.distinct()
    // Свои адреса на всех интерфейсах: телефон с включённой отладкой не должен найти сам себя
    val own = runCatching {
      java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()
    }.getOrDefault(emptySet())
    val limit = Semaphore(64)
    coroutineScope {
      prefixes.flatMap { prefix ->
        (1..254).map { "$prefix.$it" }.filter { it !in own }.map { ip ->
          async {
            limit.withPermit {
              if (probe(net, ip, ADB_PORT)) onFound(FoundTv(ip, ADB_PORT, hostName(ip), FoundTv.Kind.NETWORK_DEBUG))
            }
          }
        }
      }.awaitAll()
    }
    prefixes.map { "$it.0/24" }
  }

  private fun probe(net: Network?, ip: String, port: Int): Boolean = try {
    val socket = net?.socketFactory?.createSocket() ?: Socket()
    socket.use { it.connect(InetSocketAddress(ip, port), 1500) }
    true
  } catch (_: Exception) {
    false
  }

  /** Имя устройства в сети, если роутер его знает (часто это модель телевизора) */
  private suspend fun hostName(ip: String): String = withTimeoutOrNull(1500) {
    withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull() }
  }?.takeIf { it != ip && it.isNotBlank() }?.substringBefore('.') ?: ""

  /**
   * mDNS: телевизоры с беспроводной отладкой Android 11+ сами объявляют о себе.
   * Возвращает функцию, которая останавливает поиск.
   */
  fun discoverWireless(onFound: (FoundTv) -> Unit): () -> Unit {
    val nsd = context.getSystemService(NsdManager::class.java)
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    val lock = wifi?.createMulticastLock("chistytv-mdns")?.apply { setReferenceCounted(false); runCatching { acquire() } }
    val resolveQueue = ConcurrentLinkedQueue<Pair<NsdServiceInfo, FoundTv.Kind>>()
    var resolving = false
    val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun resolveNext() {
      synchronized(resolveQueue) {
        if (resolving) return
        val (info, kind) = resolveQueue.poll() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
          override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
            synchronized(resolveQueue) { resolving = false }
            resolveNext()
          }

          override fun onServiceResolved(si: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            val host = si.host
            if (host is Inet4Address) onFound(FoundTv(host.hostAddress!!, si.port, si.serviceName, kind))
            synchronized(resolveQueue) { resolving = false }
            resolveNext()
          }
        })
      }
    }

    for ((type, kind) in listOf(TLS_CONNECT to FoundTv.Kind.WIRELESS_DEBUG, TLS_PAIRING to FoundTv.Kind.PAIRING)) {
      val l = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(si: NsdServiceInfo) {
          resolveQueue.add(si to kind)
          resolveNext()
        }

        override fun onServiceLost(si: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
      }
      runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { listeners += l }
    }
    return {
      listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
      runCatching { lock?.release() }
    }
  }

  /**
   * На время подключения привязываем приложение к домашней сети: если VPN на телефоне не пускает
   * в локальную сеть, телевизор всё равно доступен. Уже открытое соединение остаётся в домашней сети,
   * а скачивание приложений после [unbind] снова идёт обычным путём.
   */
  fun bindToHome(): Boolean = homeNetwork()?.let { cm.bindProcessToNetwork(it) } ?: false

  fun unbind() {
    cm.bindProcessToNetwork(null)
  }

  companion object {
    const val ADB_PORT = 5555
    const val TLS_CONNECT = "_adb-tls-connect._tcp"
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"
  }
}
