package org.zender.editor.model

/**
 * 对象/资源条目的来源。对象选择器依赖该标记区分游戏本体与 Mod 内容。
 */
enum class EntryOrigin {
    /** 当前打开的数据包（游戏本体）。 */
    BASE_PACK,

    /** 当前编辑的 Mod 工程。 */
    PROJECT,

    /** 其他已加载的 Mod。 */
    OTHER_MOD,
}
