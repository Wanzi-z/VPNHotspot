package be.mygod.vpnhotspot

import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.annotation.Size
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import be.mygod.librootkotlinx.NoShellException
import be.mygod.vpnhotspot.net.DhcpWorkaround
import be.mygod.vpnhotspot.room.AppDatabase
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.util.DeviceStorageApp
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.privateLookup
import be.mygod.vpnhotspot.widget.SmartSnackbar
import com.google.android.gms.dynamite.DynamiteModule
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ParametersBuilder
import com.google.firebase.analytics.logEvent
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.provider.FirebaseInitProvider
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.invoke.MethodType
import java.util.Locale

class App : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var app: App
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate() {
        super.onCreate()
        app = this
        deviceStorage = DeviceStorageApp(this)
        // alternative to PreferenceManager.getDefaultSharedPreferencesName(this)
        deviceStorage.moveSharedPreferencesFrom(this, PreferenceManager(this).sharedPreferencesName)
        deviceStorage.moveDatabaseFrom(this, AppDatabase.DB_NAME)
        BootReceiver.migrateIfNecessary()
        Services.init { this }

        // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
        DynamiteModule::class.java.getDeclaredField("zzg").apply { isAccessible = true }.set(null, false)
        // call super.attachInfo get around ProviderInfo check
        FirebaseInitProvider::class.java.privateLookup().findSpecial(ContentProvider::class.java, "attachInfo",
            MethodType.methodType(Void.TYPE, Context::class.java, ProviderInfo::class.java),
            FirebaseInitProvider::class.java).bindTo(FirebaseInitProvider()).invokeWithArguments(deviceStorage, null)
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("uname.release", Os.uname().release)
            setCustomKey("build", Build.DISPLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setCustomKey("extension_s",
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S))
        }
        Timber.plant(object : Timber.DebugTree() {
            @SuppressLint("LogNotTimber")
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (t == null) {
                    if (priority != Log.DEBUG || BuildConfig.DEBUG) Log.println(priority, tag, message)
                    FirebaseCrashlytics.getInstance().log("${"XXVDIWEF".getOrElse(priority) { 'X' }}/$tag: $message")
                } else {
                    if (priority >= Log.WARN || priority == Log.DEBUG) {
                        Log.println(priority, tag, message)
                        Log.w(tag, message, t)
                    }
                    if (priority >= Log.INFO && t !is NoShellException) {
                        FirebaseCrashlytics.getInstance().recordException(t)
                    }
                }
            }
        })
        ServiceNotification.updateNotificationChannels()
        if (DhcpWorkaround.shouldEnable) DhcpWorkaround.enable(true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ServiceNotification.updateNotificationChannels()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) GlobalScope.launch {
            RootManager.closeExisting()
        }
    }

    /**
     * This method is used to log "expected" and well-handled errors, i.e. we care less about logs, etc.
     * logException is inappropriate sometimes because it flushes all logs that could be used to investigate other bugs.
     */
    fun logEvent(@Size(min = 1L, max = 40L) event: String, block: ParametersBuilder.() -> Unit = { }) {
        FirebaseAnalytics.getInstance(app).logEvent(event) {
            block(this)
            Timber.i(if (bundle.isEmpty) event else "$event, extras: $bundle")
        }
    }

    /**
     * LOH also requires location to be turned on. So does p2p for some reason. Source:
     * https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/53e0284/service/java/com/android/server/wifi/WifiServiceImpl.java#1204
     * https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/53e0284/service/java/com/android/server/wifi/WifiSettingsStore.java#228
     */
    inline fun <reified T> startServiceWithLocation(context: Context) {
        if (Build.VERSION.SDK_INT < 33 && location?.isLocationEnabled != true) try {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            Toast.makeText(context, R.string.tethering_location_off, Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            app.logEvent("location_settings") { param("message", e.toString()) }
            SmartSnackbar.make(R.string.tethering_location_off).show()
        } else context.startForegroundService(Intent(context, T::class.java))
    }

    lateinit var deviceStorage: Application
    val english by lazy {
        createConfigurationContext(Configuration(resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
    }
    val pref by lazy { PreferenceManager.getDefaultSharedPreferences(deviceStorage) }
    val clipboard by lazy { getSystemService<ClipboardManager>()!! }
    val location by lazy { getSystemService<LocationManager>() }

    val hasTouch by lazy { packageManager.hasSystemFeature("android.hardware.faketouch") }
    val customTabsIntent by lazy {
        CustomTabsIntent.Builder().apply {
            setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(resources.getColor(R.color.light_colorPrimary, theme))
            }.build())
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(resources.getColor(R.color.dark_colorPrimary, theme))
            }.build())
        }.build()
    }
}
