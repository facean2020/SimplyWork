# SimplyWork — 开发规则

面向在本仓库工作的 agent / 协作者。项目背景与架构见 `docs/PlaningStages/`。

## 每次会话的开发流程（硬规则）

**先开分支 → 本地验证 → 确认没问题才合入 `main`**。三步缺一不可：

1. **先开分支**。动手改代码之前，从最新的 `main` 拉一条新分支：

   ```bash
   git checkout main && git pull --ff-only   # 有远端时
   git checkout -b feat/<主题>               # 或 fix/<主题>
   ```

2. **本地验证**。改完之后必须跑通：

   ```bash
   ./gradlew :app:testDebugUnitTest          # 单元测试
   ./gradlew :app:assembleDebug              # 至少能编译出 APK
   ```

3. **确认没问题才合入 `main`**：

   ```bash
   git checkout main && git merge --no-ff feat/<主题>
   ```

   前提是第 2 步全绿。**测试或编译没过，绝不合并、绝不推到 `main`** —— 留在分支上继续修，或者直接废弃。

## 不直接在 main 上开发

`main` 只接受已验证的合并。半成品、试验性改动、还没跑通测试的东西一律留在分支上。
分支粒度按"一次会话的一件事"来切，不做长期挂着的大分支。
