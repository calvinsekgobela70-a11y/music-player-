# Ceecept release shrink rules.
-keep class com.ceecept.music.audio.** { *; }
-keep class com.ceecept.music.playback.PlayerService { *; }
-dontwarn java.lang.invoke.StringConcatFactory
