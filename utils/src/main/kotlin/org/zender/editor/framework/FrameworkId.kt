package org.zender.editor.framework

/**
 * Mod 运行时框架。一个 Mod 工程在同一时刻只对应一种框架。
 */
enum class FrameworkId {
    /** ModLoader：根目录类型文件夹、ScriptableObject、Localization、GameSourceModify。 */
    ML,

    /** ModCore：Data 目录、命名空间数据键、DataObjectModify、按语言本地化。 */
    MC,
}

/**
 * 框架能力标记。Validator 与 UI 依此决定命令、目录和提示是否可用。
 */
enum class FrameworkCapability {
    UNIQUE_ID_OBJECTS,
    SCRIPTABLE_OBJECTS,
    GAME_SOURCE_MODIFY,
    DATA_OBJECT_MODIFY,

    ASSET_BUNDLE,
    MOD_ARCH,

    IMAGE_RESOURCES,
    AUDIO_RESOURCES,

    LOCALIZATION_THREE_COLUMN,
    LOCALIZATION_LAST_COLUMN,

    /** MC：`$override` 整体覆写。 */
    PATCH_OVERRIDE,

    /** MC：`$matchTargets` 追加补丁目标。 */
    PATCH_MATCH_TARGETS,

    /** ML：`MatchTagWarpData` 多 Tag 取交集，命中后源目标不应用补丁。 */
    MATCH_TAG_INTERSECTION,

    /** MC：`MatchTagWarpData` 逐 Tag 取并集，源目标也会应用补丁。 */
    MATCH_TAG_UNION,

    /** ML：读取 `.jsonnet` 数据文件。 */
    JSONNET,
}
