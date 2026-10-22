package org.zender.app.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * 字段里的音频试听。
 *
 * 同一时刻只播放一个文件；[playingPath] 作为 Compose 状态驱动按钮图标。
 * 只负责播放，不改动工程或资源。
 */
internal object AudioPreview {

    private var clip: Clip? = null
    private var generation = 0

    /** 当前正在播放的文件；null 表示空闲。 */
    var playingPath by mutableStateOf<Path?>(null)
        private set

    /** 开始播放；再次点击同一文件时停止。返回 false 表示格式不支持或读取失败。 */
    fun toggle(path: Path): Boolean {
        if (playingPath == path) {
            stop()
            return true
        }
        stop()
        val token = ++generation
        return runCatching {
            val stream = AudioSystem.getAudioInputStream(path.toFile())
            val next = AudioSystem.getClip()
            next.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP && token == generation) {
                    playingPath = null
                    if (clip === next) clip = null
                    runCatching { next.close() }
                }
            }
            next.open(stream)
            stream.close()
            clip = next
            playingPath = path
            next.start()
            true
        }.getOrDefault(false)
    }

    fun stop() {
        val current = clip ?: return
        generation++
        clip = null
        playingPath = null
        runCatching { current.stop() }
        runCatching { current.close() }
    }
}
