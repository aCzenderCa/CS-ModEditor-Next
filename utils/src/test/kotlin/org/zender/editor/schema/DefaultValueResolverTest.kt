package org.zender.editor.schema

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonNode
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.io.AtomicFileStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DefaultValueResolverTest {

    private val repository = SchemaRepository.load(
        fileStore = AtomicFileStore(),
        packRoot = TestFixtures.miniPack(),
        referenceTypes = setOf("CardData", "CharacterPerk", "CardTag"),
    ).repository

    private val resolver = DefaultValueResolver(repository)

    @Test
    fun `缺失字段视为默认值`() {
        assertTrue(resolver.isDefault("CardData", "CardTags", null))
    }

    @Test
    fun `数值按语义比较`() {
        val template = JsonNode.NumberNode("0.0")
        assertTrue(DefaultValueResolver.semanticallyEquals(template, JsonNode.NumberNode("0")))
        assertTrue(DefaultValueResolver.semanticallyEquals(template, JsonNode.NumberNode("0.00")))
        assertFalse(DefaultValueResolver.semanticallyEquals(template, JsonNode.NumberNode("0.1")))
    }

    @Test
    fun `对象忽略成员顺序`() {
        val left = JsonDocument.parseOrThrow("""{"a":1,"b":{"c":2}}""").root
        val right = JsonDocument.parseOrThrow("""{"b":{"c":2},"a":1.0}""").root

        assertTrue(DefaultValueResolver.semanticallyEquals(left, right))
    }

    @Test
    fun `数组按顺序比较`() {
        val left = JsonDocument.parseOrThrow("""[1,2]""").root
        val right = JsonDocument.parseOrThrow("""[2,1]""").root

        assertFalse(DefaultValueResolver.semanticallyEquals(left, right))
    }

    @Test
    fun `显式写入模板值仍视为默认`() {
        val value = JsonDocument.parseOrThrow(
            """{"ParentObjectID":"","LocalizationKey":"","DefaultText":""}""",
        ).root

        assertTrue(resolver.isDefault("CardData", "CardName", value))
    }

    @Test
    fun `模板中不存在的字段只有缺失时算默认`() {
        assertTrue(resolver.isDefault("CardData", "UnknownField", null))
        assertFalse(
            resolver.isDefault("CardData", "UnknownField", JsonNode.StringNode("x")),
        )
    }

    @Test
    fun `修改后的字段不是默认`() {
        val value = JsonDocument.parseOrThrow(
            """{"ParentObjectID":"1111","LocalizationKey":"x","DefaultText":"刀"}""",
        ).root

        assertFalse(resolver.isDefault("CardData", "CardName", value))
    }
}
