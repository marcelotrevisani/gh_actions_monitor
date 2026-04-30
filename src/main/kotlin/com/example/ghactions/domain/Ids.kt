package com.example.ghactions.domain

@JvmInline
value class RunId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class JobId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ArtifactId(val value: Long) {
    override fun toString(): String = value.toString()
}
