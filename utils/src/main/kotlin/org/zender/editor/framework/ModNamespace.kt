package org.zender.editor.framework

/**
 * MC 命名空间规则：非空，且不能包含 `:`、`@`、`|`（这些字符是数据键分隔符）。
 */
object ModNamespace {

    fun isValid(value: String): Boolean =
        value.isNotBlank() && value.none { it == ':' || it == '@' || it == '|' }
}
