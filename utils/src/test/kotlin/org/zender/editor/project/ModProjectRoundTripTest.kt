package org.zender.editor.project

import org.zender.editor.fixture.TestFixtures
import org.zender.editor.objects.ObjectKey
import org.zender.editor.pack.DataPackLoader
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ModProjectRoundTripTest {

    private val basePack = DataPackLoader().load(TestFixtures.miniPack()).packOrThrow()
    private val writer = ModProjectWriter()
    private val tempDirectories = mutableListOf<Path>()

    private fun temp(): Path =
        createTempDirectory("csmode-roundtrip-").also { tempDirectories.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `ML 读取写入再读取语义一致`() {
        val original = MlProjectReader()
            .read(TestFixtures.sampleMod("ml-sample"), basePack)
            .projectOrThrow()
        val target = temp().resolve("out")

        val write = writer.write(original, target)
        check(write.isSuccess) { write.diagnostics.joinToString() }
        val reread = MlProjectReader()
            .read(target, basePack)
            .projectOrThrow()

        assertEquals(snapshot(original), snapshot(reread))
    }

    @Test
    fun `MC 读取写入再读取语义一致`() {
        val original = McProjectReader()
            .read(TestFixtures.sampleMod("mc-sample"), basePack)
            .projectOrThrow()
        val target = temp().resolve("out")

        val write = writer.write(original, target)
        check(write.isSuccess) { write.diagnostics.joinToString() }
        val reread = McProjectReader()
            .read(target, basePack)
            .projectOrThrow()

        assertEquals(snapshot(original), snapshot(reread))
    }

    private data class Snapshot(
        val namespace: String,
        val names: Map<String, String>,
        val descriptions: Map<String, String>,
        val author: String,
        val version: String,
        val objects: Map<ObjectKey, org.zender.editor.document.JsonNode>,
        val gameSourceModifies: Map<String, org.zender.editor.document.JsonNode>,
        val dataObjectModifies: Map<DataObjectModifyId, org.zender.editor.document.JsonNode>,
        val localization: List<org.zender.editor.localization.LocalizationEntry>,
        val resources: Map<ResourceId, String>,
    )

    private fun snapshot(project: ModProject): Snapshot = Snapshot(
        namespace = project.metadata.namespace,
        names = project.metadata.names,
        descriptions = project.metadata.descriptions,
        author = project.metadata.author,
        version = project.metadata.version,
        objects = project.workingTree.objects.mapValues { it.value.document.root },
        gameSourceModifies = project.workingTree.gameSourceModifies.mapValues {
            it.value.document.root
        },
        dataObjectModifies = project.workingTree.dataObjectModifies.mapValues {
            it.value.document.root
        },
        localization = project.workingTree.localization.table.entries,
        resources = project.workingTree.resources.mapValues { it.value.relativePath },
    )
}
