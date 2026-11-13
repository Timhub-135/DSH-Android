# JNI entry (libdshengine.so looks up this class/method by name)
-keep class io.github.timhub135.dsh.engine.NodeRuntime { native <methods>; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.timhub135.dsh.**$$serializer { *; }
# pdfbox-android: BouncyCastle (encrypted PDFs) and the JP2/JBIG2 image codecs are optional and not bundled
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.**
