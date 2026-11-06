# OmarchyTV launcher. Manifest components and XML views are kept by AAPT's generated rules.
# Stack traces stay readable in bug reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# PopcornClaw: started by name through `app_process` (root voice helpers, the assistant-role setter), so R8
# must keep them, their main() and their nested classes.
-keep class xyz.vvzvlad.popcornclaw.VoiceHelper { *; }
-keep class xyz.vvzvlad.popcornclaw.VoiceHelper$* { *; }
-keep class xyz.vvzvlad.popcornclaw.AssistRole { *; }
-keep class xyz.vvzvlad.popcornclaw.AssistRole$* { *; }
