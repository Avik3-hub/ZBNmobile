# OpenCV loads Java wrappers from native code.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Apache POI and XMLBeans use generated schemas and reflection.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**

# Optional desktop/OSGi integrations are not used by the Android app.
-dontwarn java.awt.**
-dontwarn org.osgi.framework.**
