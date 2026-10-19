package org.zender.editor.document

/**
 * 原始文件视图的文档模型。
 *
 * 与 [JsonDocument] 的分工：
 * - [text] 是当前编辑框里的完整原文，可能暂时非法；
 * - [document] 是**最后一份成功解析**的文档，非法输入期间保持不变，供字段视图继续显示；
 * - [originalText] 是打开/上次保存时的文件内容，用于“还原”和差异比较；
 * - [error] 携带解析失败的位置（行、列、偏移与片段）；
 * - [currentFormat] 记录字段视图回写原文时使用的格式。
 */
class RawJsonDocument private constructor(
    val originalText: String,
    val text: String,
    val document: JsonDocument?,
    val error: JsonParseException?,
    val issues: List<JsonIssue>,
    val currentFormat: JsonFormat,
    private val duplicateKeyPolicy: DuplicateKeyPolicy,
) {

    val isValid: Boolean
        get() = document != null && error == null

    val hasUnsavedTextChanges: Boolean
        get() = text != originalText

    /** 当前原文是否成功解析；解析成功时 [document] 就是它的语义快照。 */
    val isInSync: Boolean
        get() = document != null && error == null

    /**
     * 更新原文。解析失败时保留上一份有效 [document]，只更新 [text] 与 [error]。
     */
    fun withText(newText: String): RawJsonDocument {
        if (newText == text) return this
        val result = JsonDocument.parse(newText, duplicateKeyPolicy)
        return if (result.isSuccess) {
            RawJsonDocument(
                originalText = originalText,
                text = newText,
                document = result.document,
                error = null,
                issues = result.issues,
                currentFormat = currentFormat,
                duplicateKeyPolicy = duplicateKeyPolicy,
            )
        } else {
            RawJsonDocument(
                originalText = originalText,
                text = newText,
                document = document,
                error = result.error,
                issues = result.issues,
                currentFormat = currentFormat,
                duplicateKeyPolicy = duplicateKeyPolicy,
            )
        }
    }

    /**
     * 用字段视图产生的文档回写原文。[originalText] 保持不变。
     */
    fun withDocument(document: JsonDocument, format: JsonFormat = this.currentFormat): RawJsonDocument {
        val newText = document.toJsonText(format)
        return RawJsonDocument(
            originalText = originalText,
            text = newText,
            document = document,
            error = null,
            issues = emptyList(),
            currentFormat = format,
            duplicateKeyPolicy = duplicateKeyPolicy,
        )
    }

    /** 格式化当前有效文档；原文非法时抛出 [JsonParseException]。 */
    fun format(target: JsonFormat = JsonFormat.Pretty()): RawJsonDocument {
        val current = requireDocument()
        val newText = current.toJsonText(target)
        return RawJsonDocument(
            originalText = originalText,
            text = newText,
            document = current,
            error = null,
            issues = issues,
            currentFormat = target,
            duplicateKeyPolicy = duplicateKeyPolicy,
        )
    }

    fun compact(): RawJsonDocument = format(JsonFormat.Compact)

    /** 还原为打开时的原文。 */
    fun revert(): RawJsonDocument = withText(originalText).let { reverted ->
        if (reverted.document == null && document != null) {
            RawJsonDocument(
                originalText = originalText,
                text = originalText,
                document = document,
                error = reverted.error,
                issues = reverted.issues,
                currentFormat = currentFormat,
                duplicateKeyPolicy = duplicateKeyPolicy,
            )
        } else {
            reverted
        }
    }

    fun requireDocument(): JsonDocument =
        document ?: throw error ?: IllegalStateException("原始 JSON 文档尚未成功解析")

    companion object {

        fun parse(
            text: String,
            duplicateKeyPolicy: DuplicateKeyPolicy = DuplicateKeyPolicy.PRESERVE,
            format: JsonFormat = JsonFormat.Compact,
        ): RawJsonDocument {
            val result = JsonDocument.parse(text, duplicateKeyPolicy)
            return RawJsonDocument(
                originalText = text,
                text = text,
                document = result.document,
                error = result.error,
                issues = result.issues,
                currentFormat = format,
                duplicateKeyPolicy = duplicateKeyPolicy,
            )
        }

        /**
         * 用一份已经有效的文档创建原始文本视图。
         */
        fun fromDocument(
            document: JsonDocument,
            format: JsonFormat = JsonFormat.Pretty(),
        ): RawJsonDocument {
            val text = document.toJsonText(format)
            return RawJsonDocument(
                originalText = text,
                text = text,
                document = document,
                error = null,
                issues = emptyList(),
                currentFormat = format,
                duplicateKeyPolicy = DuplicateKeyPolicy.PRESERVE,
            )
        }
    }
}
