package com.example.ghactions.domain

data class Step(
    val number: Int,
    val name: String,
    val status: RunStatus,
    val conclusion: RunConclusion?
)
