package com.example.ghactions.api.dto

import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunDtoMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "id": 30433642,
          "name": "Build",
          "head_branch": "main",
          "head_sha": "009b8a3a9ccbb128af87f9b1c0f4c62e8a304f6d",
          "path": ".github/workflows/build.yml",
          "display_title": "Update README",
          "run_number": 562,
          "event": "push",
          "status": "completed",
          "conclusion": "success",
          "workflow_id": 159038,
          "url": "https://api.github.com/repos/octo/Hello-World/actions/runs/30433642",
          "html_url": "https://github.com/octo/Hello-World/actions/runs/30433642",
          "created_at": "2020-01-22T19:33:08Z",
          "updated_at": "2020-01-22T19:33:08Z",
          "actor": { "login": "octocat", "id": 1 }
        }
    """.trimIndent()

    @Test
    fun `RunDto deserializes a real GitHub response`() {
        val dto = json.decodeFromString<RunDto>(sample)
        assertEquals(30433642L, dto.id)
        assertEquals("Build", dto.name)
        assertEquals("main", dto.headBranch)
        assertEquals("completed", dto.status)
        assertEquals("success", dto.conclusion)
        assertEquals("octocat", dto.actor?.login)
        assertEquals("Update README", dto.displayTitle)
    }

    @Test
    fun `RunDto handles in-progress runs with null conclusion`() {
        val running = sample.replace(""""conclusion": "success"""", """"conclusion": null""")
            .replace(""""status": "completed"""", """"status": "in_progress"""")
        val dto = json.decodeFromString<RunDto>(running)
        assertEquals("in_progress", dto.status)
        assertNull(dto.conclusion)
    }

    @Test
    fun `RunDto toDomain produces correct Run`() {
        val dto = json.decodeFromString<RunDto>(sample)
        val run = dto.toDomain()
        assertEquals(RunId(30433642L), run.id)
        assertEquals("Build", run.workflowName)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(RunConclusion.SUCCESS, run.conclusion)
        assertEquals("main", run.headBranch)
        assertEquals("009b8a3a9ccbb128af87f9b1c0f4c62e8a304f6d", run.headSha)
        assertEquals("push", run.event)
        assertEquals("octocat", run.actorLogin)
        assertEquals(Instant.parse("2020-01-22T19:33:08Z"), run.createdAt)
        assertEquals(562, run.runNumber)
        assertEquals("Update README", run.displayTitle)
    }

    @Test
    fun `unknown status value maps to UNKNOWN, not exception`() {
        val weird = sample.replace(""""status": "completed"""", """"status": "frobnicating"""")
        val dto = json.decodeFromString<RunDto>(weird)
        assertEquals(RunStatus.UNKNOWN, dto.toDomain().status)
    }
}
