package com.example.ghactions.api.dto

import com.example.ghactions.domain.Artifact
import com.example.ghactions.domain.ArtifactId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ArtifactDto(
    val id: Long,
    val name: String,
    @SerialName("size_in_bytes") val sizeInBytes: Long,
    val expired: Boolean,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain() = Artifact(
        id = ArtifactId(id),
        name = name,
        sizeBytes = sizeInBytes,
        expired = expired,
        createdAt = createdAt?.let(Instant::parse)
    )
}

/** GitHub's list-artifacts envelope: `{ total_count, artifacts: [...] }`. */
@Serializable
data class ListArtifactsResponse(
    @SerialName("total_count") val totalCount: Int,
    val artifacts: List<ArtifactDto>
)
