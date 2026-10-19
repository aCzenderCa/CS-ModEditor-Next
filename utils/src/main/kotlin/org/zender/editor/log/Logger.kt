package org.zender.editor.log

import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 日志级别，按严重程度递增。
 */
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * 一条日志记录。数据层只负责产生记录，写到哪里由 [LogSink] 决定。
 */
data class LogRecord(
    val timestamp: Instant,
    val level: LogLevel,
    val loggerName: String,
    val message: String,
    val throwable: Throwable? = null,
)

/**
 * 日志输出目标。后续接入文件日志时实现该接口即可，不需要修改调用方。
 */
fun interface LogSink {
    fun emit(record: LogRecord)
}

/**
 * 数据层日志接口。视图层与数据层都只依赖该接口，不直接使用 `println`。
 */
interface Logger {
    val name: String

    fun isEnabled(level: LogLevel): Boolean

    fun log(level: LogLevel, message: String, throwable: Throwable? = null)

    fun trace(message: String, throwable: Throwable? = null) = log(LogLevel.TRACE, message, throwable)

    fun debug(message: String, throwable: Throwable? = null) = log(LogLevel.DEBUG, message, throwable)

    fun info(message: String, throwable: Throwable? = null) = log(LogLevel.INFO, message, throwable)

    fun warn(message: String, throwable: Throwable? = null) = log(LogLevel.WARN, message, throwable)

    fun error(message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, message, throwable)
}

/**
 * 由工厂创建日志实例，便于在测试中注入可捕获的 [LogSink]。
 */
interface LoggerFactory {
    fun getLogger(name: String): Logger
}

/**
 * 基础日志实现：负责级别过滤与记录构造，不关心输出方式。
 */
class SimpleLogger(
    override val name: String,
    private val minLevel: LogLevel,
    private val clock: Clock,
    private val sink: LogSink,
) : Logger {

    override fun isEnabled(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    override fun log(level: LogLevel, message: String, throwable: Throwable?) {
        if (!isEnabled(level)) return
        sink.emit(
            LogRecord(
                timestamp = clock.instant(),
                level = level,
                loggerName = name,
                message = message,
                throwable = throwable,
            ),
        )
    }
}

/**
 * 控制台输出：TRACE/DEBUG/INFO 写入标准输出，WARN/ERROR 写入标准错误。
 */
class ConsoleLogSink(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : LogSink {

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    override fun emit(record: LogRecord) {
        val stream = when (record.level) {
            LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO -> out
            LogLevel.WARN, LogLevel.ERROR -> err
        }
        val timestamp = timestampFormat.format(record.timestamp.atZone(zone))
        synchronized(stream) {
            stream.println("$timestamp ${record.level.name.padEnd(5)} [${record.loggerName}] ${record.message}")
            record.throwable?.printStackTrace(stream)
            stream.flush()
        }
    }
}

/**
 * 默认日志工厂：按名称缓存 [SimpleLogger]，默认输出到控制台。
 */
class ConsoleLoggerFactory(
    private val minLevel: LogLevel = LogLevel.INFO,
    private val sink: LogSink = ConsoleLogSink(),
    private val clock: Clock = Clock.systemDefaultZone(),
) : LoggerFactory {

    private val loggers = ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger =
        loggers.computeIfAbsent(name) { SimpleLogger(it, minLevel, clock, sink) }
}

/**
 * 全局日志入口。测试或后续文件日志可以在启动阶段替换 [factory]。
 */
object Log {

    @Volatile
    var factory: LoggerFactory = ConsoleLoggerFactory()

    fun getLogger(name: String): Logger = factory.getLogger(name)

    fun getLogger(type: Class<*>): Logger = getLogger(type.simpleName)
}
