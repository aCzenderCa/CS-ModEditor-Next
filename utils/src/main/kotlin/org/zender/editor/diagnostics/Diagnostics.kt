package org.zender.editor.diagnostics

/**
 * 数据层诊断级别。读取数据包时尽量继续加载可用部分，把问题集中返回给调用方。
 */
enum class DiagnosticSeverity {
    WARNING,
    ERROR,
}

/**
 * 一条读取/解析诊断。`path` 使用相对数据包根目录的路径，便于直接展示与定位。
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val path: String? = null,
) {
    val isError: Boolean get() = severity == DiagnosticSeverity.ERROR

    override fun toString(): String =
        buildString {
            append(severity.name)
            if (path != null) append('[').append(path).append(']')
            append(": ").append(message)
        }
}

/**
 * 诊断收集器。读取器把可继续的问题写入这里，阶段结束时一次性交给调用方。
 */
class DiagnosticCollector {

    private val items = mutableListOf<Diagnostic>()

    fun warning(message: String, path: String? = null) {
        items += Diagnostic(DiagnosticSeverity.WARNING, message, path)
    }

    fun error(message: String, path: String? = null) {
        items += Diagnostic(DiagnosticSeverity.ERROR, message, path)
    }

    fun add(diagnostic: Diagnostic) {
        items += diagnostic
    }

    fun addAll(diagnostics: Iterable<Diagnostic>) {
        items += diagnostics
    }

    fun hasErrors(): Boolean = items.any { it.isError }

    fun build(): List<Diagnostic> = items.toList()
}
