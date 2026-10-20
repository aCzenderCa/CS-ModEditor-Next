package org.zender.editor.localization

/**
 * 本地化文件布局。
 */
enum class LocalizationFileFormat {
    /** ML：`LocalizationKey,English,中文`。 */
    THREE_COLUMN,

    /** MC：`Key,当前语言文本`，取每行最后一列。 */
    KEY_VALUE,
}

/**
 * Mod 工程中的一个本地化文件。
 *
 * [language] 为 null 表示未识别的语言文件，内容原样保留（写入时不做语言映射）。
 */
data class LocalizationFileSpec(
    val relativePath: String,
    val language: LocalizationLanguage? = null,
    val format: LocalizationFileFormat,
) {
    init {
        require(relativePath.isNotBlank()) { "本地化文件路径不能为空" }
    }
}
