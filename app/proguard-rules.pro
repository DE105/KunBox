# General rules
-keepattributes Signature,Exceptions,*Annotation*

# SnakeYAML rules
-dontwarn java.beans.**

# Go (Gomobile) rules
-keep class go.** { *; }
-dontwarn go.**

# sing-box (libbox) rules
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**

# App specific rules for JNI callbacks
-keep class com.kunk.singbox.** { *; }

# Generic rules for missing classes reported in the error
-dontwarn d0.**
