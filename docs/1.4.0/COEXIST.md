# 相位·测试版（1.4.0）

2026-10-19 用户要求：保留手机上的相位 1.2.0，另外生成可共存的 1.4.0 测试包。

## 安装与数据

- 共存包名：`com.example.smartalarm.test`，桌面名称：**相位·测试版**。
- 版本：`1.4.0-test (17)`，使用本机 debug 签名；不读取正式签名材料。
- 原版继续使用 `com.example.smartalarm`。安装测试版不需要卸载原版，不会覆盖原版应用数据。
- 两版的设置、睡眠记录、操作记录各自独立，测试版首次打开使用默认设置。
- 测试版如需 root，需要在 root 管理器里单独授权；旧版的授权不会自动转给它。
- 两版交接的是同一个系统时钟。root 模式启用或创建的闹钟会在同一时钟列表中出现，不是两套独立闹钟。

## 维护

只增加 `coexist` 构建类型和资源覆盖，复用 1.4.0 的业务代码。正式版包名、名称、升级路径和源码 namespace 保持原样。
无障碍服务显示名也加上「相位·测试版」，方便在系统设置中区别。

```bat
set "PHASE_SIGNING_DIR=" && gradlew.bat :app:assembleCoexist :app:assembleDebug --offline --max-workers=2 --daemon --console=plain
```

原始输出：`app/build/outputs/apk/coexist/app-coexist.apk`。
交付副本：`app/build/verification/phase-1.4.0-test.apk`。

## 验证结果

- `:app:assembleCoexist :app:assembleDebug` 最终通过（exit 0，2 分 30 秒），后台构建记录：`20261019-161119-phase140-coexist-final-build`。
- 首次构建缺少按构建类型提供的 `FaultInjectingGateway` 类而失败。现复用 `src/release/kotlin` 的直通实现；不带文件控制的故障注入，不复制或改写业务逻辑。
- AAPT 从成品 APK 确认包名、`1.4.0-test (17)`、桌面名称和完整 Activity 类名；APK Signature Scheme v2 校验通过。
- 成品及模拟器已安装包 SHA-256 相同：`f997c76180a1b715d2d5f401788ae37a3985e79a782ffc7ca4f27bc5a1533ba5`。
- 自有 API36 模拟器 `emulator-5590`：先安装归档的真实 1.2.0，再安装共存包；两版均成功启动。
- 原版保持 `1.2.0`，其 APK 路径、APK 哈希、UID 和测试数据标记均未变化。两版 UID 不同，测试版读取旧版私有数据被系统拒绝；测试版首页已截图查看。证据：[共存核验](evidence/coexist.json)。
- 普通 debug APK 重新构建后的哈希仍为原交付的 `d8367020f278279a6d5ab0263544b099c3c865330836b8298e7ca612ef1b532f`。
- 本轮只改打包配置与名称资源，未改 core，不重复运行此前 84 项 core 测试或整套业务回归。

手机尚未连接或安装。模拟器结果不等于手机上的 root 桥接、响铃或用户体验验收。
