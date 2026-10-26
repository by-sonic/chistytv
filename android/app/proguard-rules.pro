# libadb находит Conscrypt через рефлексию: OpenSSLProvider для TLS и Conscrypt.exportKeyingMaterial для подключения по коду
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
# kotlinx.serialization: база пакетов
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ru.bysonic.chistytv.core.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ru.bysonic.chistytv.core.**$$serializer { *; }
