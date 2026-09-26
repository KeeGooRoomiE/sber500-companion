# Gson: keep @SerializedName and the TypeToken mechanism.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Minify is OFF for release (see build.gradle.kts). These rules matter only if it is turned on.
# Every class Gson reads or writes by reflection must keep its fields' names:
# the Retrofit models and the private register DTOs (top-level classes in DeviceCredentials.kt —
# no '$' in their names, so a '**$Register*' pattern does not match them).
-keep class ru.keegoo.companion.data.api.model.** { *; }
-keep class ru.keegoo.companion.data.auth.RegisterRequest { *; }
-keep class ru.keegoo.companion.data.auth.RegisterResponse { *; }
# Retrofit interfaces with suspend functions
-keep,allowobfuscation interface ru.keegoo.companion.data.api.CompanionApi
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
