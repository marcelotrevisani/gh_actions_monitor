package com.example.ghactions.api.dto

import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobDtoMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "id": 399444496,
          "run_id": 29679449,
          "name": "build",
          "status": "completed",
          "conclusion": "success",
          "started_at": "2020-01-20T17:42:40Z",
          "completed_at": "2020-01-20T17:44:39Z",
          "html_url": "https://github.com/octo/Hello-World/runs/399444496",
          "steps": [
            {
              "name": "Set up job",
              "status": "completed",
              "conclusion": "success",
              "number": 1,
              "started_at": "2020-01-20T09:42:40Z",
              "completed_at": "2020-01-20T09:42:41Z"
            },
            {
              "name": "Run actions/checkout@v2",
              "status": "completed",
              "conclusion": "success",
              "number": 2,
              "started_at": "2020-01-20T09:42:41Z",
              "completed_at": "2020-01-20T09:42:45Z"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `JobDto deserializes a real GitHub response`() {
        val dto = json.decodeFromString<JobDto>(sample)
        assertEquals(399444496L, dto.id)
        assertEquals(29679449L, dto.runId)
        assertEquals("build", dto.name)
        assertEquals("completed", dto.status)
        assertEquals("success", dto.conclusion)
        assertEquals(2, dto.steps.size)
    }

    @Test
    fun `JobDto handles still-running jobs with null timestamps and conclusion`() {
        val running = sample
            .replace(""""status": "completed"""", """"status": "in_progress"""")
            .replace(""""conclusion": "success"""", """"conclusion": null""")
            .replace(""""completed_at": "2020-01-20T17:44:39Z"""", """"completed_at": null""")
        val dto = json.decodeFromString<JobDto>(running)
        assertEquals("in_progress", dto.status)
        assertNull(dto.conclusion)
        assertNull(dto.completedAt)
    }

    @Test
    fun `JobDto toDomain produces correct Job and steps`() {
        val dto = json.decodeFromString<JobDto>(sample)
        val job = dto.toDomain()
        assertEquals(JobId(399444496L), job.id)
        assertEquals(RunId(29679449L), job.runId)
        assertEquals("build", job.name)
        assertEquals(RunStatus.COMPLETED, job.status)
        assertEquals(RunConclusion.SUCCESS, job.conclusion)
        assertEquals(Instant.parse("2020-01-20T17:42:40Z"), job.startedAt)
        assertEquals(2, job.steps.size)
        assertEquals(1, job.steps[0].number)
        assertEquals("Set up job", job.steps[0].name)
        assertEquals(RunStatus.COMPLETED, job.steps[0].status)
        assertEquals(RunConclusion.SUCCESS, job.steps[0].conclusion)
    }
}
