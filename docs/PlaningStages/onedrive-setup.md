# OneDrive 接入（个人账号）— 配置与运维

> 状态：已实现（v1.4）。代码侧已填 client id；只差在 Entra 门户登记回调地址就能真机登录。

## 1. 你要做的事：在门户登记回调地址（只需一次）

代码侧已经配好了：`gradle.properties` 的 `msalClientId` 与签名哈希（后者从
`~/.android/debug.keystore` 自动取得，你不用维护）。剩下的是**在 Entra 门户登记回调地址**。

> ⚠️ **回调地址是 `msauth://<包名>/<URL 编码的签名哈希>`，不是 `msal<clientId>://auth`。**
> 后者是 MSAL.NET 的形式；MSAL **Android** 用的是前者（`msal<clientId>://auth` 在本库里属 legacy，
> 官方 README、教程与运行时自检工具 `showExpectedMsalRedirectUriInfo` 全都输出 `msauth://`）。
> 填错的症状是真机登录时报服务器侧 **`AADSTS50011: The reply URL specified in the request
> does not match the reply URLs configured for the application`** —— 看起来完全不像本地配置问题。

### 本项目的确切值

| 项 | 值 |
|---|---|
| 包名称 (Package name) | `com.yangz3720.simplywork` |
| 签名哈希 (Signature hash) | `/ygH5HWo+pDup1fieqjslPyMvok=` |
| 生成的回调地址 | `msauth://com.yangz3720.simplywork/%2FygH5HWo%2BpDup1fieqjslPyMvok%3D` |

签名哈希是 debug keystore 的证书 SHA-1（base64）。**换签名 / 打 release 包 / 换机器后会变**，
那时按第 2.3 节重取，并用 `-PmsalSignatureHash=…` 或在 `gradle.properties` 覆盖。

## 2. Entra 应用注册

你的应用已注册好（client id `b34edc45-900f-491c-a8c4-096d6a90023f`，账户类型「个人 Microsoft 账户」）。
只需补回调地址：

### 2.1 推荐路径（Android 平台，门户自动算编码）

1. [entra.microsoft.com](https://entra.microsoft.com) → **Entra ID** → **应用注册** → 选你的应用
2. **身份验证** → **添加平台**
3. 在「配置平台」里选 **Android**
4. 填：
   - **包名称**：`com.yangz3720.simplywork`
   - **签名哈希**：`/ygH5HWo+pDup1fieqjslPyMvok=` ← **原始值，不要 URL 编码**
5. **配置**

门户会自动生成并登记 `msauth://com.yangz3720.simplywork/%2FygH5HWo%2BpDup1fieqjslPyMvok%3D`。
这样你完全不用操心编码。

### 2.2 备选路径（手动填回调地址）

如果门户的 Android 平台不接受你的哈希，改用 **移动和桌面应用程序**：

1. **身份验证** → **添加平台** → **移动和桌面应用程序**
2. 在「**自定义重定向 URI**」里逐个添加：

```
msauth://com.yangz3720.simplywork/%2FygH5HWo%2BpDup1fieqjslPyMvok%3D
```

同时勾选 `https://login.microsoftonline.com/common/oauth2/nativeclient` 也无害。

**编码规则**：base64 里的 `/` `+` `=` 必须转成 `%2F` `%2B` `%3D`。
本项目的哈希以 `/` 开头，所以未编码时路径会变成 `//ygH5HWo+…`（双斜杠）—— 这是正常的，
文档公式就是「`path = "/" + 原始哈希`」。

### 2.3 重新取签名哈希

```bash
keytool -exportcert -alias androiddebugkey \
  -keystore ~/.android/debug.keystore \
  -storepass android -keypass android \
  | openssl sha1 -binary | openssl base64
```

或直接问 App：`PublicClientApplication.showExpectedMsalRedirectUriInfo(activity)` 会显示
包名、签名哈希、**MSAL 期望的 redirect URI** 以及对应的 manifest 片段 —— 这是官方推荐的
自查手段（它会输出 `msauth://…`，绝不会是 `msal<clientId>://auth`）。

release 包 / 自定义签名：把上一步得到的值写进 `gradle.properties`：

```properties
msalSignatureHash=你的哈希
```

`app/build.gradle.kts` 会把它注入 `BuildConfig.MSAL_SIGNATURE_HASH`（配置文件用）与
manifest 的 `android:path`（用原始未编码值）。debug 构建缺省自动从 debug keystore 现算，
所以**日常开发不用管它**。

### 2.4 权限（委托权限）

**API 权限 → 添加权限 → Microsoft Graph → 委托的权限**：`User.Read`、`Files.ReadWrite`。

个人账号**只支持委托权限**，不支持"应用程序权限"（客户端凭据 / 后台服务）。

`openid` / `profile` / `offline_access` **不要加**：保留作用域，MSAL 会自动补齐并据此下发 refresh token。

### 2.5 完成

重新构建安装 → 项目弹层 → 任一项目行右侧「云端」→「登录 Microsoft 账号」。

## 3. 同步语义（用户需要知道的）

**双向镜像 + 懒惰调度**。项目的沙箱目录（`filesDir/agent_workspace/projects/<projectId>/`）与所选云端文件夹互为镜像。

**首轮方向由"项目里有没有文件"决定**，而不是由"文件夹是新是旧"决定：

| 情况 | 行为 |
|---|---|
| 项目是**空的** | 拉取：云端文件夹的内容整棵下载下来（云端是种子） |
| 项目里有文件、云端文件夹是**空的** | 上传：项目整棵传上去（本地是种子） |
| **两边都有内容** | **拒绝绑定**并提示改选空文件夹。没有可比的时钟/版本号，自动选一边就可能覆盖工作成果 |
| 在云端**新建**文件夹并绑定 | 新建的目录必然为空，所以仍按上面三条判断 |

| 时机 | 行为 |
|---|---|
| 一轮 agent run 结束 | **只推不拉**；本地无改动时零网络请求 |
| 进入 / 切换项目 | 拉取 + 推送，10 分钟内不重复拉（节流） |
| 用户点「立即同步」 | 跳过节流，强制拉取 + 推送 |

### 冲突与安全护栏

- **主键是 driveItem id，不是路径**。路径每次从 `parentReference` 推导 —— 云端重命名/移动不会让本地误判为"删了又建"。
- **变更判据是 eTag + 本地 `size/mtime` 基线**，不做跨时钟的时间戳比较（设备时钟与服务器时钟不可比）。
- **双向都改了同一路径 → 本地胜出**，并在状态行报告"以本地为准"。理由：agent 的产出在本地，用云端覆盖会直接丢工作成果。
- **本地工作区为空而清单非空 → 拒绝删除云端**，只报 warning。这是防"重装 / 清数据后照清单把用户云盘清空"的护栏。
- **单个文件上限 16 MiB**（`DEFAULT_MAX_SYNC_FILE_BYTES`）：下载与上传都是**整文件过一遍内存**（`RemoteDrive.download()` 返回 `ByteArray`），所以不设上限时把空项目绑到含视频的云端文件夹会直接 `OutOfMemoryError`（实测过）。超限的文件**两边都不动**，只在状态行出一条 `跳过 N 个超过 16 MB 的文件`。
- **云端文件夹的删除传播到本地**，反之亦然（镜像的本义）。因此网盘**根目录不可直接绑定**：根层底部的确认按钮直接禁用，引擎也会拒绝 `parentId` 为空的条目。
- 超过 4 MiB 的文件走 **Upload Session** 分块上传；429 读 `Retry-After` 退避；401 强制续期令牌后重试一次；delta 游标过期（HTTP 410）自动重建基线。
- **超时是分档的**：元数据调用（小 JSON）60s；**下载**放宽到 30 分钟（`BULK_REQUEST_TIMEOUT_MS`）—— 大文件在慢链路上超过 60s 只是慢、不是故障，按 60s 判失败会让"拉取"在慢网络上必败。链路真的死掉（长时间收不到字节）仍由 60s 的 socket 超时兜住。

### 懒惰的具体含义

没有轮询、没有后台任务、没有 Webhook 订阅（Webhook 要公网回调地址，端侧不成立）。同步只由用户行为触发，
且推送路径在"没有本地改动"时**一次网络调用都不发**。

## 4. 已知未做

- **Webhook / 近实时**：需要公网可达的回调端点，端侧方案不成立。
- **后台定期同步**：需要 WorkManager + 后台网络配额，与"耗电低"的取向冲突。
- **Agent 直接读写云端**：`fs_*` 工具仍只作用于本地沙箱；云端只是镜像目标。
- **多账号**：`account_mode: SINGLE`，一个 App 连一个个人 OneDrive。
- **共享给我的文件**：当前绑定的文件夹限于自己网盘内。

## 5. 排错

| 现象 | 原因与处置 |
|---|---|
| 面板显示"尚未配置" | `gradle.properties` 的 `msalClientId` 仍是 `REPLACE-…` |
| **`AADSTS50011` / `AADSTS500113`（reply URL 不匹配）** | 门户登记的回调地址与代码发出的不一致 —— 见第 1 节的红字警告。对照 `msauth://<包名>/<URL 编码哈希>` 逐字符检查；换过签名后要重取哈希 |
| `redirect_uri_validation_error` | 只在 broker 分支出现。确认配置里 `use_broker=false`（否则 MSAL 会拿包名+签名哈希去校验 manifest 的 intent-filter） |
| 登录成功但同步报 `HTTP 403` | 委托权限没授到 `Files.ReadWrite`，或用户在该文件夹上没有写权限 |
| 同步报 `HTTP 410` 之后又成功 | 正常：delta 游标过期，已自动重建基线 |
| 状态行长时间"正在同步" | 文件很大或网络差；超时 60s |
| 项目显示"已连接"但本地没文件 | 绑定的是**空**云端文件夹 —— 镜像下这是正确结果；项目里有文件时再点「立即同步」就会传上去 |
| 提示"两边都有内容时无法安全合并" | 项目里有文件、云端文件夹里也有文件。改选一个空文件夹，或先清空其中一边 |
| 根层看不到「选择此文件夹」 | 根不可绑定，所以根层**不渲染**这个按钮（上面的提示说明了原因）。进下一层即可 |
| 状态行「跳过 N 个超过 16 MB 的文件」 | 正常护栏，这些文件两边都没动。要同步更大的文件得改 `DEFAULT_MAX_SYNC_FILE_BYTES`，且应顺带把整文件缓冲改成分块流式（否则只是把 OOM 阈值推高） |
| 下载卡很久 / 慢网络下以前必失败 | 下载的整体超时已放宽到 30 分钟（元数据仍 60s）。仍失败则是链路真的断了，见 socket 超时 |
| 改了签名但行为没变 | 签名哈希是**配置期**读进 manifest 的；`./gradlew --rerun-tasks` 强制刷新（配置缓存可能留着旧值） |

## 6. 相关代码

| 关注点 | 位置 |
|---|---|
| 依赖与注入（client id / 签名哈希） | `gradle/libs.versions.toml`、`app/build.gradle.kts`、`gradle.properties`、`app/src/main/AndroidManifest.xml` |
| MSAL 配置生成（含 redirect URI 拼装） | `infrastructure/cloud/onedrive/OneDriveMsalConfig.kt` |
| 登录 / 静默续期 / 登出 | `infrastructure/cloud/onedrive/OneDriveAuth.kt` |
| Graph REST（含上传会话 / 429 / 401） | `infrastructure/cloud/onedrive/OneDriveGraphClient.kt` |
| 惰性调度 + 串行化 | `infrastructure/cloud/onedrive/OneDriveService.kt` |
| 云端端口 | `core/cloud/RemoteDrive.kt` |
| 同步清单（id ↔ 路径 ↔ 基线） | `core/cloud/SyncManifest.kt` |
| 双向镜像算法 | `core/cloud/ProjectSyncEngine.kt` |
| 项目绑定持久化 | `core/session/ProjectStore.kt`（`OneDriveBinding`） |
| UI | `ui/agent/OneDriveSheet.kt`、`ui/agent/ProjectSheet.kt` |
| 测试 | `core/cloud/ProjectSyncEngineTest.kt`（11 例）、`infrastructure/cloud/onedrive/OneDriveMsalConfigTest.kt`（4 例，锁死回调地址格式） |

Maven 仓库注意：MSAL 的 `common` 传递依赖 `com.microsoft.device.display:display-mask` 只发布在
Microsoft 自己的公开 feed 上，已在 `settings.gradle.kts` 按 group 限定加入。
