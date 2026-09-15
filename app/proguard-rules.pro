-keep class com.focuskiosk.admin.FocusDeviceAdminReceiver { *; }
-keep class com.focuskiosk.admin.BootReceiver { *; }
-keep class com.focuskiosk.updater.InstallResultReceiver { *; }
-keepclassmembers class * extends android.app.admin.DeviceAdminReceiver { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
