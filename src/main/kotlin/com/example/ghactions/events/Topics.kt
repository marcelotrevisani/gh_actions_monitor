package com.example.ghactions.events

import com.intellij.util.messages.Topic

/** Event signaling that the resolved authentication source has changed. */
fun interface AuthChangedListener {
    fun onAuthChanged()
}

/** Event signaling that the project's bound GitHub repo has changed (or become null). */
fun interface RepoBindingChangedListener {
    fun onRepoBindingChanged(newBinding: BoundRepo?)
}

/** Snapshot of a repo binding, published with [RepoBindingChangedListener]. */
data class BoundRepo(
    val host: String,        // e.g. "https://api.github.com"
    val owner: String,
    val repo: String
)

object Topics {
    val AUTH_CHANGED: Topic<AuthChangedListener> =
        Topic.create("GhActions.AuthChanged", AuthChangedListener::class.java)

    val REPO_BINDING_CHANGED: Topic<RepoBindingChangedListener> =
        Topic.create("GhActions.RepoBindingChanged", RepoBindingChangedListener::class.java)

    // RateLimitChanged topic added in Plan 2 alongside the HTTP client.
}
