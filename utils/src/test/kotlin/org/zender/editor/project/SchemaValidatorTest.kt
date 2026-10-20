package org.zender.editor.project

import org.zender.editor.document.JsonDocument
import org.zender.editor.document.JsonPath
import org.zender.editor.document.jsonNumber
import org.zender.editor.document.jsonString
import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class SchemaValidatorTest {

    private val pack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val schemas = pack.schemaRepository
    private val validator = SchemaValidator(pack)

    private val probePack = DataPackLoader().load(TestFixtures.probePack()).packOrThrow()
    private val probeValidator = SchemaValidator(probePack)

    private fun validateProbe(text: String, isPatch: Boolean = false): List<ValidationIssue> =
        probeValidator.validateDocument(
            schemas = probePack.schemaRepository,
            typeName = "Probe",
            document = JsonDocument.parseOrThrow(text),
            relativePath = "Probe/Test.json",
            isPatch = isPatch,
        )

    private fun mlProject(): ModProject =
        MlProjectReader().read(TestFixtures.sampleMod("ml-sample"), pack).projectOrThrow()

    private fun validate(
        text: String,
        typeName: String = "CardData",
        isPatch: Boolean = false,
    ): List<ValidationIssue> = validator.validateDocument(
        schemas = schemas,
        typeName = typeName,
        document = JsonDocument.parseOrThrow(text),
        relativePath = "CardData/Test.json",
        isPatch = isPatch,
    )

    @Test
    fun `样例工程没有字段级问题`() {
        assertTrue(validator.validate(mlProject()).isEmpty())
    }

    @Test
    fun `未知字段在普通对象中只告警`() {
        val issues = validate("""{"UnknownField":1}""")

        val issue = issues.first { it.code == "schema.field.unknown" }
        assertEquals(ValidationSeverity.WARNING, issue.severity)
    }

    @Test
    fun `补丁中的未知字段视为错误`() {
        val issues = validate("""{"UnknownField":1}""", isPatch = true)

        val issue = issues.first { it.code == "schema.field.unknown" }
        assertEquals(ValidationSeverity.ERROR, issue.severity)
    }

    @Test
    fun `字段类型不符时报错`() {
        val issues = validate("""{"ObjectWeight":"heavy"}""")

        assertTrue(issues.any { it.code == "schema.field.type" && it.isError })
    }

    @Test
    fun `枚举值不在取值范围内时报错`() {
        val issues = validate("""{"CardType":99}""")

        assertTrue(issues.any { it.code == "schema.field.type" && it.message.contains("枚举") })
    }

    @Test
    fun `合法枚举值不报错`() {
        assertTrue(validate("""{"CardType":3}""").isEmpty())
    }

    @Test
    fun `override 内容按目标 Schema 校验`() {
        val issues = validate("""{"${'$'}override":{"ObjectWeight":"heavy"}}""" , isPatch = true)

        assertTrue(issues.any { it.code == "schema.field.type" })
        assertTrue(issues.none { it.code == "schema.field.unknown" && it.message.contains("\$override") })
    }

    @Test
    fun `Warp 陪伴键不被当成未知字段`() {
        val issues = validate(
            """{"SpawningBlockedBy":[],"SpawningBlockedByWarpData":["1111"],"SpawningBlockedByWarpType":3}""",
        )

        assertTrue(issues.none { it.code == "schema.field.unknown" })
    }

    @Test
    fun `未知类型在工程校验中告警`() {
        val project = mlProject()
        val unknown = ProjectObject(
            key = ObjectKey.of("UnknownType", "Thing"),
            kind = ProjectObjectKind.SCRIPTABLE_OBJECT,
            document = JsonDocument.parseOrThrow("""{"A":1}"""),
            relativePath = "ScriptableObject/UnknownType/Thing.json",
        )
        val updated = project.copy(
            workingTree = project.workingTree.withObject(unknown),
        )

        val issue = validator.validate(updated).first { it.code == "schema.type.missing" }
        assertEquals(ValidationSeverity.WARNING, issue.severity)
    }

    @Test
    fun `写入校验拒绝不存在的字段`() {
        val issues = validator.validateFieldWrite(
            typeName = "CardData",
            path = JsonPath.parse("NoSuchField"),
            value = jsonNumber(1),
        )

        assertTrue(issues.any { it.code == "schema.field.missing" && it.isError })
    }

    @Test
    fun `写入校验拒绝只读字段`() {
        val issues = validator.validateFieldWrite(
            typeName = "CardData",
            path = JsonPath.parse("CardName.LocalizationKey"),
            value = jsonString("key"),
        )

        assertTrue(issues.any { it.code == "schema.field.readOnly" && it.isError })
    }

    @Test
    fun `写入校验检查字段类型`() {
        assertTrue(
            validator.validateFieldWrite(
                typeName = "CardData",
                path = JsonPath.parse("ObjectWeight"),
                value = jsonString("heavy"),
            ).any { it.code == "schema.field.type" },
        )

        assertTrue(
            validator.validateFieldWrite(
                typeName = "CardData",
                path = JsonPath.parse("ObjectWeight"),
                value = jsonNumber(1.5),
            ).isEmpty(),
        )
    }

    @Test
    fun `写入 Warp 陪伴键时映射到基础字段`() {
        assertTrue(
            validator.validateFieldWrite(
                typeName = "CardData",
                path = JsonPath.parse("SpawningBlockedByWarpData"),
                value = jsonString("1111"),
            ).isEmpty(),
        )
    }

    @Test
    fun `写入校验可以使用工程自带数据包`() {
        val issues = SchemaValidator().validateFieldWrite(
            project = mlProject(),
            typeName = "CardData",
            path = JsonPath.parse("NoSuchField"),
            value = jsonNumber(1),
        )

        assertTrue(issues.any { it.code == "schema.field.missing" })
    }

    // ------------------------------------------------------------ Probe 类型覆盖

    @Test
    fun `Probe 合法文档没有字段问题`() {
        val issues = validateProbe(
            """{
              "Flag": false,
              "Count": 1,
              "Ratio": 1.5,
              "Text": "x",
              "Mode": 1,
              "Child": {"ParentObjectID":"","LocalizationKey":"","DefaultText":""},
              "Target": {},
              "Tags": [],
              "Numbers": [1,2],
              "Modes": [0,1],
              "Children": [{"DefaultText":"child"}],
              "Mystery": null
            }""",
        )

        assertTrue(issues.isEmpty(), issues.joinToString())
    }

    @Test
    fun `基础类型不匹配时分别报告期望类型`() {
        val issues = validateProbe(
            """{
              "Flag": 0,
              "Count": "x",
              "Ratio": [],
              "Text": {},
              "Child": false,
              "Numbers": null
            }""",
        )

        val messages = issues.filter { it.code == "schema.field.type" }.map { it.message }
        assertTrue(messages.any { it.contains("期望布尔值，实际为 数字") })
        assertTrue(messages.any { it.contains("期望整数，实际为 字符串") })
        assertTrue(messages.any { it.contains("期望数字，实际为 数组") })
        assertTrue(messages.any { it.contains("期望字符串，实际为 对象") })
        assertTrue(messages.any { it.contains("期望对象，实际为 布尔值") })
        assertTrue(messages.any { it.contains("期望数组，实际为 null") })
    }

    @Test
    fun `枚举与引用本体类型不匹配时报告`() {
        val issues = validateProbe(
            """{
              "Mode": 99,
              "Target": "not-an-object",
              "Tags": {}
            }""",
        )

        val messages = issues.filter { it.code == "schema.field.type" }.map { it.message }
        assertTrue(messages.any { it.contains("枚举") })
        assertTrue(messages.any { it.contains("引用字段本体期望空对象") })
        assertTrue(messages.any { it.contains("期望数组，实际为 对象") })
    }

    @Test
    fun `列表元素类型错误带下标`() {
        val issues = validateProbe("""{"Numbers":[1,"x",3]}""")

        val issue = issues.first { it.code == "schema.field.type" }
        assertTrue(issue.message.contains("[1]"))
        assertTrue(issue.message.contains("整数"))
    }

    @Test
    fun `枚举列表元素非法时报错`() {
        val issues = validateProbe("""{"Modes":[0,9]}""")

        val issue = issues.first { it.code == "schema.field.type" }
        assertTrue(issue.message.contains("[1]"))
        assertTrue(issue.message.contains("枚举"))
    }

    @Test
    fun `对象列表元素递归遍历`() {
        val issues = validateProbe(
            """{"Children":[{"ParentObjectID":1}]}""",
        )

        assertTrue(issues.any { it.code == "schema.field.type" })
    }

    @Test
    fun `文档根节点不是对象时报错`() {
        val issues = validateProbe("[]")

        assertTrue(issues.any { it.code == "schema.document.invalid" })
    }

    @Test
    fun `Probe 写入校验覆盖列表元素与空路径`() {
        assertTrue(
            probeValidator.validateFieldWrite(
                typeName = "Probe",
                path = JsonPath.parse("Numbers[0]"),
                value = jsonString("x"),
            ).any { it.code == "schema.field.type" },
        )
        assertTrue(
            probeValidator.validateFieldWrite(
                typeName = "Probe",
                path = JsonPath.parse("Numbers[0]"),
                value = jsonNumber(1),
            ).isEmpty(),
        )
        assertTrue(
            probeValidator.validateFieldWrite(
                typeName = "Probe",
                path = JsonPath.ROOT,
                value = jsonNumber(1),
            ).any { it.code == "schema.field.missing" },
        )
        assertTrue(
            probeValidator.validateFieldWrite(
                typeName = "NoSuchType",
                path = JsonPath.key("X"),
            ).any { it.code == "schema.field.missing" },
        )
    }

    @Test
    fun `Probe 写入嵌套对象与越界数组路径`() {
        assertTrue(
            probeValidator.validateFieldWrite(
                typeName = "Probe",
                path = JsonPath.parse("Child.NoSuchField"),
                value = jsonNumber(1),
            ).any { it.code == "schema.field.missing" },
        )
        assertTrue(
            probeValidator.validateFieldWrite(
                typeName = "Probe",
                path = JsonPath.parse("Numbers[0].Child"),
                value = jsonNumber(1),
            ).any { it.code == "schema.field.missing" },
        )
    }

    @Test
    fun `补丁中的 Probe 未知字段视为错误`() {
        val issues = validateProbe("""{"UnknownProbeField":1}""", isPatch = true)

        val issue = issues.first { it.code == "schema.field.unknown" }
        assertEquals(ValidationSeverity.ERROR, issue.severity)
    }

    @Test
    fun `重复未知键只报告一次`() {
        val issues = validateProbe("""{"UnknownProbeField":1,"UnknownProbeField":2}""")

        assertEquals(
            1,
            issues.count { it.code == "schema.field.unknown" },
        )
    }

    @Test
    fun `对象列表中的非对象元素被跳过`() {
        assertTrue(validateProbe("""{"Children":[1]}""").isEmpty())
    }

    @Test
    fun `未知类型文档遍历不产生字段问题`() {
        val issues = probeValidator.validateDocument(
            schemas = probePack.schemaRepository,
            typeName = "NoSuchType",
            document = JsonDocument.parseOrThrow("""{"A":1}"""),
            relativePath = "NoSuchType/Test.json",
            isPatch = false,
        )

        assertTrue(issues.isEmpty())
    }
}
