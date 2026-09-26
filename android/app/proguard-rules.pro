# Gson: keep @SerializedName and the TypeToken mechanism.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Auth: RegisterRequest/RegisterResponse are private inner classes serialized with Gson.
# @SerializedName protects field names; these rules protect the classes themselves.
-keep class ru.keegoo.companion.data.auth.**$Register* { *; }
