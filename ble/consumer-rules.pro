# FlowBLE consumer ProGuard rules
# These rules are shipped to consuming applications.

-keep class com.flowble.FlowBleClient { *; }
-keep class com.flowble.BleConnection { *; }
-keep class com.flowble.BleScanner { *; }
-keep class com.flowble.ScannerConfig { *; }
-keep class com.flowble.model.** { *; }
-keep class com.flowble.exception.** { *; }
