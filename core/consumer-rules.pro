# K-MVI requires no consumer keep rules. Runtime routing uses direct Class/KClass
# references and identity checks rather than name-based reflection.
#
# Apps that access their own MVI types through reflection, serialization, or JNI
# must provide rules for those app-specific usages.
