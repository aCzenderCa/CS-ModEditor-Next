# CS-ModEditor-Next

## 工程与目标游戏

- **新建工程**：顶部项目菜单 →「新建工程…」，选择 ML/MC、名称、Namespace 与空目录。
- **数据包**：「游戏」页分别配置 CSTI / CSFF 两种主包目录（不预置本机路径）；额外数据包由用户
  自行导入，归属主包可同时勾选 CSTI 与 CSFF，并指定框架（MC/ML，两种格式不通用）。应用组合时
  主包与匹配框架下启用的额外包合并加载，并记住工程 ↔ 数据包组合。
- **元数据**：「元数据」页编辑 `ModInfo.json` / `ModMeta.json` 的常用字段，未知字段保留。
- **多级目录**：文件树按实际子文件夹分层展示；工具栏或右键可新建子文件夹，新对象可指定多级目录。

## Claude Code MCP 自动接入

编辑器启动时会自动完成两件事，用户**不需要写 `.mcp.json`，也不需要执行 `claude mcp add`**：

1. 在 `127.0.0.1:47831` 启动内嵌 MCP HTTP 服务（只监听回环地址）；
2. 通过 `claude mcp add -s user -t http cs-modeditor <endpoint>` 把端点注册到 Claude Code
   （user scope，重启编辑器会刷新注册）。

使用顺序：**先打开编辑器，再启动 Claude Code**。验证：

```bash
claude mcp get cs-modeditor
```

正常时应显示 `Scope: User config` 与 `Status: ✔ Connected`。

说明与可调项：

- MCP 修改会直接进入编辑器的 `EditorSession`：字段视图、撤销栈与脏状态都会同步更新；
  `save_project` 才会写盘。
- 需要本机安装 Claude Code CLI（`claude` 在 PATH 中；Windows 也会探测 `%APPDATA%\npm\claude.cmd`）。
- Claude Code 若已在运行，请在会话中使用 `/mcp` 重新连接，或重启会话。
- 端口可用环境变量 `CSMODE_MCP_PORT` 覆盖；注册会自动使用实际端点。
- 关闭自动注册：设置 `CSMODE_MCP_AUTO_REGISTER=0`；手动注册命令：
  `claude mcp add -s user -t http cs-modeditor http://127.0.0.1:47831/mcp`。
- 当前 MCP 端点绑定**主窗口**的工作区；独立工程窗口不会自动切换。

可用工具：

| 类别 | 工具 |
| --- | --- |
| 加载/新建/概览 | `open_pack`、`open_project`、`create_project`、`project_summary` |
| 浏览/读取 | `list_object_types`、`list_objects`、`get_object`、`get_schema`、`list_localization`、`list_resources` |
| 校验/保存 | `validate_project`、`save_project` |
| 编辑 | `set_field`、`set_reference`、`clear_reference`、`remove_reference`、`create_object`、`create_patch`、`set_localization`、`import_resource` |

## 文档

- [基础信息与规范](基础信息与规范.md)：项目基础信息、架构分层、编码/测试/文档/Git 规范。
- [项目计划](项目计划.md)：数据包格式、ML/MC 加载逻辑、编辑器架构与依赖设计。
- [进度检查点](进度检查点.md)：当前完成状态、验证记录、待办与风险。
- [代码结构说明](代码结构说明.md)：模块与包职责、核心调用链、扩展点、测试位置与常见坑。

This project uses [Gradle](https://gradle.org/).
To build and run the application, use the *Gradle* tool window by clicking the Gradle icon in the right-hand toolbar,
or run it directly from the terminal:

* Run `./gradlew run` to build and run the application.
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`).
This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

This project follows the suggested multi-module setup and consists of the `app` and `utils` subprojects.
The shared build logic was extracted to a convention plugin located in `buildSrc`.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies
and both a build cache and a configuration cache (see `gradle.properties`).