# MC-Agents

**MC-Agents** 是一个 [Fabric](https://fabricmc.net/) 服务端模组，用于将 Minecraft 服务器接入大语言模型（LLM）。它提供游戏内命令，支持 AI 对话、自动检索 [Minecraft Wiki](https://minecraft.wiki/)、按玩家保存对话记忆，以及通过结构化 AI 指令可选地控制 Carpet 风格的假人（fake players）。

[English README](README.md)

---

## 运行环境

| 组件 | 版本 / 说明 |
|------|-------------|
| **Minecraft** | **兼容范围**：`>=1.19.4 <1.23`（见 `fabric.mod.json` 中 `depends.minecraft`）。适用于 **Java 版** 正式版，含 **1.19.4**、**1.20.x**（如 1.20.1–1.20.6）、**1.21.x**（如 1.21.1 起至当前 1.21 线）等；不含 1.19.3 及更早。 |
| **Java** | 17 或更高（Gradle 编译目标为 Java 17） |
| **Fabric Loader** | ≥ 0.18.6 |
| **Fabric API** | 必需；须与游戏版本匹配（可从 [Fabric 开发页](https://fabricmc.net/develop/) 或各版本对应的 `fabric-api` 坐标选择）。本地开发默认见 `gradle.properties`（例如 `0.119.4+1.21.4`）。 |

发布构建（`.github/workflows/build.yml`）会为上述兼容范围内、Fabric 已发布对应 API 的各 **Minecraft 正式版** 分别产出 `mc-agents-<游戏版本>.jar`，并上传到 GitHub Release。

### 假人控制（可选但建议安装）

- **[Carpet Mod](https://github.com/gnembon/fabric-carpet)**（或提供相同 `player <名称> rejoin` / `player <名称> kill` 行为的模组）。  
  MC-Agents **未**在 Gradle 中将 Carpet 声明为依赖；上下线通过在服务端执行上述命令实现。未安装 Carpet（或等价功能）时，控制类指令在执行阶段会失败。

---

## 游戏内界面语言

面向玩家的文案使用 Minecraft 语言文件：

- **英语** — `en_us`
- **简体中文** — `zh_cn`

在客户端切换游戏语言即可。AI 回复遵循 `ControlBot.md` 中的规则（默认与用户最近一条输入使用相同语言）。

---

## 大模型 API 兼容性

模组使用 HTTP 调用 **OpenAI 兼容的 Chat Completions** 接口：

- **默认地址** — `https://api.openai.com/v1/chat/completions`（可在配置中修改）。
- **协议** — JSON `POST`，请求头 `Authorization: Bearer <api_key>`、`Content-Type: application/json; charset=utf-8`，请求体包含 `model`、`stream` 与 `messages`（角色：`system`、`user`、`assistant`）。
- **流式输出** — 优先使用 Server-Sent Events（`data: …` 分片）；若收到 HTTP **400**，会回退为**非流式**请求。
- **模型** — 任何实现相同 JSON 模式的提供商（OpenAI、各类代理、兼容网关）均可使用。代码内会对 DeepSeek 风格模型名做规范化（如 `deepseek-chat` / `deepseek-reasoner` 别名）。

### 上下文窗口长度

总上下文长度通过 **[OpenRouter](https://openrouter.ai/)**（`GET https://openrouter.ai/api/v1/models`）解析：若配置了 `openrouter_api_key` 则优先使用该密钥，否则使用 **`api_key`**，以匹配当前 `model` 并用于 token 预算与进度展示。请确保至少有一个密钥能访问 OpenRouter 的模型列表，且包含你所用的模型 id。

---

## 配置

### 配置文件位置

模组会从**世界目录**向上查找，直到出现 `server.properties` 或 `eula.txt`，将该目录视为**服务端根目录**。配置文件固定为：

**`<服务端根目录>/macagent.txt`**

（与常见独立服务端中 `server.properties` 所在目录一致。）

### 格式

纯文本，每行一条 **`键=值`**：

- 以 **`#`** 开头的行与空行会被忽略。
- **首次启动**且不存在 `macagent.txt` 时，模组会**写入默认模板**（含注释说明）并在日志中输出路径。

### 配置项

| 键 | 说明 |
|----|------|
| `api_url` | Chat Completions 地址（默认 OpenAI）。 |
| `api_key` | 访问 `api_url` 的 Bearer 令牌。 |
| `openrouter_api_key` | 可选，专用于 OpenRouter `/models`；留空则回退使用 `api_key`。 |
| `model` | 模型 id（代码内默认：`gpt-4o-mini`）。 |
| `max_context_tokens` | **无效**：总上下文由 OpenRouter 模型元数据**自动解析**（见上文「上下文窗口长度」）；若填写会记录警告并忽略。 |

### 旧版路径迁移

若服务端根目录下尚无 `macagent.txt`，但旧路径已存在配置（例如 `agentsdata/`、世界旁目录、世界目录内等），模组可能**自动复制**到上述新位置。发生迁移时日志会出现 `Migrated config file from …`。

### 生效方式

无需重启即可重载：**`/agent reload`**（权限等级 **2**）。

> **安全提示：** 勿将 `macagent.txt` 提交到公开仓库或随意分发；其中包含密钥。

---

## 系统提示词与假人记录

- **控制假人等行为**由 **`src/main/java/com/mcagents/prompt/ControlBot.md`** 定义（从服务端工作目录加载：模组会从世界目录向上查找该相对路径）。
- **假人名称 ↔ 标签** 映射保存在 **`macagent/agent_records.json`**（JSON 数组，元素为 `{ "bot_name", "tag" }`）。AI 在系统提示中会收到该库的截断副本，用于决定 join/leave。

旧版路径可能自动迁移（如 `agentsdata`、世界 `data/` 等），详见 `Agent.java` / `Recordbot.java`。

---

## 命令（玩家）

| 命令 | 说明 |
|------|------|
| `/agent ask <提示词>` | 携带当前玩家对话历史向 LLM 发送提示。支持**复合指令**（多行，或用 `然后`、`；`、`+` 等分隔），按**顺序**执行多段子任务。 |
| `/agent <提示词>` | 与 `/agent ask <提示词>` 相同。会先匹配已有子命令字面量（如 `search`、`new`、`record` 等），因此仅当第一个词不是保留关键字时，整段内容才作为 ask 提示词。 |
| `/agent search <关键词>` | 从 Minecraft Wiki（英/中）拉取摘要并请模型归纳。 |
| `/agent new` | 清空当前玩家的对话状态。 |
| `/agent record <bot_name> <tag>` | 追加一条假人记录。 |
| `/agent record remove …` | 删除记录（按 bot+tag、仅 bot 名、或全部）。 |
| `/agent botlist` | 列出已记录假人。 |
| `/agent reload` | 重载配置（需 OP / 权限等级 2）。 |

当前实现下，`ask`、`search`、`new` 需由**玩家**执行（不能仅靠控制台）。

---

## 功能概要

- **OpenAI 兼容对话**：**SSE 流式**输出，失败时可非流式回退；若响应含 `reasoning` / `thinking` 等字段会尽量处理。
- **按玩家会话历史**：粗略估算 token；在已知最大上下文时显示**上下文用量进度条**。
- **Minecraft Wiki**：通过 MediaWiki API 访问 **minecraft.wiki** 与 **zh.minecraft.wiki**（按检索词拉丁/CJK 比例选站，必要时切换另一站点）。
- **资料类 `/agent ask`**：启发式识别「维基类」问题；服务端先 **提取检索词**（`WIKI_QUERY: …`）、**搜索** Wiki、注入摘录后，再由模型 **单次** 作答（不会在服务端再跑一轮「先答再 Wiki 补答」）。
- **`[MC_WIKI_SEARCH]` 行**：若模型输出，**第一轮**助手回复**不会**展示给玩家、也**不会**写入历史；服务端对 **Minecraft Wiki** 检索（每条回复最多 3 条查询）后，再请求模型生成**唯一一条**可见的总结并写入对话。该最终回复 **不会** 再触发更多 Wiki 轮次。
- **复合指令**：**「对话完成」**与 **上下文用量** 仅在 **整段复合指令全部执行完毕**（最后一个子任务结束）后提示一次。
- **Carpet 假人控制**：若模型输出 `[CONTROL_BOT] join <标签或名称>` 或 `[CONTROL_BOT] leave …`，服务端会解析并执行 Carpet 的 `player` 子命令（`rejoin` / `kill`），含**频率限制**与同名假人的**跨玩家冲突保护**。目标可通过 `agent_records.json` 的 **tag** 解析，或直接填写 **bot 名**（3–16 位，仅 `[A-Za-z0-9_]`）。
- **展示清洗**：控制指令与 Wiki 指令行不会作为普通回复全文展示；对「思考」流会尽量抑制提示词泄露类内容。

---

## 典型数据目录结构

```
<服务端根目录>/
  macagent.txt              # API 配置
  macagent/
    agent_records.json      # bot_name / tag 记录
```

---

## 编译

```bash
./gradlew build
```

重映射后的模组 JAR 位于 `build/libs/`。CI（`.github/workflows/build.yml`）在 release 事件时执行 `./gradlew build`。

---

## 项目信息

- **模组 id：** `mc-agents`（见 `fabric.mod.json`；部分旧文档可能仍写为 `modid`）。
- **Maven 组与版本：** 见 `gradle.properties`（`maven_group`、`mod_version`）。
- **作者：** [Cherry Fu](mailto:me@dgct.cc)（`fabric.mod.json`）。

---

## 许可证

本项目采用 **MIT 许可证**。完整文本见仓库根目录 [`LICENSE`](LICENSE)（Gradle `jar` 任务会将该文件打入构建产物）。
