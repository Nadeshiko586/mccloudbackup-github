# 核心层冒烟测试

核心层（`shared/java/dev/mccb/core/`）是纯 Java、零 Minecraft 依赖的，所以可以**完全脱离游戏**
验证。这个测试在内存里实现了一个最小的 WebDAV 服务器，然后把整条链路真跑一遍。

它验证的东西：

| 分组 | 覆盖内容 |
|---|---|
| 名字净化 | 中文保留、空格转连字符、路径分隔符、`.`/`..` 拦截、超长截断、null 安全 |
| 整合包识别 | Prism `instance.cfg`、Modrinth `profile.json`、CurseForge `minecraftinstance.json`、原版布局兜底、坏 JSON 不崩 |
| 打包与哈希 | `session.lock` 被排除、改动时间戳不影响哈希、改内容哈希才变、Zip Slip 被拦截 |
| 端到端 | 首次备份、**整合包分组生效**、内容未变跳过上传、内容变化重新上传、列出云端世界与快照、删除本地后从云端还原、保留策略真删云端文件、401 错误翻译 |

## 怎么跑

```powershell
# 1. 先构建出 jar
cd D:\Project\mccloudbackup
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.4.1"
gradle build

# 2. 编译测试（需要 gson，因为 mod 的 jar 本身不打包它——运行时由 Minecraft 提供）
$jar  = "mc-26.3\build\libs\mccloudbackup-1.0.0.jar"
$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1).FullName

javac -encoding UTF-8 -cp $jar -d tools\smoke\classes tools\smoke\CoreSmokeTest.java

# 3. 运行
$env:JAVA_TOOL_OPTIONS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
java -cp "$jar;tools\smoke\classes;$gson" CoreSmokeTest
```

全部通过时退出码为 0，有失败时退出码为 1，可以直接接进 CI。

> `tools/` 目录不在任何 Gradle source set 里，所以不会被正常构建编译到，
> 也不会被打进发布的 jar。

## 这个测试验证不到什么

它是**纯核心层**测试，所以下面这些必须进游戏才能验证：

- mod 能否被 Fabric Loader 正常加载（入口点、`fabric.mod.json`）
- 界面能不能正常显示和点击（`MainScreen` / `ConfigScreen` / `RestoreScreen`）
- 事件挂钩是否真的在正确的时机触发（`AFTER_SAVE` / `SERVER_STOPPED` / tick）
- 世界目录解析是否正确（`getWorldPath(LevelResource.ROOT)` 拿到的路径）
- 真实 WebDAV 服务商的兼容性（坚果云的 `PROPFIND` / `MKCOL` 返回码、TLS 等）

这些需要 `gradle :mc-26.3:runClient` 或装进真实整合包实测。
