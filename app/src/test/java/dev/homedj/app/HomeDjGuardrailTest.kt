package dev.homedj.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static guardrails from AGENTS.md. Fix the code, never this list: a new exception needs a
 * reviewed AGENTS.md change first.
 */
class HomeDjGuardrailTest {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun text(file: File) = file.readText().replace("\r\n", "\n")

    @Test fun productionKotlinAvoidsBannedPatterns() {
        val banned = mapOf(
            "GlobalScope" to Regex("""\bGlobalScope\b"""),
            "runBlocking" to Regex("""\brunBlocking\b"""),
            "Thread.sleep" to Regex("""\bThread\.sleep\("""),
            "!!" to Regex("""!!"""),
            "collectAsState( (use collectAsStateWithLifecycle)" to Regex("""\bcollectAsState\("""),
            "destructive migration" to Regex("""fallbackToDestructiveMigration"""),
        )
        val violations = File(repo, "app/src/main").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file ->
                text(file).lines().withIndex().flatMap { (index, line) ->
                    banned.filterValues { it.containsMatchIn(line) }.keys
                        .map { "${file.name}:${index + 1} uses $it" }
                }
            }.toList()
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test fun manifestKeepsBackupCleartextAndExportsLocked() {
        val manifest = text(File(repo, "app/src/main/AndroidManifest.xml"))
        assertTrue("allowBackup must stay false", manifest.contains("""android:allowBackup="false""""))
        assertTrue("cleartext must stay off", manifest.contains("""android:usesCleartextTraffic="false""""))
        val exported = Regex("""<(activity|service|receiver|provider)\b[^>]*>""").findAll(manifest)
            .map { it.value }
            .filter { it.contains("""android:exported="true"""") }
            .mapNotNull { Regex("""android:name="([^"]+)"""").find(it)?.groupValues?.get(1) }
            .toSet()
        assertEquals(setOf(".HomeDjActivity", ".PlaybackService"), exported)
    }

    @Test fun agentMirrorsMatchTheClaudeSource() {
        val source = File(repo, ".claude/skills")
        val expected = snapshot(source)
        assertTrue("no skills found", expected.isNotEmpty())
        for (mirror in listOf(".agents/skills", ".codex/.agents/skills")) {
            assertEquals("$mirror drifted from .claude/skills", expected, snapshot(File(repo, mirror)))
        }
        assertEquals(
            ".codex/AGENTS.md drifted from AGENTS.md",
            text(File(repo, "AGENTS.md")), text(File(repo, ".codex/AGENTS.md")),
        )
    }

    @Test fun skillsHaveParseableFrontmatterAndNoForeignProjectReferences() {
        val foreign = listOf(
            "FocusLog", "FocusEventId", "FocusDatabase", "FocusMigrations", "runCatchingNonCancellation",
            "AppServices", "BrutalArchitectureGuardrailTest", "com.ankit.destination", "brutalCheck",
            "Firestack", "Shizuku", "Destination's", "in Destination",
        )
        val docs = File(repo, ".claude/skills").walkTopDown().filter { it.extension == "md" } +
            sequenceOf(File(repo, "AGENTS.md"), File(repo, "CLAUDE.md"))
        val leaks = docs.flatMap { file ->
            foreign.filter { text(file).contains(it) }.map { "${file.name} mentions $it" }
        }.toList()
        assertTrue(leaks.joinToString("\n"), leaks.isEmpty())

        File(repo, ".claude/skills").listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val head = text(File(dir, "SKILL.md")).lines()
            assertEquals("${dir.name}: frontmatter start", "---", head.first())
            assertTrue("${dir.name}: name", head.contains("name: ${dir.name}"))
            // An unquoted description containing ": " is invalid YAML and the skill stops auto-loading.
            assertTrue("${dir.name}: quoted description", head.any { it.startsWith("description: \"") && it.endsWith("\"") })
        }
    }

    private fun snapshot(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to text(it) }
}
