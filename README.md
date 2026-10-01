# 云雀灵感闪现 (YunqueSpark)

> 让多年笔记的价值流进大脑的最后一公里。

一个 Android 上的个人笔记 AI 工作台：自动同步小米笔记，由 app 内置 AI 自主完成分类、碎片统合、知识库构建与灵感扩充，并把每天最值得看的一条做成定时推送的「灵感卡片」。Obsidian 是展示容器，app 负责生产。

## 核心理念

1. **骨架定规范，AI 负责生产**。app 只提供数据层、工具调用 harness 和任务规范；分类、统合、知识库、扩充、选卡全部由内置 AI 通过工具调用自主完成，人不介入生产过程。
2. **原始层不可变**。笔记原文只读；AI 的一切产出（分类元数据、知识库页、草稿、卡片）独立存放，可随时整体删除重来。
3. **LLM Wiki 模式**（源自 [Karpathy 的 gist](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f)）：知识被增量编译成持久的 wiki（索引+日志+交叉引用+可溯源链接），而非每次查询重新检索推导。
4. **最后一公里**：每日卡片 ≤140 字 + 一句点评 + 原文链接，扫一眼就能让旧笔记重新进入大脑。

## 功能

| 功能 | 说明 |
|---|---|
| 小米笔记同步 | i.mi.com v2 接口，双路径枚举 + 账本增量 + 附件本地化，只增改不删 |
| 账号登录 | 无头 WebView 注入登录（自动填表/过协议弹窗/短信验证码），Cookie 过期自动续期 |
| 笔记分类 | AI 自建 6~14 类体系并逐篇归类，概念不明自动联网核查 |
| 碎片统合 | 识别跨年份的同主题碎片链，串成完整想法 + 时间线 + 原文清单 |
| 知识库 | 按 LLM Wiki 模式增量消化笔记为 concept/entity/topic 页面 |
| 灵感扩充 | 把一句话火花扩成半成品（可行性调研 + 方案 + 最小下一步） |
| 每日卡片 | 系统级真实闹钟（setAlarmClock）定时推送，重启自愈，点通知直达卡片日历 |
| 卡片日历 | 月历网格回顾任意一天的卡片，纯本地读取零等待，可收藏/编辑 |
| 问云雀 | 对话式访问全库与知识库，答案可沉淀回知识库，引用可跳原文 |
| Obsidian 同步 | 产出自动写入手机 Obsidian vault（SAF 授权），链接改写为 vault 内相对路径 |
| 自动流水线 | 拉到新笔记或存在存量待办时自动运行 分类→统合→知识库→扩充 |
| CLI 控制台 | app 内嵌本机 HTTP 服务，`./yq` 脚本经 adb forward 远程控制全部能力 |

## 架构

```
┌─ 数据层 ──────────────────────────────────────┐
│ SQLite + FTS4（notes/kb_pages/drafts/cards/…） │
│ MinoteSync   i.mi.com v2 同步引擎              │
│ ObsidianSync SAF 写入 vault                    │
├─ AI harness（借鉴 Operit 的工具调用设计） ──────┤
│ AnthropicClient  DeepSeek Anthropic 兼容层/SSE  │
│ ToolRegistry     工具=数据类+接口，错误不抛出    │
│ AgentLoop        多轮循环 + 上下文预算裁剪       │
│ Tools            16 个内置工具                  │
│ Prompts/Jobs     任务规范与装配                 │
├─ 呈现层（Jetpack Compose, Material3） ─────────┤
│ 首页卡片 / 对话 / 任务台 / 笔记库 / 知识库 /     │
│ 卡片日历 / 设置；深浅双主题全 token              │
└───────────────────────────────────────────────┘
```

## 快速开始

```bash
# 1. 配置 API key（DeepSeek，Anthropic 兼容端点）
echo "yunque.ai_key=sk-你的key" >> local.properties
#    也可不配置，装到手机后在 app 设置里填写

# 2. 构建（Android Studio 打开或命令行）
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**首次使用**（手机上）：

1. 设置 → 内置 AI：填 DeepSeek API Key（[获取](https://platform.deepseek.com)）；
2. 设置 → 小米笔记同步 → 账号登录（或粘贴 Cookie）；
3. （可选）设置 → 同步到 Obsidian：选择 vault 文件夹；
4. （可选）设置 → 每日定时推送：开启并选时间。

之后全自动：打开 app 静默增量同步 → 拉到新笔记自动整理（分类→统合→知识库→扩充）→ 产出同步进 Obsidian → 每天定时推送当日灵感卡片。

**种子数据（可选）**：如已有 Obsidian 形式的小米笔记导出，可打进 apk 实现离线开箱即用：

```bash
python3 tools/gen_seed.py "/path/to/vault/minote" app/src/main/assets/seed/notes.jsonl
```

不做这步也行，登录后云同步会拉全库。

## CLI（电脑端控制手机上的 app）

```bash
./yq health            # 存活与状态
./yq stats             # 库统计
./yq tasks             # 任务状态
./yq pipeline          # 启动 分类→统合→知识库→扩充 流水线
./yq sync / obsidian   # 云同步 / 同步 Obsidian
./yq cards / note <id> / kb [slug]
YQ_SERIAL=<序列号> ./yq …   # 多设备切换
```

## 隐私

- 笔记与产出全部存在手机本地（app 沙箱），不上传任何第三方服务器；
- 小米账号密码仅存本机，只用于向 account.xiaomi.com 完成登录换取同步 Cookie；
- AI 调用仅将必要上下文发往你所配置的 DeepSeek API；
- 种子数据（你的全部笔记）默认被 `.gitignore` 排除，谨防误提交。

## 声明

小米笔记接口为非官方逆向（参考社区成果），仅供个人备份与学习研究，请合理使用；接口随时可能变更。本项目与小米公司无关。

## License

[MIT](LICENSE) © [Halo0sama](https://github.com/Halo0sama)
