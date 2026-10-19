package org.zender.editor.log

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class LoggerTest {

    private class CapturingSink : LogSink {
        val records = mutableListOf<LogRecord>()

        override fun emit(record: LogRecord) {
            records += record
        }
    }

    private val fixedClock = Clock.fixed(Instant.parse("2026-10-19T08:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `低于最小级别的日志被过滤`() {
        val sink = CapturingSink()
        val logger = SimpleLogger("test", LogLevel.INFO, fixedClock, sink)

        logger.trace("trace")
        logger.debug("debug")

        assertTrue(logger.isEnabled(LogLevel.INFO))
        assertFalse(logger.isEnabled(LogLevel.DEBUG))
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun `日志记录携带时间、级别与名称`() {
        val sink = CapturingSink()
        val logger = SimpleLogger("card-loader", LogLevel.DEBUG, fixedClock, sink)

        logger.debug("开始解析")
        logger.error("解析失败", IllegalStateException("boom"))

        assertEquals(2, sink.records.size)
        assertEquals(Instant.parse("2026-10-19T08:30:00Z"), sink.records[0].timestamp)
        assertEquals(LogLevel.DEBUG, sink.records[0].level)
        assertEquals("card-loader", sink.records[0].loggerName)
        assertEquals("开始解析", sink.records[0].message)
        assertEquals(LogLevel.ERROR, sink.records[1].level)
        assertEquals("boom", sink.records[1].throwable?.message)
    }

    @Test
    fun `工厂按名称复用同一个 logger`() {
        val factory = ConsoleLoggerFactory(minLevel = LogLevel.WARN, sink = CapturingSink(), clock = fixedClock)

        assertTrue(factory.getLogger("pack") === factory.getLogger("pack"))
        assertFalse(factory.getLogger("pack") === factory.getLogger("project"))
    }
}
