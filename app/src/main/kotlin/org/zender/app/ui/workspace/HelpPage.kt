package org.zender.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 内置帮助页：功能入口、数据包模型与快捷操作。
 */
@Composable
fun HelpPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("帮助", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "CS-ModEditor-Next 是用于编辑 Card Survival（ML / MC）Mod 工程的桌面编辑器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HelpSection(
            "快速开始",
            listOf(
                "1. 顶部项目菜单 →「新建工程…」，或「打开工程」选择已有 Mod 目录。",
                "2. 到「游戏」页配置 CSTI / CSFF 主包目录（首次需要选择本机数据包目录）。",
                "3. 在「游戏」页导入额外数据包，选择框架（MC/ML）与归属主包。",
                "4. 点击主包的「应用组合」，编辑器会加载主包 + 已启用的额外包。",
                "5. 左侧文件树选择对象开始编辑；Ctrl+S 保存工程。",
            ),
        )

        HelpSection(
            "数据包",
            listOf(
                "主包：CSTI 与 CSFF 两款游戏的数据包，目录由用户自行选择，编辑器不预置路径。",
                "额外包：用户自行导入，可同时归属 CSTI 和 CSFF；必须指定框架 MC 或 ML。",
                "MC 额外包使用标准数据包格式（PackInfo.json / TypeStruct / BaseJson / Objects 等）。",
                "ML 额外包使用 ModCore-ModEditor 的 ScriptableObjectTypeJsonData 等目录；" +
                    "其中 Base*.json 只作为 BaseJson 模板，不生成对象。",
                "合并规则：同名 Schema、对象、本地化与资源由额外包覆盖主包；工程会记住所用组合。",
            ),
        )

        HelpSection(
            "文件树与对象",
            listOf(
                "类型目录下按真实子文件夹分层显示，支持任意多级目录。",
                "工具栏按钮或右键类型/文件夹：新建对象、新建子文件夹。",
                "新建对象对话框支持搜索类型，并可填写多级子目录。",
                "右键对象 →「删除对象…」；对象和补丁节点也支持选中后按 Delete 键删除，保存工程后对应 JSON 文件会从磁盘删除。",
                "右键补丁节点 →「删除补丁…」可删除 GameSourceModify / DataObjectModify。",
                "右键对象可新建补丁：带 UniqueID 的对象用 GameSourceModify；MC 的无 GUID 对象还可用 DataObjectModify（ML 不支持）。",
                "也可右键文件树中的 GameSourceModify / DataObjectModify 目录创建补丁；对象与补丁节点可以拖动到对应文件夹改变目录。",
                "空补丁在字段视图点击「添加 Warp 字段」选择引用字段；与游戏加载器一致，只写入 WarpData 与 WarpType 配对，不写字段本体，普通值字段不在此处创建。",
                "「数据包」勾选项显示数据包中只读对象，可复制到工程。",
                "搜索框可按类型、对象名、补丁目标过滤；「只看补丁」只显示修改项。",
                "文件页的两条竖向分隔线可以左右拖动，调整文件树、编辑区与检查器的宽度；宽度会记住。",
            ),
        )

        HelpSection(
            "编辑",
            listOf(
                "字段视图按 TypeStruct / BaseJson / Doc 生成，支持引用选择器与 WarpType 显示。",
                "检查器中的警告/错误可以点击，自动打开对应对象、清空字段过滤、选中问题字段并滚动到该行；" +
                    "WarpData/WarpType 路径会定位回引用字段。",
                "字段搜索只匹配顶级字段，不会递归匹配子字段；顶级字段原有的展开状态不受影响。",
                "字段工具栏的「默认值」是三态开关：对勾显示全部；横线只隐藏顶级默认字段，子字段全部保留；空框隐藏所有层级的默认字段。",
                "字段星标可收藏到多个收藏夹；收藏夹在「设置」中新建、删除，字段在所有收藏夹中唯一。",
                "字段工具栏的「收藏夹」菜单可多选 1 个或多个收藏夹，过滤后只显示这些收藏夹中的字段及其子字段。",
                "原始视图可直接编辑 JSON，合法后自动同步字段视图；差异视图对比打开时与当前内容。",
                "引用字段可点击选择对象，或在星图把节点拖到右侧详情面板写入引用。",
                "对象、图片与音频引用的 WarpData/WarpType 由引用编辑器统一管理，不会作为普通字段重复显示。",
                "ML ModLoader 扩展字段（如 MatchTagWarpData、ModLoaderSpecialOverwrite、" +
                    "BlueprintCardDataCardTabGroup、VisibleGameStatStatListTab 等）会在 JSON 中存在时显示并可直接编辑。",
                "DurabilityStat 的 Active 直接显示为开关按钮；未激活时整体视作默认值且不可展开，激活后可展开编辑明细。",
                "图片/音频字段没有设置引用时视作默认值，不会计入修改。",
                "所有修改进入命令栈，可撤销/重做；保存前会同步所有已打开文档。",
            ),
        )

        HelpSection(
            "星图",
            listOf(
                "排列模式：力导向、分组、分层，可随时切换。",
                "「文件夹分块」是独立开关：开启后按类型 + 子文件夹分块，块内仍使用当前排列模式。",
                "节点按来源着色：工程对象、工程修改、本体目标、资源、未解析。",
                "拖拽节点到右侧详情面板可写入最近聚焦的引用字段。",
                "滚轮缩放，空白处拖动平移；画布内容不会绘制到工具栏或其他面板上。",
            ),
        )

        HelpSection(
            "元数据 / 本地化 / 资源 / 导出",
            listOf(
                "「元数据」页编辑 ModInfo.json / ModMeta.json 常用字段；未知字段与顺序会保留。",
                "「本地化」页统一编辑 Key 的中英文；保存时按 ML/MC 布局写回 CSV。",
                "「资源」页查看工程与数据包图片/音频；支持导入、替换与删除工程资源记录。",
                "「导出」页在完整校验通过后把工程写入新目录；当前不做 ML ↔ MC 转换。",
            ),
        )

        SectionTitle("快捷键")
        KeyValue("保存工程", "Ctrl + S")
        KeyValue("撤销 / 重做", "Ctrl + Z / Ctrl + Y")
        KeyValue("重做（备用）", "Ctrl + Shift + Z")
        KeyValue("关闭文件 Tab", "鼠标中键点击标签")
        KeyValue("删除对象 / 补丁", "Delete（先点击文件树节点）")

        HelpSection(
            "Claude Code / MCP",
            listOf(
                "编辑器启动后会在 127.0.0.1:47831 启动内嵌 MCP 服务，并自动注册到 Claude Code。",
                "先打开编辑器再启动 Claude Code；已运行的会话可用 /mcp 重连。",
                "MCP 的修改直接进入当前编辑会话，save_project 才会写盘。",
                "端口可用环境变量 CSMODE_MCP_PORT 覆盖；CSMODE_MCP_AUTO_REGISTER=0 关闭自动注册。",
            ),
        )

        Text(
            text = "更多设计说明见仓库根目录的 README、项目计划与代码结构说明。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HelpSection(title: String, lines: List<String>) {
    SectionTitle(title)
    lines.forEach { line ->
        Text(
            text = "• $line",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
