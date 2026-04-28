package com.example.ghactions.repo

import com.example.ghactions.events.BoundRepo
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RepoBindingTest : BasePlatformTestCase() {

    fun testParseGithubHttpsUrl() {
        val parsed = RepoBinding.parseRemote("https://github.com/octocat/Hello-World.git")
        assertEquals(BoundRepo(host = "https://api.github.com", owner = "octocat", repo = "Hello-World"), parsed)
    }

    fun testParseGithubSshUrl() {
        val parsed = RepoBinding.parseRemote("git@github.com:octocat/Hello-World.git")
        assertEquals(BoundRepo(host = "https://api.github.com", owner = "octocat", repo = "Hello-World"), parsed)
    }

    fun testParseGithubHttpsUrlWithoutDotGit() {
        val parsed = RepoBinding.parseRemote("https://github.com/octocat/Hello-World")
        assertEquals(BoundRepo(host = "https://api.github.com", owner = "octocat", repo = "Hello-World"), parsed)
    }

    fun testParseEnterpriseHttpsUrl() {
        val parsed = RepoBinding.parseRemote("https://ghe.example.com/team/service.git")
        assertEquals(
            BoundRepo(host = "https://ghe.example.com/api/v3", owner = "team", repo = "service"),
            parsed
        )
    }

    fun testParseEnterpriseSshUrl() {
        val parsed = RepoBinding.parseRemote("git@ghe.example.com:team/service.git")
        assertEquals(
            BoundRepo(host = "https://ghe.example.com/api/v3", owner = "team", repo = "service"),
            parsed
        )
    }

    fun testParseRejectsNonGithubHosts() {
        assertNull(RepoBinding.parseRemote("https://gitlab.com/foo/bar.git"))
        assertNull(RepoBinding.parseRemote("git@bitbucket.org:foo/bar.git"))
    }

    fun testParseRejectsMalformedInput() {
        assertNull(RepoBinding.parseRemote(""))
        assertNull(RepoBinding.parseRemote("not-a-url"))
        assertNull(RepoBinding.parseRemote("https://github.com/onlyone"))
    }

    fun testCurrentIsNullWhenProjectHasNoGitRemotes() {
        val binding = project.getService(RepoBinding::class.java)
        // Test fixture project has no git remotes by default
        assertNull(binding.current)
    }
}
