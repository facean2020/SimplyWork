# R8 规则 —— V1.0 release（isMinifyEnabled + isShrinkResources）
#
# 原则：这里只放**确有必要**的规则，每条都写明为谁、为什么。绝大多数库自己带
# consumer 规则（kotlinx-serialization-core 的 kotlinx-serialization-common.pro、
# MSAL 的 proguard.txt 都由 AGP 自动并入），不需要在这里重复抄一遍 —— 抄了只会
# 让下次升级库时规则悄悄过期。
#
# ⚠️ 这条路径剪坏东西**只在运行时暴露**：编译通过不等于运行正确。任何改动之后，
# 都要装到真机上把主要流程跑一遍（尤其是读已有会话/项目数据这条路）。

# ----- 持久化枚举 -----
# SessionNodeType / SessionNodeStatus 的**名字**会落进 SQLite 的 node_type / status 列，
# 读回时走 SessionNodeType.valueOf(String)（core/session/store/SqliteSessionStore.kt）。
# R8 会剪掉"看起来没有静态引用"的枚举常量，那一刻读老数据就抛 IllegalArgumentException
# —— 典型的只炸在历史数据上的 bug。整类保下来，代价只是两个小枚举。
-keep enum com.yangz3720.simplywork.core.session.SessionNodeType { *; }
-keep enum com.yangz3720.simplywork.core.session.SessionNodeStatus { *; }

# ----- MSAL 带进来的 Nimbus JOSE 的可选后端 -----
# MSAL 依赖 com.nimbusds.jose（JOSE/JWT）。Nimbus 的 Ed25519 / X25519 / XChaCha20 有多个
# 后端实现，其中一个走 Google Tink；我们的类路径上没有 com.google.crypto.tink，所以 R8 报
# "missing class"。MSAL 的令牌校验走 JCA（java.security）的 RS256 后端，这些类运行时根本
# 不会被触碰。
#
# 只精确 dontwarn 这 5 个（照抄 R8 生成的 missing_rules.txt），**不**写成
# `-dontwarn com.nimbusds.**` / `-dontwarn com.google.crypto.tink.**`：将来升级 MSAL 若
# 真的开始依赖 Tink，我们要被明确告知，而不是静默发出一个加密路径已坏掉的包。
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign$KeyPair
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign
-dontwarn com.google.crypto.tink.subtle.Ed25519Verify
-dontwarn com.google.crypto.tink.subtle.X25519
-dontwarn com.google.crypto.tink.subtle.XChaCha20Poly1305

