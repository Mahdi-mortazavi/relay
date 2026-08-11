# Project-specific ProGuard/R8 rules.

# Full Mode's forwarder (/wg, built by gomobile). Every reference to it from
# Kotlin goes through Class.forName with a string — deliberately, so a build
# without the library still compiles — which means R8 sees no reference at all
# and is free to delete the whole thing. It happens to survive today; the day it
# does not, Full Mode disappears from release builds only, the option stops
# being offered, and every debug build still works. Keep it explicitly.
-keep class relaywg.** { *; }
-keep class go.** { *; }
-dontwarn go.**
