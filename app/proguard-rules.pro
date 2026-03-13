# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Wi-Fi P2P related classes
-keep class android.net.wifi.p2p.** { *; }
