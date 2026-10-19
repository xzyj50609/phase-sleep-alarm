# Phase 1.4.0 验证报告

日期：2026-10-19。版本：**1.4.0 (17)，本地调试包**。本地实现、构建、模拟器回归和资源核验完成；这不等于真实 root 手机或用户验收通过。

## 交付与范围

- 工作目录：`phase-public-140/`，分支 `feature/1.4.0`，基于 1.3.1 的 `6589a12`。源 checkout 的图标草稿分支未合并。
- APK：`app/build/verification/phase-1.4.0-debug.apk`，17,703,512 字节。SHA-256：`d8367020f278279a6d5ab0263544b099c3c865330836b8298e7ca612ef1b532f`。模拟器安装包实测哈希与此一致。
- 完整周期候选由 core 生成 FAST / EXPECTED / SLOW，入睡误差与周期误差是输入参数。EXPECTED 保留原时间，包括原有秒值；只对偏移量使用方案的 roundToInt，不取整候选时间。
- root 大卡片和备选提供直接设置时间的按钮；360dp 下备选的文字与三个时间分行排，确保时间完整。点文字仍然只选中。普通模式保留原交接和确认流程，只新增周期误差提示。
- 重算提示读取持久化操作记录，按分钟刷新及回到前台重判。旧闹钟检查区分三态，root 在独立后台执行器读取，5 秒超时；即使底层读取不响应中断，也能及时返回“没能确认”。检查不调用新建、启用、关闭或删除接口。
- 五种模式支持原生点选、跟手横拖、松手吸附；拖动期间不提交模式。提供 Tab 与 selected 语义、系统手势排除，使用主题色和可中断动画。
- 首页去掉版本号，设置底部增加关于、项目主页和离线许可。版本从已安装包读取。许可由 Gradle Sync 从仓库原文件生成 assets，不维护第二份手抄文本。
- C2 三个图标原样搬运，独立提交 `80b9414`。没有新增依赖。首页 717 行；新界面拆为独立组件。

## 测试先行与不变项

先写 WakeOptionsTest、PreviousAlarmTest、RecommendationRegressionTest 和 controller 测试，再实现。第一轮 `:core:test` 在 `:core:compileTestKotlin` 失败，因为新模型与函数尚不存在；实现后通过。

最终 core **84/84，无失败、错误或跳过**。其中一项使用从 `6589a12` 冻结的 `LegacySleepCalculator131`，逐个比较 **540 组输入**的新旧输出：五种模式 × 有无必醒 × 六个上床时间 × 80/95/120 分钟周期 × 5/20/60 分钟延迟。比较所有旧字段，包括推荐对象、备选内容和顺序、标签、理由、质量、说明和两段式分析；只排除新增的 wakeOptions。冻结源码也已与原提交核对。

额外核对了 AlarmSetter、StandardClockResolver、PrefsHelper、SleepLogStore、AlarmOperationStore、AlarmMode、WheelPicker 的内容均未改；首页 InputsCard 整段内容未改，Gradle dependencies 声明未改。证据：[core 与不变项](evidence/core-tests.json)。

## 实际命令与结果

工作目录为本 worktree。Gradle 经工作区 detached_gate 启动；下列 `set` 为该后台 cmd 进程的命令，明确清空 `PHASE_SIGNING_DIR`，不提供正式签名材料。

```bat
set "PHASE_SIGNING_DIR=" && gradlew.bat :core:test --no-daemon --console=plain
set "PHASE_SIGNING_DIR=" && gradlew.bat :core:test --tests com.example.smartalarm.core.interaction.PreviousAlarmTest --offline --max-workers=2 --daemon --console=plain
set "PHASE_SIGNING_DIR=" && gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :clockfixture:assembleDebug --offline --max-workers=2 --no-daemon --console=plain
set "PHASE_SIGNING_DIR=" && gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest --offline --max-workers=2 --daemon --console=plain
```

| 实际运行 | 结果 |
|---|---|
| `20261019-084002-phase140-core-red` | 预期失败：新增 API 未实现，compileTestKotlin 失败 |
| `20261019-084147-phase140-core-green` | 初版 core 82 项通过；随后补了秒值不取整用例，最终 84 项 |
| `20261019-084445-phase140-app-first` | 冷构建触发 900 秒单进程超时，exit 124，存活子进程 0；未当作构建通过 |
| `20261019-090112-phase140-build-offline` | exit 0，BUILD SUCCESSFUL，6 分 32 秒；包括两个测试时钟 |
| `20261019-090926-phase140-final-build` | exit 0，BUILD SUCCESSFUL，4 分 49 秒 |
| `20261019-094458-phase140-final-assertions` | exit 0，三项构建通过，19 秒 |
| `20261019-095016-phase140-dst-red` | 补充夏令时失败用例，5 项中 1 项失败，证实重复时分判定问题 |
| `20261019-095129-phase140-dst-green` | 修复后 exit 0，三项构建通过，20 秒；core 最终 84 项 |

运行目录位于工作区 `scripts/gate_runs/`。构建保留旧有的 android.overridePathCheck 实验性提示和弃用 API 警告，不影响门槛结果。

模拟器为自有 API 36 / x86_64，串号 `emulator-5590`；1080px、密度 480，即 360dp。所有 ADB 指定该串号。因原有模拟器包与调试签名不同，仅在该模拟器卸载旧相位后安装；没有操作用户手机。

```powershell
python tests/run_140_instrumentation.py --adb C:\Users\zyj\AppData\Local\Android\Sdk\platform-tools\adb.exe --serial emulator-5590 --expected-apk-sha d8367020f278279a6d5ab0263544b099c3c865330836b8298e7ca612ef1b532f --output-dir app/build/verification/delivery-suite

python tests/verify_140_restart.py --adb C:\Users\zyj\AppData\Local\Android\Sdk\platform-tools\adb.exe --serial emulator-5590 --output-dir app/build/verification/restart-final --expected-apk-sha d8367020f278279a6d5ab0263544b099c3c865330836b8298e7ca612ef1b532f

python tests/verify_140_artifact.py --apk app/build/outputs/apk/debug/app-debug.apk --aapt2 C:\Users\zyj\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt2.exe --icon-source ../docs/icon-design/android --output docs/1.4.0/evidence/artifact.json
```

- 完整合成仪器回归 **24/24，无失败或跳过**：新增 controller/UI、APK 身份、原有 controller、普通时钟交接、备份恢复。覆盖实际 UI 按钮、所有场景的网关实参和记录、超时、异常、普通方式不启动 root、旧的未决操作保护、手动保存与取消、防重复、备份回滚。证据：[运行摘要](evidence/runtime-summary.json)。
- 另 **1 项**种子仪器测试通过；随后实际终止相位进程，检查旧进程消失，再启动新进程。提示条仍存在，已保存 XML 和截图。仅 force-stop 相位自身，没有 force-stop 任何系统时钟。证据：[进程恢复](evidence/restart.json)。
- APK 三个图标的编译后向量内容与定稿逐项一致，工作区文件与定稿忽略换行符后完全一致；adaptive icon 含 monochrome。四份许可 assets 与仓库原文逐字节一致，版本正确且无 INTERNET 权限。证据：[APK 核验](evidence/artifact.json)。
- 开发期间修正了自动化的三个定位问题：selected 在标签父节点；设置页中间的手势会进入参数滚轮；Toast 需在点击前监听事件。ADB 的 exit 0 不能代表 JUnit 成功，测试入口现在解析 JUnit 结果并拒绝失败、跳过或崩溃，最终结果才计为通过。
- 最后审核补了夏令时重复/跳时时分的失败用例，再修复普通方式的“下一次出现”计算：枚举合法偏移，选择未来最近一次；不存在的时分跳过。没有改睡眠候选、推荐或时钟写入策略。

## 第 7 节验收剧本逐条对照

| 编号 | 已自动验证的内容 | 用户真机验收边界 |
|---|---|---|
| 1 | 540 组推荐字段与旧版一致；普通界面无三个按钮；正常 5 周期提示 20 分钟；合成时钟的打开、保存、返回确认、取消、防重复通过 | 不同品牌实际时钟表现仍以用户手机为准 |
| 1a | 必醒后直接起无提示；正常周期档有提示；1 周期为 13；短睡无提示；core 固定候选无选项 | 可在手机复核排版和使用感受 |
| 2 | EXPECTED 与大时间相同，不取整；root 界面出现三个场景，普通界面不出现 | root 手机实际外观待验 |
| 3 | n=1–6 为 13/15/17/19/20/22；80 分钟周期上限 20；回笼只累计自己的周期；跨午夜、参数变化及相邻档不交叉通过 | 无额外真机计算步骤 |
| 4 | 定时样例中心 08:02，SLOW 实际传给合成网关 08:22；真实 UI 点时间也走同一路径，回执时间一致 | **真实 ColorOS 的创建/复用、开启与读回由用户验收** |
| 5 | FAST/EXPECTED/SLOW 的持久化起床时间与时长均来自自己的选项；08:22 对应 495 分钟，较中心多 20；确认来源与幂等记录通过 | 历史页实际观感可在手机核对；本轮历史渲染代码未改 |
| 6 | 点备选文字只选中；操作记录、成功目标和历史条数均不变，不启动新的操作 | 可在手机复核触摸手感 |
| 7 | 必醒两段式、选档、文案与所有旧字段回归一致；直接起、30 分、目标时间和短睡无三选项 | 已知的截止规则不改，见下方风险 |
| 8 | confirmed 状态和 15 分钟至 4 小时的边界逐项单测；注入 20 分钟前记录显示提示；刚设和过期隐藏 | 未用真实等待 20 分钟替代合成年龄测试 |
| 9 | 已实际退出并重启相位进程，磁盘记录恢复，提示条存在；不是仅新建一个内存对象 | 种子是合成操作记录，不代表真实闹钟已在厂商时钟创建 |
| 10 | root/普通三态纯函数；更早闹钟遮挡、空列表、禁用、失败、超时、锁定均覆盖；午夜、时区及夏令时重复/跳时时分通过；两种文案与已关闭不弹窗通过；读操作不写闹钟 | **真实 root 开启/关闭后的读回和“查看闹钟”到达目标时钟由用户验收**；普通方式的信息有下一个闹钟的可见性限制 |
| 11 | 快速点选；实际触摸按下/移动但不松手时模式仍原值，松手后才变小睡；纵向不切换；selected 父语义、关闭动画、字体 1.0/1.3/2.0 通过。截图已看，保早餐和三个时间完整 | 真实 TalkBack 操作、厂商边缘返回和主观流畅感仍需手机验收 |
| 12 | 首页无版本；关于显示已安装包 1.4.0 (17)；许可内容和滚动区域可读；浏览器 ACTION_VIEW 与 URL 被原生 monitor 核对；抛 ActivityNotFoundException 后观察到提示事件且页面不崩溃 | monitor 不等于实际浏览器网页渲染；可在手机点一次主页 |
| 13 | 定稿源、worktree、最终 APK 向量一致；含单色资源和 adaptive monochrome；图标独立提交 | 桌面缓存、实际主题图标着色和厂商裁切由用户验收 |

本地截图在 `app/build/verification/screenshots/delivery/`，进程重开截图在 `app/build/verification/restart-final/`，均为未入库的构建产物。已人工看过普通首页、2.0 倍字体模式标签与完整三个时间、关于页、许可页、重开提示条。

## 剩余风险与明确边界

1. 本轮没有在真实 root 手机回归，未测真实响铃、厂商后台行为、物理声音或正式签名升级。root 继续为实验性功能；调试签名不是正式发布签名。
2. 按方案保留的规则：没有必醒时，上课模式可能推荐晚于截止的点；root 的“躺得久”也可能晚于上课截止。本轮未加过滤或改变选档。
3. 10 分钟入睡误差与 8 分钟周期误差是方案给定的建模假设，未用用户实测校准。普通方式被更早闹钟遮挡时只能回答“没能确认”。
4. 已有的系统浅色状态栏对比度问题仍在。大字号需上下滚动查看完整卡片；时间本身没有被控件截断。
5. 未新增依赖，没有运行 NativeClockTest、ClockResolutionTest 或旧私有仓库设备脚本；没有对系统时钟 force-stop，没有读取正式签名私钥与密码。

## 源 checkout 的状态变化

开始时只读观察到 `phase-public` 在 `brand/icon-redesign` / `df47401`，三个图标为未提交的 C2 内容。收尾时分支和 HEAD 仍相同，但三个文件已是旧草稿内容且 Git 干净，文件修改时间为 2026-10-19 08:41。

本轮在该目录仅执行了只读检查和方案指定的 worktree add，没有执行 commit、checkout、restore 或 reset；这项内容变化的来源未核实，也未尝试回写或还原原目录。**C2 定稿仍完整保存在指定的 docs/icon-design/android 源目录、本 worktree 与独立图标提交中**。后续继续本 worktree，不要用原目录重建本次交付包。

## 发布边界

只在 `feature/1.4.0` 本地提交。没有推送、合并 main、打 tag 或发布；上级私有仓库与签名目录没有作为源码或交付内容。
