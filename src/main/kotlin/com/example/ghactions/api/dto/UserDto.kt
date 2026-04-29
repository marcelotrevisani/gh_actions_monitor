package com.example.ghactions.api.dto

import kotlinx.serialization.Serializable

/**
 * The minimal subset of GitHub's user object we surface — used for the run actor.
 * GitHub returns far more fields; [Json.ignoreUnknownKeys] in [GitHubHttp] handles the rest.
 */
@Serializable
data class UserDto(
    val login: String,
    val id: Long
)
