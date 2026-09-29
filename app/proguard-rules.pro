# R8 rules — V1.0 release (isMinifyEnabled + isShrinkResources)
#
# Principle: only keep rules that are absolutely necessary; each one states what it protects
# and why. Most libraries ship their own consumer rules (kotlinx-serialization-core's
# kotlinx-serialization-common.pro, MSAL's proguard.txt), which AGP merges automatically —
# duplicating them here would only let them go stale on library upgrades.
#
# ⚠️ Damage from editing this path only surfaces at runtime: compile success ≠ correct execution.
# After any change, install the APK on a device and exercise the main flows
# (especially reading existing sessions/projects).

# ----- persisted enums -----
# The **names** of SessionNodeType / SessionNodeStatus land in the SQLite columns node_type / status,
# and are read back via SessionNodeType.valueOf(String) (core/session/store/SqliteSessionStore.kt).
# R8 will strip enum constants that appear unreferenced statically — reading old data then throws
# IllegalArgumentException. A classic bug that only breaks on historical data. Keep the whole class;
# the cost is two small enums.
-keep enum com.yangz3720.simplywork.core.session.SessionNodeType { *; }
-keep enum com.yangz3720.simplywork.core.session.SessionNodeStatus { *; }

# ----- optional Nimbus JOSE backend pulled in by MSAL -----
# MSAL depends on com.nimbusds.jose (JOSE/JWT). Nimbus has multiple Ed25519 / X25519 / XChaCha20
# backend implementations; one of them uses Google Tink. Our classpath lacks com.google.crypto.tink,
# so R8 reports "missing class". MSAL's token validation goes through JCA (java.security)'s RS256
# backend — these classes are never touched at runtime.
#
# Suppress warnings for exactly these 5 classes (copied verbatim from R8's missing_rules.txt),
# NOT `-dontwarn com.nimbusds.**` / `-dontwarn com.google.crypto.tink.**`: if a future MSAL upgrade
# actually starts depending on Tink, we want an explicit warning instead of silently shipping a broken
# crypto path.
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign$KeyPair
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign
-dontwarn com.google.crypto.tink.subtle.Ed25519Verify
-dontwarn com.google.crypto.tink.subtle.X25519
-dontwarn com.google.crypto.tink.subtle.XChaCha20Poly1305
