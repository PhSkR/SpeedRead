# JSoup + Readability4J power UrlFileParser. These rules are contributed to the app's
# R8 pass via consumerProguardFiles (see data-importers/build.gradle.kts) — the app has
# isMinifyEnabled = true, and without these keeps, reflective DOM parsing + selectors
# would silently break in release builds.
-keep class org.jsoup.** { *; }
-keep class net.dankito.readability4j.** { *; }
-dontwarn org.jsoup.**
-dontwarn net.dankito.readability4j.**
