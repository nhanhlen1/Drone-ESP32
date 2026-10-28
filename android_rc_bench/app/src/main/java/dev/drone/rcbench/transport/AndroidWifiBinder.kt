package dev.drone.rcbench.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.io.IOException
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicReference

/** Observe the existing Wi-Fi; never join an AP, reconnect, or request Internet. */
class AndroidWifiBinder(context:Context,private val onLost:()->Unit):ChannelBinder,Closeable {
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    private val bound=AtomicReference<Network?>()
    @Volatile private var closed=false
    private val callback=object:ConnectivityManager.NetworkCallback(){
        override fun onLost(network:Network){if(!closed&&bound.compareAndSet(network,null))onLost()}
        override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){
            if(!closed&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&bound.compareAndSet(network,null))onLost()
        }
    }
    init {
        val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request,callback)
    }
    @Suppress("DEPRECATION")
    override fun bind(channel:SocketChannel) {
        if(closed)throw IOException("Wi-Fi observer đã đóng")
        fun wifi(n:Network?)=n!=null&&cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        val active=cm.activeNetwork
        val network=if(wifi(active))active else cm.allNetworks.firstOrNull{wifi(it)}
        if(network==null)throw IOException("Chưa kết nối Wi-Fi. Hãy chọn ESP32_DRONE trong cài đặt điện thoại")
        bound.set(network)
        network.bindSocket(channel.socket()) // Use the AP even if mobile data has Internet.
    }
    override fun close(){if(closed)return;closed=true;bound.set(null);cm.unregisterNetworkCallback(callback)}
}
