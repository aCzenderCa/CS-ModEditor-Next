package org.zender.editor.localization

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class CsvParseException(
    message: String,
    val offset: Int,
    val line: Int,
    val column: Int,
) : IllegalArgumentException("$message（第 $line 行，第 $column 列）")

data class CsvParseResult(
    val rows: List<List<String>>,
    val error: CsvParseException? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * 数据包与 Mod 使用的 CSV 解析器。
 *
 * 支持：
 * - 逗号分隔、双引号字段；
 * - 字段内逗号、换行与 `""` 转义的双引号；
 * - `\n` / `\r\n` / `\r` 行尾；
 * - 空列与只含空白的列（原样保留，不裁剪内容）。
 *
 * 不支持：可配置分隔符之外的方言、转义字符前缀（Excel 风格 `\"`）。
 */
object CsvParser {

    fun parse(
        text: String,
        separator: Char = ',',
        quote: Char = '"',
    ): CsvParseResult {
        if (text.isEmpty()) return CsvParseResult(emptyList())

        val rows = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var index = 0
        var line = 1
        var column = 1
        var endedWithRowTerminator = false

        fun advance(): Char {
            val ch = text[index++]
            if (ch == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            return ch
        }

        fun finishField() {
            fields += field.toString()
            field.clear()
        }

        fun finishRow() {
            finishField()
            rows += fields.toList()
            fields.clear()
        }

        fun consumeLineTerminator() {
            val first = advance()
            if (first == '\r' && index < text.length && text[index] == '\n') {
                advance()
            }
        }

        try {
            while (index < text.length) {
                val ch = text[index]
                if (ch == quote) {
                    advance()
                    var closed = false
                    while (index < text.length) {
                        val inner = advance()
                        if (inner == quote) {
                            if (index < text.length && text[index] == quote) {
                                advance()
                                field.append(quote)
                            } else {
                                closed = true
                                break
                            }
                        } else {
                            field.append(inner)
                        }
                    }
                    if (!closed) {
                        throw CsvParseException("引号字段未闭合", index, line, column)
                    }
                    if (index < text.length) {
                        when (val next = text[index]) {
                            separator -> {
                                advance()
                                finishField()
                                endedWithRowTerminator = false
                            }

                            '\n', '\r' -> {
                                consumeLineTerminator()
                                finishRow()
                                endedWithRowTerminator = true
                            }

                            else -> throw CsvParseException(
                                "引号闭合后存在多余字符：'$next'",
                                index,
                                line,
                                column,
                            )
                        }
                    } else {
                        endedWithRowTerminator = false
                    }
                } else {
                    when (ch) {
                        separator -> {
                            advance()
                            finishField()
                            endedWithRowTerminator = false
                        }

                        '\n', '\r' -> {
                            consumeLineTerminator()
                            finishRow()
                            endedWithRowTerminator = true
                        }

                        else -> {
                            field.append(advance())
                            endedWithRowTerminator = false
                        }
                    }
                }
            }
            if (!endedWithRowTerminator) {
                finishRow()
            }
            return CsvParseResult(rows.toList())
        } catch (e: CsvParseException) {
            return CsvParseResult(rows.toList(), e)
        }
    }

}

/**
 * CSV 字节解码。
 *
 * 数据包与 Mod 的本地化通常为 UTF-8，本机数据包存在重复 BOM 的情况，
 * 因此这里集中处理 UTF-8/UTF-16 BOM 并移除所有前导 BOM。
 */
object CsvEncoding {

    fun decode(bytes: ByteArray, fallback: Charset = StandardCharsets.UTF_8): String {
        val text = when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)

            else -> String(bytes, fallback)
        }
        return text.trimStart('﻿')
    }
}
