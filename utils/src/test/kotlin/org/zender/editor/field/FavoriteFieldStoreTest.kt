package org.zender.editor.field

import org.zender.editor.io.AtomicFileStore
import org.zender.editor.settings.SettingsStore
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FavoriteFieldStoreTest {

    private val tempDirectory: Path = createTempDirectory("csmode-favorites-")
    private val settingsPath: Path = tempDirectory.resolve("settings.json")
    private val store = FavoriteFieldStore(
        SettingsStore(AtomicFileStore(), settingsPath),
    )

    @AfterTest
    fun cleanup() {
        tempDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `初始没有收藏`() {
        assertTrue(store.favorites().isEmpty())
        assertFalse(store.isFavorite("CardData", "CardType"))
    }

    @Test
    fun `添加收藏并持久化`() {
        store.setFavorite("CardData", "CardType", true)
        store.setFavorite("CardData", "CardName.DefaultText", true)

        assertTrue(store.isFavorite("CardData", "CardType"))
        assertEquals(
            setOf("CardType", "CardName.DefaultText"),
            store.favoritePaths("CardData"),
        )

        val reloaded = FavoriteFieldStore(SettingsStore(AtomicFileStore(), settingsPath))
        assertTrue(reloaded.isFavorite("CardData", "CardType"))
        assertEquals(2, reloaded.favorites().size)
    }

    @Test
    fun `切换收藏状态`() {
        assertTrue(store.toggle("CardTag", "InGameName"))
        assertTrue(store.isFavorite("CardTag", "InGameName"))
        assertFalse(store.toggle("CardTag", "InGameName"))
        assertFalse(store.isFavorite("CardTag", "InGameName"))
    }

    @Test
    fun `重复收藏不会产生重复项`() {
        store.setFavorite("CardData", "CardType", true)
        store.setFavorite("CardData", "CardType", true)

        assertEquals(1, store.favorites().size)
    }

    @Test
    fun `移除某类型的全部收藏`() {
        store.setFavorite("CardData", "CardType", true)
        store.setFavorite("CardTag", "InGameName", true)

        store.removeAll("CardData")

        assertFalse(store.isFavorite("CardData", "CardType"))
        assertTrue(store.isFavorite("CardTag", "InGameName"))
    }

    @Test
    fun `清空收藏`() {
        store.setFavorite("CardData", "CardType", true)

        store.clear()

        assertTrue(store.favorites().isEmpty())
    }

    @Test
    fun `收藏夹可以添加并分别收藏`() {
        val first = store.addFolder("战斗")
        val second = store.addFolder("界面")
        store.addToFolder("CardData", "CardType", first.id)
        store.addToFolder("CardData", "CardName", second.id)

        assertEquals(2, store.folders().size)
        assertEquals(
            listOf("CardType"),
            store.folders().first { it.id == first.id }.fields.map { it.fieldPath },
        )
        assertEquals(
            listOf("CardName"),
            store.folders().first { it.id == second.id }.fields.map { it.fieldPath },
        )

        store.removeFolder(first.id)
        assertEquals(1, store.folders().size)
        assertFalse(store.isFavorite("CardData", "CardType"))
        assertTrue(store.isFavorite("CardData", "CardName"))
    }

    @Test
    fun `同一字段移动到另一个收藏夹`() {
        val first = store.addFolder("A")
        val second = store.addFolder("B")
        store.addToFolder("CardData", "CardType", first.id)
        store.addToFolder("CardData", "CardType", second.id)

        assertEquals(1, store.favorites().size)
        assertEquals(0, store.folders().first { it.id == first.id }.fields.size)
        assertEquals(1, store.folders().first { it.id == second.id }.fields.size)
    }

    @Test
    fun `类型或路径为空时拒绝写入`() {
        assertFailsWith<IllegalArgumentException> {
            store.setFavorite("", "CardType", true)
        }
        assertFailsWith<IllegalArgumentException> {
            store.setFavorite("CardData", "", true)
        }
    }
}
