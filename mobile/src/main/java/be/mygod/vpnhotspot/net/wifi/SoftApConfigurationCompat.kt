package be.mygod.vpnhotspot.net.wifi

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.net.MacAddress
import android.net.wifi.ScanResult
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.Parcelable
import android.util.SparseIntArray
import androidx.annotation.RequiresApi
import be.mygod.vpnhotspot.net.monitor.TetherTimeoutMonitor
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat.Companion.requireSingleBand
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat.Companion.setChannel
import be.mygod.vpnhotspot.net.wifi.WifiSsidCompat.Companion.toCompat
import be.mygod.vpnhotspot.util.ConstantLookup
import be.mygod.vpnhotspot.util.UnblockCentral
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.lang.reflect.InvocationTargetException

@Parcelize
data class SoftApConfigurationCompat(
    var ssid: WifiSsidCompat? = null,
    var bssid: MacAddress? = null,
    var passphrase: String? = null,
    var isHiddenSsid: Boolean = false,
    /**
     * You should probably set or modify this field directly only when you want to use bridged AP,
     * see also [android.net.wifi.WifiManager.isBridgedApConcurrencySupported].
     * Otherwise, use [requireSingleBand] and [setChannel].
     */
    var channels: SparseIntArray = SparseIntArray(1).apply { append(SoftApConfiguration.BAND_2GHZ, 0) },
    var securityType: Int = SoftApConfiguration.SECURITY_TYPE_OPEN,
    @TargetApi(30)
    var maxNumberOfClients: Int = 0,
    var isAutoShutdownEnabled: Boolean = true,
    var shutdownTimeoutMillis: Long = 0,
    @TargetApi(30)
    var isClientControlByUserEnabled: Boolean = false,
    @RequiresApi(30)
    var blockedClientList: List<MacAddress> = emptyList(),
    @RequiresApi(30)
    var allowedClientList: List<MacAddress> = emptyList(),
    @TargetApi(31)
    var macRandomizationSetting: Int = if (Build.VERSION.SDK_INT >= 33) {
        RANDOMIZATION_NON_PERSISTENT
    } else RANDOMIZATION_PERSISTENT,
    @TargetApi(31)
    var isBridgedModeOpportunisticShutdownEnabled: Boolean = true,
    @TargetApi(31)
    var isIeee80211axEnabled: Boolean = true,
    @TargetApi(33)
    var isIeee80211beEnabled: Boolean = true,
    @TargetApi(31)
    var isUserConfiguration: Boolean = true,
    @TargetApi(33)
    var bridgedModeOpportunisticShutdownTimeoutMillis: Long = -1L,
    @TargetApi(33)
    var vendorElements: List<ScanResult.InformationElement> = emptyList(),
    @TargetApi(33)
    var persistentRandomizedMacAddress: MacAddress? = null,
    @TargetApi(33)
    var allowedAcsChannels: Map<Int, Set<Int>> = emptyMap(),
    @TargetApi(33)
    var maxChannelBandwidth: Int = CHANNEL_WIDTH_AUTO,
    @RequiresApi(36)
    var isClientIsolationEnabled: Boolean = false,
    var underlying: Parcelable? = null,
) : Parcelable {
    companion object {
        const val BAND_LEGACY = SoftApConfiguration.BAND_2GHZ or SoftApConfiguration.BAND_5GHZ
        @TargetApi(30)
        const val BAND_ANY_30 = BAND_LEGACY or SoftApConfiguration.BAND_6GHZ
        @TargetApi(31)
        const val BAND_ANY_31 = BAND_ANY_30 or SoftApConfiguration.BAND_60GHZ
        val BAND_TYPES by lazy {
            if (Build.VERSION.SDK_INT >= 31) try {
                return@lazy UnblockCentral.SoftApConfiguration_BAND_TYPES
            } catch (e: ReflectiveOperationException) {
                Timber.w(e)
            }
            intArrayOf(
                SoftApConfiguration.BAND_2GHZ,
                SoftApConfiguration.BAND_5GHZ,
                SoftApConfiguration.BAND_6GHZ,
                SoftApConfiguration.BAND_60GHZ,
            )
        }
        @RequiresApi(31)
        val bandLookup = ConstantLookup<SoftApConfiguration>("BAND_")

        @TargetApi(31)
        const val RANDOMIZATION_NONE = 0
        @TargetApi(31)
        const val RANDOMIZATION_PERSISTENT = 1
        @TargetApi(33)
        const val RANDOMIZATION_NON_PERSISTENT = 2

        @TargetApi(33)
        const val CHANNEL_WIDTH_AUTO = -1
        @TargetApi(30)
        const val CHANNEL_WIDTH_INVALID = 0

        fun isLegacyEitherBand(band: Int) = band and BAND_LEGACY == BAND_LEGACY

        /**
         * [android.net.wifi.WifiConfiguration.KeyMgmt.WPA2_PSK]
         */
        private const val LEGACY_WPA2_PSK = 4

        val securityTypes = arrayOf(
            "OPEN",
            "WPA2-PSK",
            "WPA3-SAE Transition mode",
            "WPA3-SAE",
            "WPA3-OWE Transition",
            "WPA3-OWE",
        )

        /**
         * Based on:
         * https://elixir.bootlin.com/linux/v5.12.8/source/net/wireless/util.c#L75
         * https://cs.android.com/android/platform/superproject/+/master:packages/modules/Wifi/framework/java/android/net/wifi/ScanResult.java;l=789;drc=71d758698c45984d3f8de981bf98e56902480f16
         */
        fun channelToFrequency(band: Int, chan: Int) = when (band) {
            SoftApConfiguration.BAND_2GHZ -> when (chan) {
                14 -> 2484
                in 1 until 14 -> 2407 + chan * 5
                else -> throw IllegalArgumentException("Invalid 2GHz channel $chan")
            }
            SoftApConfiguration.BAND_5GHZ -> when (chan) {
                in 182..196 -> 4000 + chan * 5
                in 1..Int.MAX_VALUE -> 5000 + chan * 5
                else -> throw IllegalArgumentException("Invalid 5GHz channel $chan")
            }
            SoftApConfiguration.BAND_6GHZ -> when (chan) {
                2 -> 5935
                in 1..253 -> 5950 + chan * 5
                else -> throw IllegalArgumentException("Invalid 6GHz channel $chan")
            }
            SoftApConfiguration.BAND_60GHZ -> {
                require(chan in 1 until 7) { "Invalid 60GHz channel $chan" }
                56160 + chan * 2160
            }
            else -> throw IllegalArgumentException("Invalid band $band")
        }
        fun frequencyToChannel(freq: Int) = when (freq) {
            2484 -> 14
            in Int.MIN_VALUE until 2484 -> (freq - 2407) / 5
            in 4910..4980 -> (freq - 4000) / 5
            in Int.MIN_VALUE until 5925 -> (freq - 5000) / 5
            5935 -> 2
            in Int.MIN_VALUE..45000 -> (freq - 5950) / 5
            in 58320..70200 -> (freq - 56160) / 2160
            else -> throw IllegalArgumentException("Invalid frequency $freq")
        }

        /**
         * apBand and apChannel is available since API 23.
         *
         * https://android.googlesource.com/platform/frameworks/base/+/android-6.0.0_r1/wifi/java/android/net/wifi/WifiConfiguration.java#242
         */
        @Suppress("DEPRECATION")
        /**
         * The band which AP resides on
         * -1:Any 0:2G 1:5G
         * By default, 2G is chosen
         */
        private val apBand by lazy { android.net.wifi.WifiConfiguration::class.java.getDeclaredField("apBand") }
        @Suppress("DEPRECATION")
        /**
         * The channel which AP resides on
         * 2G  1-11
         * 5G  36,40,44,48,149,153,157,161,165
         * 0 - find a random available channel according to the apBand
         */
        private val apChannel by lazy {
            android.net.wifi.WifiConfiguration::class.java.getDeclaredField("apChannel")
        }

        @get:RequiresApi(33)
        private val getAllowedAcsChannels by lazy @TargetApi(33) {
            SoftApConfiguration::class.java.getDeclaredMethod("getAllowedAcsChannels", Int::class.java)
        }
        @get:RequiresApi(30)
        private val getAllowedClientList by lazy @TargetApi(30) {
            SoftApConfiguration::class.java.getDeclaredMethod("getAllowedClientList")
        }
        @get:RequiresApi(30)
        private val getBand by lazy @TargetApi(30) { SoftApConfiguration::class.java.getDeclaredMethod("getBand") }
        @get:RequiresApi(30)
        private val getBlockedClientList by lazy @TargetApi(30) {
            SoftApConfiguration::class.java.getDeclaredMethod("getBlockedClientList")
        }
        @get:RequiresApi(33)
        private val getBridgedModeOpportunisticShutdownTimeoutMillis by lazy @TargetApi(33) {
            SoftApConfiguration::class.java.getDeclaredMethod("getBridgedModeOpportunisticShutdownTimeoutMillis")
        }
        @get:RequiresApi(30)
        private val getChannel by lazy @TargetApi(30) {
            SoftApConfiguration::class.java.getDeclaredMethod("getChannel")
        }
        @get:RequiresApi(31)
        private val getMacRandomizationSetting by lazy @TargetApi(31) {
            SoftApConfiguration::class.java.getDeclaredMethod("getMacRandomizationSetting")
        }
        @get:RequiresApi(33)
        private val getMaxChannelBandwidth by lazy @TargetApi(33) {
            SoftApConfiguration::class.java.getDeclaredMethod("getMaxChannelBandwidth")
        }
        @get:RequiresApi(30)
        private val getMaxNumberOfClients by lazy @TargetApi(30) {
            SoftApConfiguration::class.java.getDeclaredMethod("getMaxNumberOfClients")
        }
        @get:RequiresApi(33)
        private val getPersistentRandomizedMacAddress by lazy @TargetApi(33) {
            SoftApConfiguration::class.java.getDeclaredMethod("getPersistentRandomizedMacAddress")
        }
        @get:RequiresApi(30)
        private val getShutdownTimeoutMillis by lazy @TargetApi(30) {
            SoftApConfiguration::class.java.getDeclaredMethod("getShutdownTimeoutMillis")
        }
        @get:RequiresApi(33)
        private val getVendorElements by lazy @TargetApi(33) {
            SoftApConfiguration::class.java.getDeclaredMethod("getVendorElements")
        }
        @get:RequiresApi(30)
        private val isAutoShutdownEnabled by lazy @TargetApi(30) {
            SoftApConfiguration::class.java.getDeclaredMethod("isAutoShutdownEnabled")
        }
        @get:RequiresApi(31)
        private val isBridgedModeOpportunisticShutdownEnabled by lazy @TargetApi(31) {
            SoftApConfiguration::class.java.getDeclaredMethod("isBridgedModeOpportunisticShutdownEnabled")
        }
        @get:RequiresApi(30)
        private val isClientControlByUserEnabled by lazy @TargetApi(30) {
            SoftApConfiguration::class.java.getDeclaredMethod("isClientControlByUserEnabled")
        }
        @get:RequiresApi(36)
        private val isClientIsolationEnabled by lazy @TargetApi(36) {
            SoftApConfiguration::class.java.getDeclaredMethod("isClientIsolationEnabled")
        }
        @get:RequiresApi(31)
        private val isIeee80211axEnabled by lazy @TargetApi(31) {
            SoftApConfiguration::class.java.getDeclaredMethod("isIeee80211axEnabled")
        }
        @get:RequiresApi(33)
        private val isIeee80211beEnabled by lazy @TargetApi(33) {
            SoftApConfiguration::class.java.getDeclaredMethod("isIeee80211beEnabled")
        }
        @get:RequiresApi(31)
        private val isUserConfiguration by lazy @TargetApi(31) {
            SoftApConfiguration::class.java.getDeclaredMethod("isUserConfiguration")
        }

        @get:RequiresApi(30)
        private val newBuilder by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getConstructor(SoftApConfiguration::class.java)
        }
        @get:RequiresApi(33)
        private val setAllowedAcsChannels by lazy @TargetApi(33) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setAllowedAcsChannels", Int::class.java,
                IntArray::class.java)
        }
        @get:RequiresApi(30)
        private val setAllowedClientList by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setAllowedClientList", List::class.java)
        }
        @get:RequiresApi(30)
        private val setAutoShutdownEnabled by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setAutoShutdownEnabled", Boolean::class.java)
        }
        @get:RequiresApi(30)
        private val setBand by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setBand", Int::class.java)
        }
        @get:RequiresApi(30)
        private val setBlockedClientList by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setBlockedClientList", List::class.java)
        }
        @get:RequiresApi(31)
        private val setBridgedModeOpportunisticShutdownEnabled by lazy @TargetApi(31) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setBridgedModeOpportunisticShutdownEnabled",
                Boolean::class.java)
        }
        @get:RequiresApi(33)
        private val setBridgedModeOpportunisticShutdownTimeoutMillis by lazy @TargetApi(33) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod(
                "setBridgedModeOpportunisticShutdownTimeoutMillis", Long::class.java)
        }
        @get:RequiresApi(30)
        private val setBssid by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setBssid", MacAddress::class.java)
        }
        @get:RequiresApi(30)
        private val setChannel by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setChannel", Int::class.java, Int::class.java)
        }
        @get:RequiresApi(30)
        private val setClientControlByUserEnabled by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setClientControlByUserEnabled",
                Boolean::class.java)
        }
        @get:RequiresApi(36)
        private val setClientIsolationEnabled by lazy @TargetApi(36) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setClientIsolationEnabled", Boolean::class.java)
        }
        @get:RequiresApi(30)
        private val setHiddenSsid by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setHiddenSsid", Boolean::class.java)
        }
        @get:RequiresApi(31)
        private val setIeee80211axEnabled by lazy @TargetApi(31) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setIeee80211axEnabled", Boolean::class.java)
        }
        @get:RequiresApi(33)
        private val setIeee80211beEnabled by lazy @TargetApi(33) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setIeee80211beEnabled", Boolean::class.java)
        }
        @get:RequiresApi(31)
        private val setMacRandomizationSetting by lazy @TargetApi(31) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setMacRandomizationSetting", Int::class.java)
        }
        @get:RequiresApi(33)
        private val setMaxChannelBandwidth by lazy @TargetApi(33) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setMaxChannelBandwidth", Int::class.java)
        }
        @get:RequiresApi(30)
        private val setMaxNumberOfClients by lazy @TargetApi(31) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setMaxNumberOfClients", Int::class.java)
        }
        @get:RequiresApi(30)
        private val setPassphrase by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setPassphrase", String::class.java,
                Int::class.java)
        }
        @get:RequiresApi(33)
        private val setRandomizedMacAddress by lazy @TargetApi(33) {
            UnblockCentral.setRandomizedMacAddress(SoftApConfiguration.Builder::class.java)
        }
        @get:RequiresApi(30)
        private val setShutdownTimeoutMillis by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setShutdownTimeoutMillis", Long::class.java)
        }
        @get:RequiresApi(30)
        private val setSsid by lazy @TargetApi(30) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setSsid", String::class.java)
        }
        @get:RequiresApi(33)
        private val setVendorElements by lazy @TargetApi(33) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setVendorElements", List::class.java)
        }
        @get:RequiresApi(33)
        private val setWifiSsid by lazy @TargetApi(33) {
            SoftApConfiguration.Builder::class.java.getDeclaredMethod("setWifiSsid", WifiSsid::class.java)
        }

        @Deprecated("Class deprecated in framework")
        @Suppress("DEPRECATION")
        fun android.net.wifi.WifiConfiguration.toCompat() = SoftApConfigurationCompat(
                WifiSsidCompat.fromUtf8Text(SSID, true),
                BSSID?.let { MacAddress.fromString(it) },
                preSharedKey,
                hiddenSSID,
                // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/wifi/java/android/net/wifi/SoftApConfToXmlMigrationUtil.java;l=87;drc=aa6527cf41671d1ed417b8ebdb6b3aa614f62344
                SparseIntArray(1).also {
                    it.append(when (val band = apBand.getInt(this)) {
                        0 -> SoftApConfiguration.BAND_2GHZ
                        1 -> SoftApConfiguration.BAND_5GHZ
                        -1 -> BAND_LEGACY
                        else -> throw IllegalArgumentException("Unexpected band $band")
                    }, apChannel.getInt(this))
                },
                allowedKeyManagement.nextSetBit(0).let { selected ->
                    require(allowedKeyManagement.nextSetBit(selected + 1) < 0) {
                        "More than 1 key managements supplied: $allowedKeyManagement"
                    }
                    when (if (selected < 0) -1 else selected) {
                        -1,     // getAuthType returns NONE if nothing is selected
                        android.net.wifi.WifiConfiguration.KeyMgmt.NONE -> SoftApConfiguration.SECURITY_TYPE_OPEN
                        android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK,
                        LEGACY_WPA2_PSK,
                        6,      // FT_PSK
                        11 -> { // WPA_PSK_SHA256
                            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
                        }
                        android.net.wifi.WifiConfiguration.KeyMgmt.SAE -> SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                        android.net.wifi.WifiConfiguration.KeyMgmt.OWE -> SoftApConfiguration.SECURITY_TYPE_WPA3_OWE
                        else -> android.net.wifi.WifiConfiguration.KeyMgmt.strings
                                .getOrElse<String>(selected) { "?" }.let {
                            throw IllegalArgumentException("Unrecognized key management $it ($selected)")
                        }
                    }
                },
                isAutoShutdownEnabled = TetherTimeoutMonitor.enabled,
                underlying = this)

        @RequiresApi(30)
        @Suppress("UNCHECKED_CAST")
        fun SoftApConfiguration.toCompat() = SoftApConfigurationCompat(
            if (Build.VERSION.SDK_INT >= 33) wifiSsid?.toCompat() else @Suppress("DEPRECATION") {
                WifiSsidCompat.fromUtf8Text(ssid)
            },
            bssid,
            passphrase,
            isHiddenSsid,
            if (Build.VERSION.SDK_INT >= 31) channels else SparseIntArray(1).also {
                it.append(getBand(this) as Int, getChannel(this) as Int)
            },
            securityType,
            getMaxNumberOfClients(this) as Int,
            isAutoShutdownEnabled(this) as Boolean,
            getShutdownTimeoutMillis(this) as Long,
            isClientControlByUserEnabled(this) as Boolean,
            getBlockedClientList(this) as List<MacAddress>,
            getAllowedClientList(this) as List<MacAddress>,
            underlying = this,
        ).also {
            if (Build.VERSION.SDK_INT < 31) return@also
            it.macRandomizationSetting = getMacRandomizationSetting(this) as Int
            it.isBridgedModeOpportunisticShutdownEnabled = isBridgedModeOpportunisticShutdownEnabled(this) as Boolean
            it.isIeee80211axEnabled = isIeee80211axEnabled(this) as Boolean
            it.isUserConfiguration = isUserConfiguration(this) as Boolean
            if (Build.VERSION.SDK_INT < 33) return@also
            it.isIeee80211beEnabled = isIeee80211beEnabled(this) as Boolean
            it.bridgedModeOpportunisticShutdownTimeoutMillis =
                getBridgedModeOpportunisticShutdownTimeoutMillis(this) as Long
            it.vendorElements = getVendorElements(this) as List<ScanResult.InformationElement>
            it.persistentRandomizedMacAddress = getPersistentRandomizedMacAddress(this) as MacAddress?
            it.allowedAcsChannels = BAND_TYPES.map { bandType ->
                try {
                    bandType to (getAllowedAcsChannels(this, bandType) as IntArray).toSet()
                } catch (e: InvocationTargetException) {
                    if (e.targetException !is IllegalArgumentException) throw e
                    null
                }
            }.filterNotNull().toMap()
            it.maxChannelBandwidth = getMaxChannelBandwidth(this) as Int
            if (Build.VERSION.SDK_INT >= 36) it.isClientIsolationEnabled = isClientIsolationEnabled(this) as Boolean
        }

        /**
         * Only single band/channel can be supplied on API 23-30
         */
        fun requireSingleBand(channels: SparseIntArray): Pair<Int, Int> {
            require(channels.size() == 1) { "Unsupported number of bands configured" }
            return channels.keyAt(0) to channels.valueAt(0)
        }

        @RequiresApi(30)
        private fun SoftApConfiguration.Builder.setChannelsCompat(
            channels: SparseIntArray,
        ) = if (Build.VERSION.SDK_INT < 31) {
            val (band, channel) = requireSingleBand(channels)
            if (channel == 0) setBand(this, band) else setChannel(this, channel, band)
            this
        } else setChannels(channels)
        @get:RequiresApi(30)
        private val staticBuilder by lazy @TargetApi(30) { SoftApConfiguration.Builder() }
        @RequiresApi(30)
        fun testPlatformValidity(channels: SparseIntArray) = staticBuilder.setChannelsCompat(channels)
        @RequiresApi(30)
        fun testPlatformValidity(bssid: MacAddress) = setBssid(staticBuilder, bssid)
        @RequiresApi(33)
        fun testPlatformValidity(vendorElements: List<ScanResult.InformationElement>) =
            setVendorElements(staticBuilder, vendorElements)
        @RequiresApi(33)
        fun testPlatformValidity(band: Int, channels: IntArray) = setAllowedAcsChannels(staticBuilder, band, channels)
        @RequiresApi(33)
        fun testPlatformValidity(bandwidth: Int) = setMaxChannelBandwidth(staticBuilder, bandwidth)
        @RequiresApi(30)
        fun testPlatformTimeoutValidity(timeout: Long) = setShutdownTimeoutMillis(staticBuilder, timeout)
        @RequiresApi(33)
        fun testPlatformBridgedTimeoutValidity(timeout: Long) =
            setBridgedModeOpportunisticShutdownTimeoutMillis(staticBuilder, timeout)
    }

    fun setChannel(channel: Int, band: Int = BAND_LEGACY) {
        channels = SparseIntArray(1).apply {
            append(when {
                channel <= 0 || band != BAND_LEGACY -> band
                channel > 14 -> SoftApConfiguration.BAND_5GHZ
                else -> SoftApConfiguration.BAND_2GHZ
            }, channel)
        }
    }

    /**
     * Based on:
     * https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.0.0_r1/src/com/android/settings/wifi/WifiApDialog.java#88
     * https://android.googlesource.com/platform/packages/apps/Settings/+/b1af85d/src/com/android/settings/wifi/tether/WifiTetherSettings.java#162
     * https://android.googlesource.com/platform/frameworks/base/+/92c8f59/wifi/java/android/net/wifi/SoftApConfiguration.java#511
     */
    @SuppressLint("NewApi") // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/wifi/java/android/net/wifi/WifiConfiguration.java#1385
    @Deprecated("Class deprecated in framework, use toPlatform().toWifiConfiguration()")
    @Suppress("DEPRECATION")
    fun toWifiConfiguration(): android.net.wifi.WifiConfiguration {
        val (band, channel) = requireSingleBand(channels)
        val wc = underlying as? android.net.wifi.WifiConfiguration
        val result = if (wc == null) android.net.wifi.WifiConfiguration() else android.net.wifi.WifiConfiguration(wc)
        val original = wc?.toCompat()
        result.SSID = ssid?.toString()
        result.preSharedKey = passphrase
        result.hiddenSSID = isHiddenSsid
        apBand.setInt(result, when (band) {
            SoftApConfiguration.BAND_2GHZ -> 0
            SoftApConfiguration.BAND_5GHZ -> 1
            else -> {
                require(isLegacyEitherBand(band)) { "Convert fail, unsupported band setting :$band" }
                -1
            }
        })
        apChannel.setInt(result, channel)
        if (original?.securityType != securityType) {
            result.allowedKeyManagement.clear()
            result.allowedKeyManagement.set(when (securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> android.net.wifi.WifiConfiguration.KeyMgmt.NONE
                // not actually used on API 30-
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> LEGACY_WPA2_PSK
                // CHANGED: not actually converted in framework-wifi
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE,
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> android.net.wifi.WifiConfiguration.KeyMgmt.SAE
                SoftApConfiguration.SECURITY_TYPE_WPA3_OWE,
                SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION -> android.net.wifi.WifiConfiguration.KeyMgmt.OWE
                else -> throw IllegalArgumentException("Convert fail, unsupported security type :$securityType")
            })
            result.allowedAuthAlgorithms.clear()
            result.allowedAuthAlgorithms.set(android.net.wifi.WifiConfiguration.AuthAlgorithm.OPEN)
        }
        // CHANGED: not actually converted in framework-wifi
        if (bssid != original?.bssid) result.BSSID = bssid?.toString()
        return result
    }

    @RequiresApi(30)
    fun toPlatform(): SoftApConfiguration {
        val sac = underlying as? SoftApConfiguration
        val builder = if (sac == null) {
            SoftApConfiguration.Builder()
        } else newBuilder.newInstance(sac) as SoftApConfiguration.Builder
        if (Build.VERSION.SDK_INT >= 33) {
            setWifiSsid(builder, ssid?.toPlatform())
        } else setSsid(builder, ssid?.toString())
        setPassphrase(builder, when (securityType) {
            SoftApConfiguration.SECURITY_TYPE_OPEN,
            SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION,
            SoftApConfiguration.SECURITY_TYPE_WPA3_OWE -> null
            else -> passphrase
        }, securityType)
        builder.setChannelsCompat(channels)
        setBssid(builder,
            if (Build.VERSION.SDK_INT < 31 || macRandomizationSetting == RANDOMIZATION_NONE) bssid else null)
        setMaxNumberOfClients(builder, maxNumberOfClients)
        try {
            setShutdownTimeoutMillis(builder, shutdownTimeoutMillis)
        } catch (e: InvocationTargetException) {
            if (e.targetException is IllegalArgumentException) try {
                setShutdownTimeoutMillis(builder, -1 - shutdownTimeoutMillis)
            } catch (e2: InvocationTargetException) {
                e2.addSuppressed(e)
                throw e2
            } else throw e
        }
        setAutoShutdownEnabled(builder, isAutoShutdownEnabled)
        setClientControlByUserEnabled(builder, isClientControlByUserEnabled)
        setHiddenSsid(builder, isHiddenSsid)
        setAllowedClientList(builder, allowedClientList)
        setBlockedClientList(builder, blockedClientList)
        if (Build.VERSION.SDK_INT >= 31) {
            setMacRandomizationSetting(builder, macRandomizationSetting)
            setBridgedModeOpportunisticShutdownEnabled(builder, isBridgedModeOpportunisticShutdownEnabled)
            setIeee80211axEnabled(builder, isIeee80211axEnabled)
            if (Build.VERSION.SDK_INT >= 33) {
                setIeee80211beEnabled(builder, isIeee80211beEnabled)
                setBridgedModeOpportunisticShutdownTimeoutMillis(builder, bridgedModeOpportunisticShutdownTimeoutMillis)
                setVendorElements(builder, vendorElements)
                val needsUpdate = persistentRandomizedMacAddress != null && sac?.let {
                    getPersistentRandomizedMacAddress(it) as MacAddress
                } != persistentRandomizedMacAddress
                if (needsUpdate) try {
                    setRandomizedMacAddress(builder, persistentRandomizedMacAddress)
                } catch (e: ReflectiveOperationException) {
                    Timber.w(e)
                }
                for (bandType in BAND_TYPES) {
                    val value = allowedAcsChannels[bandType] ?: emptySet()
                    try {
                        setAllowedAcsChannels(builder, bandType, value.toIntArray())
                    } catch (e: InvocationTargetException) {
                        if (value.isNotEmpty()) throw e
                    }
                }
                setMaxChannelBandwidth(builder, maxChannelBandwidth)
                if (Build.VERSION.SDK_INT >= 36) setClientIsolationEnabled(builder, isClientIsolationEnabled)
            }
        }
        return builder.build()
    }

    /**
     * Documentation: https://github.com/zxing/zxing/wiki/Barcode-Contents#wi-fi-network-config-android-ios-11
     * Based on: https://android.googlesource.com/platform/packages/apps/Settings/+/4a5ff58/src/com/android/settings/wifi/dpp/WifiNetworkConfig.java#161
     */
    fun toQrCode() = StringBuilder("WIFI:").apply {
        when (securityType) {
            SoftApConfiguration.SECURITY_TYPE_OPEN, SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION,
            SoftApConfiguration.SECURITY_TYPE_WPA3_OWE -> { }
            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK, SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> {
                append("T:WPA;")
            }
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> append("T:SAE;")
            else -> throw IllegalArgumentException("Unsupported authentication type")
        }
        append("S:")
        append(ssid!!.toMeCard())
        append(';')
        passphrase?.let { passphrase ->
            append("P:")
            append(WifiSsidCompat.toMeCard(passphrase))
            append(';')
        }
        if (isHiddenSsid) append("H:true;")
        append(';')
    }.toString()
}
