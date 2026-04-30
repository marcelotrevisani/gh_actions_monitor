package com.example.ghactions.repo

/**
 * Translates an HTTP error into a short, user-actionable status line.
 *
 * GitHub deliberately returns 404 (not 403) for repos a token can't see, which is
 * confusing in our UI — the user thinks the repo is gone when actually their account
 * just doesn't have access. The 401/403/404 messages here name the likely cause and
 * point at the setting to change. Other status codes fall through to the raw API body.
 */
fun friendlyApiError(httpStatus: Int?, message: String): String = when (httpStatus) {
    401 -> "Unauthorized (401). The selected account's token is invalid or expired — " +
        "reauthorize via Settings → Version Control → GitHub."
    403 -> "Forbidden (403). The token may lack required scopes (need 'repo' for private repos) " +
        "or the org requires SSO authorization for this token."
    404 -> "Not visible to the selected account (404). Either the repo doesn't exist, or the " +
        "account isn't a collaborator. Pick a different account in " +
        "Settings → Tools → GitHub Actions Monitor."
    else -> "Failed${httpStatus?.let { " ($it)" } ?: ""}: $message"
}
