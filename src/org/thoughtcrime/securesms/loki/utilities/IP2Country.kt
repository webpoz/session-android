package org.thoughtcrime.securesms.loki.utilities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log
import com.opencsv.CSVReader
import org.apache.commons.net.util.SubnetUtils
import org.whispersystems.signalservice.loki.api.onionrequests.OnionRequestAPI
import org.whispersystems.signalservice.loki.utilities.ThreadUtils
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader

class IP2Country private constructor(private val context: Context) {
    private val pathsBuiltEventReceiver: BroadcastReceiver
    val countryNamesCache = mutableMapOf<String, String>()

    private val ipv4ToCountry by lazy {
        val file = loadFile("geolite2_country_blocks_ipv4.csv")
        val csv = CSVReader(FileReader(file.absoluteFile)).apply {
            skip(1)
        }

        csv.readAll()
            .filter { cols -> !cols[1].isNullOrBlank() }
            .associate { cols ->
                SubnetUtils(cols[0]) to cols[1].toInt()
            }
    }

    private val countryToNames by lazy {
        val file = loadFile("geolite2_country_locations_english.csv")
        val csv = CSVReader(FileReader(file.absoluteFile)).apply {
            skip(1)
        }
        csv.readAll()
            .filter { cols -> !cols[0].isNullOrEmpty() && !cols[1].isNullOrEmpty() }
            .associate { cols ->
                cols[0].toInt() to cols[5]
            }
    }

    // region Initialization
    companion object {

        public lateinit var shared: IP2Country

        public val isInitialized: Boolean get() = ::shared.isInitialized

        public fun configureIfNeeded(context: Context) {
            if (isInitialized) { return; }
            shared = IP2Country(context)
        }
    }

    init {
        populateCacheIfNeeded()
        pathsBuiltEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                populateCacheIfNeeded()
            }
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(pathsBuiltEventReceiver, IntentFilter("pathsBuilt"))
    }

    // TODO: Deinit?
    // endregion

    // region Implementation
    private fun loadFile(fileName: String): File {
        val directory = File(context.applicationInfo.dataDir)
        val file = File(directory, fileName)
        if (directory.list().contains(fileName)) { return file }
        val inputStream = context.assets.open("csv/$fileName")
        val outputStream = FileOutputStream(file)
        val buffer = ByteArray(1024)
        while (true) {
            val count = inputStream.read(buffer)
            if (count < 0) { break }
            outputStream.write(buffer, 0, count)
        }
        inputStream.close()
        outputStream.close()
        return file
    }

    private fun cacheCountryForIP(ip: String): String? {

        // return early if cached
        countryNamesCache[ip]?.let { return it }

        val comps = ipv4ToCountry.asSequence()

        val bestMatchIp = comps.firstOrNull { (subnet, _) ->
            subnet.info.isInRange(ip)
        }?.let { (_, code) ->
            countryToNames[code]
        }

        if (bestMatchIp != null) {
            countryNamesCache[ip] = bestMatchIp
            return bestMatchIp
        } else {
            Log.d("Loki","Country name for $ip couldn't be found")
        }
        return null
    }

    private fun populateCacheIfNeeded() {
        ThreadUtils.queue {
            OnionRequestAPI.paths.forEach { path ->
                path.forEach { snode ->
                    cacheCountryForIP(snode.ip) // Preload if needed
                }
            }
            Broadcaster(context).broadcast("onionRequestPathCountriesLoaded")
            Log.d("Loki", "Finished preloading onion request path countries.")
        }
    }
    // endregion
}