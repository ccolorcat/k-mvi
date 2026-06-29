# K-MVI public API used by consumer projects.
-keep class cc.colorcat.mvi.** { *; }

# K-MVI resolves user-defined MVI types at runtime through exact Class/KClass identity.
# Keep these subtypes reachable when consumer apps enable R8 shrinking.
-keep class ** implements cc.colorcat.mvi.Mvi$Intent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Intent$Concurrent { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Intent$Sequential { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$State { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$Event { *; }
-keep class ** implements cc.colorcat.mvi.Mvi$PartialChange { *; }
