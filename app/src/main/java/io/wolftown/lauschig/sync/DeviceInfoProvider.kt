package io.wolftown.lauschig.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoProvider @Inject constructor(@ApplicationContext private val context: Context) {
    fun get(): DeviceInfoDto = DeviceInfoDto(
        deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
        clientName = "Lauschig",
        clientVersion = BuildConfig.VERSION_NAME,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkVersion = Build.VERSION.SDK_INT.toString(),
        deviceName = Build.MODEL,
    )
}
