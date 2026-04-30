package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.Artifact
import com.example.ghactions.domain.ArtifactId
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunRepositoryArtifactsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")
    private val runId = RunId(7)

    private fun newRepo(client: GitHubClient): RunRepository = RunRepository(
        boundRepo = { repo },
        clientFactory = { client },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    @Test
    fun `refreshArtifacts transitions Idle to Loaded`() = runTest {
        val artifacts = listOf(
            Artifact(id = ArtifactId(1), name = "logs", sizeBytes = 100, expired = false, createdAt = Instant.EPOCH)
        )
        val client = mockk<GitHubClient>()
        coEvery { client.listArtifacts(repo, runId) } returns artifacts
        val repository = newRepo(client)

        repository.refreshArtifacts(runId).join()
        val state = repository.artifactsState(runId).first()
        assertTrue(state is ArtifactsState.Loaded, "expected Loaded, got $state")
        assertEquals(artifacts, (state as ArtifactsState.Loaded).artifacts)
    }

    @Test
    fun `refreshArtifacts maps GitHubApiException to Error state`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listArtifacts(repo, runId) } throws GitHubApiException(status = 404, message = "not found")
        val repository = newRepo(client)

        repository.refreshArtifacts(runId).join()
        val state = repository.artifactsState(runId).first()
        assertTrue(state is ArtifactsState.Error, "expected Error, got $state")
        assertEquals(404, (state as ArtifactsState.Error).httpStatus)
    }

    @Test
    fun `downloadArtifactToFile writes zip bytes to the requested path`() = runTest {
        val payload = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x09)
        val client = mockk<GitHubClient>()
        coEvery { client.downloadArtifact(repo, ArtifactId(11)) } returns payload
        val repository = newRepo(client)

        val tmp = kotlin.io.path.createTempFile("ghactions-test-", ".zip").toFile()
        try {
            val result = repository.downloadArtifactToFile(ArtifactId(11), tmp)
            assertTrue(result is DownloadResult.Success, "expected Success, got $result")
            assertEquals(tmp, (result as DownloadResult.Success).file)
            kotlin.test.assertContentEquals(payload, tmp.readBytes())
        } finally {
            tmp.delete()
        }
    }
}
