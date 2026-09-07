# The native layer resolves these by JNI name, so they cannot be renamed.
-keepclasseswithmembernames class com.amaral.driverlab.vk.NativeVulkan {
    native <methods>;
}
-keep class com.amaral.driverlab.vk.NativeVulkan { *; }

# kotlinx.serialization generates serializers that are looked up reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.amaral.driverlab.** {
    *** Companion;
}
-keepclasseswithmembers class com.amaral.driverlab.** {
    kotlinx.serialization.KSerializer serializer(...);
}
