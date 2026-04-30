package com.example.ghactions.api.dto

import com.example.ghactions.domain.CheckRunId
import com.example.ghactions.domain.Job
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.domain.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class JobDto(
    val id: Long,
    @SerialName("run_id") val runId: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("check_run_url") val checkRunUrl: String? = null,
    val steps: List<StepDto> = emptyList()
) {
    fun toDomain(): Job = Job(
        id = JobId(id),
        runId = RunId(runId),
        name = name,
        status = RunStatus.fromWire(status),
        conclusion = RunConclusion.fromWire(conclusion),
        startedAt = startedAt?.let(Instant::parse),
        completedAt = completedAt?.let(Instant::parse),
        htmlUrl = htmlUrl,
        steps = steps.map { it.toDomain() },
        checkRunId = checkRunId()
    )
}

@Serializable
data class StepDto(
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val number: Int,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null
) {
    fun toDomain(): Step = Step(
        number = number,
        name = name,
        status = RunStatus.fromWire(status),
        conclusion = RunConclusion.fromWire(conclusion)
    )
}

fun JobDto.checkRunId(): CheckRunId? {
    val url = checkRunUrl ?: return null
    // Trailing path segment of `https://api.github.com/repos/o/r/check-runs/123` → 123
    val tail = url.substringAfterLast('/').toLongOrNull() ?: return null
    return CheckRunId(tail)
}
