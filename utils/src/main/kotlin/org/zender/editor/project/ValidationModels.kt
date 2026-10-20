package org.zender.editor.project

/**
 * 校验级别。
 *
 * - [ERROR]：保存/导出前必须修复（确定性错误）；
 * - [WARNING]：可能依赖其他 Mod、数据包版本或运行时行为，需要人工确认；
 * - [INFO]：提示信息，不阻塞保存。
 */
enum class ValidationSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * 一条工程校验结果。
 *
 * [code] 是稳定标识（供 UI 分组、测试断言），[path] 是工程内相对路径或字段路径。
 */
data class ValidationIssue(
    val severity: ValidationSeverity,
    val code: String,
    val message: String,
    val path: String? = null,
) {
    val isError: Boolean get() = severity == ValidationSeverity.ERROR

    val isWarning: Boolean get() = severity == ValidationSeverity.WARNING
}
