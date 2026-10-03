# mccloudbackup —— Fabric 自动云存档

把单人存档自动备份到 WebDAV 云端，支持浏览历史快照和一键还原。

当前支持 **Minecraft Java Edition 26.3**（Fabric）。

---

## 这个 mod 的一个前提，值得先说清楚

**Minecraft 从 26.1 起不再混淆代码**，Fabric 官方也随之停止维护 Yarn 映射。
这对你意味着两件事：

1. 26.1+ 的 mod 用的是 **Mojang 官方类名**（`net.minecraft.client.Minecraft`、
   `net.minecraft.network.chat.Component`……），而不是网上绝大多数教程里的 Yarn 类名
   （`MinecraftClient`、`Text`……）。照抄旧教程会一路编译报错。
2. **1.21.11 及之前的 mod，不重新编译无法在 26.x 上运行。**

所以"一个 jar 同时兼容 1.20.1 到 26.3"在技术上是不成立的。本工程的做法是：
**核心逻辑跨版本共享，每个游戏版本一个独立的适配模块**，各自产出各自的 jar。
详见文末「如何增加一个新版本」。

---

## 环境要求

| 项目 | 版本 | 说明 |
|---|---|---|
| JDK | **25** | 26.3 要求 Java 25，运行 Gradle 的 JVM 也必须是 25 |
| Gradle | 9.8.0 | 用工程自带的 `gradlew` 即可，无需手动安装 |
| Fabric Loader | 0.19.5+ | 玩家端 |
| Fabric API | 0.161.0+26.3 | 玩家端 |
| Cloth Config | 26.3.159+ | 玩家端，**必需**（配置界面靠它渲染） |
| Mod Menu | 21.0.0+ | 玩家端，可选（只是提供一个设置入口） |

Java 建议用 [Eclipse Temurin](https://adoptium.net/) 或
[Microsoft OpenJDK](https://learn.microsoft.com/java/openjdk/download) 的 25。

---

## 构建

### 构建

```powershell
cd D:\Project\mccloudbackup
.\gradlew build
```

产物：

```
mc-26.3\build\libs\mccloudbackup-1.0.0.jar
```

> 工程里的 Gradle Wrapper 已经生成好了，**不需要你再手动跑 `gradle wrapper`**。
> 首次执行 `.\gradlew` 时它会自动下载 Gradle 9.8.0 到用户目录（约 130 MB），
> 之后就一直走本地缓存。
>
> 如果你系统里已经装了 Gradle，也可以直接用 `gradle build`，效果一样。

### 开发时在 IDE 里跑

```powershell
.\gradlew :mc-26.3:runClient
```

---

## 安装

1. 装好 Fabric Loader 0.19.5+ 对应的 Minecraft 26.3
2. 把下面这些丢进 `.minecraft/mods/`：
   - `mccloudbackup-1.0.0.jar`
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Cloth Config](https://modrinth.com/mod/cloth-config)
   - [Mod Menu](https://modrinth.com/mod/modmenu)

关于最后两个：

- **Cloth Config 是必需的**（`fabric.mod.json` 里写进了 `depends`），设置界面完全由它渲染。
- **Mod Menu 是可选的**。它只是三个入口之一，另外两个（单人游戏界面左下角的按钮、
  游戏内按 <kbd>B</kbd>）都不依赖它。不装 Mod Menu 照样能用全部功能，
  只是模组列表里少一个入口。

---

## 怎么打开界面

三个入口，任选：

| 入口 | 位置 |
|---|---|
| **单人游戏界面** | 左下角的「云存档」按钮 |
| **快捷键** | 默认 <kbd>B</kbd>，可在「选项 → 控制 → 杂项」里改 |
| **Mod Menu** | 模组列表里点 Cloud Backup |

快捷键只在**游戏内**（没有任何界面打开时）生效，避免在别的界面里横插一脚。

---

## 自动备份时的提示

退出世界会触发自动备份，而此时玩家已经在**标题界面**——那里既没有聊天栏，
也没有界面内的进度条。备份就这样隐形地跑着，很容易让人以为没事干就把游戏关了。

所以自动备份进行中，右上角会有一个**实时更新的系统提示**，显示当前阶段和百分比。
备份完成后再弹一条结果提示。

> 如果你在备份没传完的时候直接退出游戏，游戏会**最多等待 20 秒**让上传完成。
> 这期间窗口会短暂无响应，属于正常现象。备份线程是守护线程，所以即使等超时了，
> 游戏也照样能正常退出，只是那一次备份会不完整。

---

## 配置

配置文件在 `.minecraft/config/mccloudbackup.json`，也可以在游戏里通过
**Mod Menu → Cloud Backup → 设置** 打开图形界面改。

### WebDAV

| 字段 | 说明 |
|---|---|
| `url` | WebDAV 地址。坚果云是 `https://dav.jianguoyun.com/dav/` |
| `username` | 账号 |
| `password` | **应用密码，不是登录密码**（坚果云必须用「添加应用密码」生成的那个） |
| `remoteRoot` | 云端根目录，默认 `mccloudbackup`，会在这个目录下按世界名建子目录 |
| `allowInsecureTls` | 自签证书的私有 WebDAV 才需要打开。**打开后不校验服务器证书** |
| `groupByModpack` | 默认 `true`。在云端按整合包多分一层文件夹，见下文「为什么」 |
| `modpackName` | 整合包名。**留空即自动识别**，识别不准时可以手填（支持中文） |

> ⚠️ **密码是明文存在 JSON 里的。** 这是有意为之的取舍（详见「已知限制」）。
> 请务必使用应用专用密码，不要用你的主账号密码。

各家服务的 `url`：

| 服务 | URL |
|---|---|
| 坚果云 | `https://dav.jianguoyun.com/dav/` |
| Nextcloud | `https://你的域名/remote.php/dav/files/用户名/` |
| 群晖 WebDAV Server | `https://你的域名:5006/`（需在套件里开启 WebDAV） |
| InfiniCLOUD | `https://webdav.infini-cloud.net/` |

### 触发时机

| 字段 | 默认 | 说明 |
|---|---|---|
| `onWorldExit` | `true` | 退出世界时备份 |
| `onAutosave` | `false` | 每次自动保存后备份。默认关，因为自动保存很频繁 |
| `autosaveMinIntervalMinutes` | `10` | 上一项开启时的最小间隔 |
| `onInterval` | `false` | 按固定间隔备份 |
| `intervalMinutes` | `30` | 间隔分钟数 |

### 保留策略

`maxSnapshotsPerWorld`（默认 `10`）：每个世界在云端保留最近多少份快照，
超出的会自动删除最旧的。

### 高级

| 字段 | 默认 | 说明 |
|---|---|---|
| `compressionLevel` | `6` | zip 压缩级别 0–9。`0` 最快但文件最大 |
| `timeoutSeconds` | `60` | 网络超时 |
| `skipUnchanged` | `true` | 内容与上份快照相同时跳过上传 |
| `excludePatterns` | `["session.lock", "*.lock"]` | 相对世界目录的排除规则，支持 `*` `?` |

---

## 云端目录结构

```
<remoteRoot>/
  <整合包名>/
    <世界目录名>/
      20261001-143012-a1b2c3d4.zip
      20261001-182204-9f8e7d6c.zip
      manifest.json
```

文件名是 `时间戳-内容哈希前8位.zip`。`manifest.json` 记录了这个世界所有快照的
时间、大小和内容哈希。

写入顺序是**先传 zip、最后传 manifest**，这样清单里出现的每一条快照都保证已经
在云端了，不会出现"清单说有、实际下载不到"的幽灵条目。

### 为什么要多一层整合包文件夹

因为不同整合包的存档目录**经常重名**——新建世界默认就叫 `New World`，
几个整合包玩下来云端就会互相覆盖。更麻烦的是整合包的 mod 列表不同，
把 A 整合包的存档还原进 B 整合包，缺失的方块和物品会导致存档直接损坏。

所以 mod 会在上传时自动识别当前整合包并在云端分一层文件夹。识别不出来也没关系，
它会依次退回到「用文件夹名」和 `default`，并且**你随时可以在设置里手动覆盖**。

识别方式按顺序尝试这些启动器的实例元数据：

| 启动器 | 文件 | 取的字段 |
|---|---|---|
| Prism / MultiMC / PolyMC | `instance.cfg` | `name=` |
| Modrinth App | `profile.json` | `name` |
| CurseForge | `minecraftinstance.json` | `name` |

会在游戏目录**及其往上最多三层**里找这些文件——因为不同启动器把游戏目录放在
实例目录下的位置不一样。

> 这套识别逻辑只对上面这些启动器验证过思路，**没有在真实实例上测过**。
> 如果你用的是原版启动器或别的启动器，多半会退回到文件夹名或 `default`。
> 游戏日志里会打印实际用了哪个名字，设置界面里也会显示。

如果想关掉分层（回到旧布局），把「按整合包分文件夹」关掉即可。

---

## 还原功能的行为

还原是唯一会动你本地文件的操作，所以流程刻意做得保守：

1. 从云端下载快照，**先校验不是空包**才继续
2. 把当前的世界目录整个改名为 `<世界名>.mccb-backup-<时间戳>`
3. 解压快照到原来的位置
4. 如果解压失败，自动把第 2 步挪走的目录挪回来

也就是说，**即使还原中途失败，你原来的世界也不会丢**，最坏情况是目录名多了个后缀，
手动改回去即可。还原成功时聊天栏也会提示备份目录的名字。

还原正在游玩中的世界会被拒绝——那时候文件被游戏占着，替换会得到半个旧世界加半个新世界。

---

## 工作原理

```
shared/java/dev/mccb/core/      ← 纯 Java，零 Minecraft 依赖
    platform/GamePlatform        ← 唯一与游戏交互的边界（接口）
    webdav/WebDavClient          ← 手写的 WebDAV 客户端，只用 JDK 自带 HttpClient
    archive/WorldArchiver        ← 打包 zip + 同时算内容哈希
    backup/BackupService         ← 备份流程编排
    backup/RestoreService        ← 还原流程编排

mc-26.3/src/client/.../client/   ← 26.3 专属适配
    platform/FabricGamePlatform  ← 唯一 import Minecraft 的文件
    BackupEvents                 ← 事件挂钩
    gui/                         ← 界面
```

两个值得一提的设计：

**哈希只吃内容和路径，不吃时间戳。** 自动保存会把 `level.dat` 的修改时间刷新，
如果哈希把时间算进去，"内容没变就跳过上传"就永远失效了。

**打包和哈希是同一次遍历完成的。** 反正压缩本来就要把文件读一遍，顺手喂给
SHA-256 几乎不花额外时间，省掉"为了算哈希再读一遍整个世界"。

**触发时机用 Fabric API 事件，不用 Mixin。** 本工程不含任何 Mixin，少一层注入就
少一类"游戏一更新就崩"的故障。

---

## 已知限制

- **密码明文存储。** 正确的做法是接系统钥匙串（Windows Credential Manager /
  macOS Keychain / libsecret），但那需要为三个平台各写一套 JNI 或调外部命令，
  工作量和脆弱度都不低。当前版本的取舍是：明文 + README 大字警告 + 强制应用专用密码。
  如果你需要，这是可以后续补上的。
- **没有增量备份。** 每次都是全量快照。大世界（几个 GB）上传会比较慢。
  增量 diff 容易出错，v1 刻意不做。
- **快照列表是分页的，不是滚动列表。** 见「待办」。
- **仅在单人存档中触发。** 多人服务器上是没有作用的。

---

## 待办 / 后续可做

1. **把 `shared/` 提升为独立的 Gradle 模块**，并用 `options.release = 17` 强制它
   保持 Java 17 兼容（现在只是文档约定，靠自觉）。这样将来复用给 1.20.1 更可靠。
2. **快照列表改成可滚动列表**，替换当前的按钮分页。
3. **还原界面加二次确认弹窗**，避免误点。
4. 可选的**客户端加密**（上传前用密码加密 zip）。
5. 支持 **S3 兼容对象存储**（Cloudflare R2 / 阿里云 OSS / 腾讯云 COS）。
6. 加一个 `icon.png`（现在没放图标，Fabric 会记为一条无害的提示）。

---

## 构建踩坑记录

这两条都是实际踩到的，记下来免得重复浪费时间。

### Loom 1.18 移除了 `modImplementation` 等配置

这是本工程第一次构建失败的原因：

```
Could not find method modImplementation() for arguments
[me.shedaniel.cloth:cloth-config-fabric:26.3.159]
```

Loom 1.18 起，`modImplementation` / `modCompileOnly` / `modLocalRuntime` / `modRuntimeOnly`
这些 Loom 专属配置**全部移除**，改用 Gradle 原生的（Loom 会自动识别 Fabric mod）：

| 旧写法（≤ Loom 1.17） | 新写法（Loom 1.18+） |
|---|---|
| `modImplementation` | `implementation` |
| `modCompileOnly` | `compileOnly` |
| `modLocalRuntime` | `localRuntime` |
| `modRuntimeOnly` | `runtimeOnly` |

所以你会看到官方模板里 fabric-api 用的就是裸的 `implementation`——不是它写漏了。
网上绝大多数教程和旧工程还在用 `modImplementation`，照抄会直接构建失败。

### 26.1+ 的工程里没有 `mappings` 依赖，这是正常的

不要按旧教程加 `mappings loom.officialMojangMappings()` 或 Yarn。
26.1 起游戏不再混淆，Mojang 的版本清单里已经没有 `client_mappings.json` 了，
加上反而会报 `Failed to find official mojang mappings`（见 fabric-loom#1585）。

### 26.3 上另外三个实测踩到的坑

**1. Toast 必须回客户端线程。**
`ToastManager` 会碰字体和渲染状态，从后台线程调用会抛
`IllegalStateException: Rendersystem called from wrong thread`，而且**是静默失败**——
只在日志里留一行 warn，玩家什么都看不到。任何从 worker 线程发的提示都要先
`Minecraft.getInstance().execute(...)`。

**2. 按键相关的 API 全改名了。**

| 旧 | 26.3 |
|---|---|
| `KeyBindingHelper`（`keybinding` 包） | `KeyMappingHelper`（`keymapping` 包） |
| `KeyMapping(..., String category)` | `KeyMapping(..., KeyMapping.Category)` |
| `InputConstants.Type.KEYSYM` | `InputConstants.Type.KEYBOARD` |
| `GLFW.GLFW_KEY_B` | `InputConstants.KEY_B`（LWJGL 不在编译类路径上） |

**3. `ScreenEvents.AFTER_INIT` 会重复触发，而且旧控件不一定被清掉。**
窗口尺寸变化（最小化再最大化）会再跑一次 `init()`，此时 `clearWidgets()`
**有时清有时不清**。如果每次都往界面里加控件，就会在同一个位置叠出好几颗，
露出的边角看上去像"多出来一节按钮"。
往原版界面加控件时，务必先摘掉自己上一轮加的那个。

---

## 如何增加一个新版本（例如 1.21.1 或 1.20.1）

1. 复制 `mc-26.3/` 为 `mc-1.21.1/`
2. 在 `settings.gradle` 里加 `include 'mc-1.21.1'`
3. 改这个模块的 `gradle.properties` 依赖版本，并在 `build.gradle` 里
   设置对应的 `options.release`（1.20.1 用 17，1.20.5–1.21.x 用 21）
4. **1.21.11 及之前需要加回 `mappings` 依赖**（`loom.officialMojangMappings()`
   或 Yarn），因为那些版本仍然是混淆的
5. 重写 `platform/FabricGamePlatform`——把 Mojang 类名换回该版本对应的名字
6. `shared/` 目录**一行都不用改**

需要改的只有第 5 步那一个文件。这正是把 `GamePlatform` 接口收得这么窄的原因。

---

## 验证状态

### 已在真实游戏中实测通过

跑过的环境：JDK 25.0.4.1 + Gradle 9.8.0 + Fabric Loom 1.18.2 + Minecraft 26.3，
对着一个**真实的 WebDAV 服务器**（非局域网、非模拟）。

| 项目 | 结论 |
|---|---|
| mod 被 Fabric Loader 加载 | ✅ 在 56 个 mod 的列表里 |
| 配置自动生成、界面读写 | ✅ |
| 世界目录解析 | ✅ 修正了 `LevelResource.ROOT` 带 `.` 的问题 |
| 退出世界触发备份 | ✅ 多次 |
| 内容去重跳过上传 | ✅ |
| 真实 WebDAV 上传 / 列目录 / 删旧快照 | ✅ |
| 中文世界名的 URL 编码 | ✅ |
| **一键还原** | ✅ 含回退备份生成与回滚路径 |
| 关机保护（等未完成备份） | ✅ 实测触发过 |
| 单人游戏界面入口 / 快捷键 / 系统提示 | ✅ |
| 我们自己的代码零异常 | ✅ |

另外 **42 项核心层端到端测试全部通过**（见 `tools/smoke/`）。那个测试在内存里起了一个
最小 WebDAV 服务器，把「打包 → 上传 → 去重 → 浏览 → 还原 → 保留策略」整条链路跑一遍，
还覆盖了 Zip Slip 防护、401 错误翻译、网络抖动重试、以及回退备份的位置约束。

### 已知未覆盖的部分

- **多人服务器**：本 mod 是纯客户端、只处理单人存档，在服务器上不生效（设计如此）。
- **其他 WebDAV 服务商**：只在实测的那一台上验证过。各家对 `PROPFIND` / `MKCOL`
  的返回码不统一（代码里已按"多返回码都当成功"处理），但换服务商仍建议先用
  设置界面里的路径试一次。
- **超大世界**：实测过的存档在 12 MB 量级，几个 GB 的世界没有验证过
  （全量上传，会比较慢）。

想自己复现开发环境：

```powershell
.\gradlew :mc-26.3:runClient
```
