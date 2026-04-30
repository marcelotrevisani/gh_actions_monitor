# GitHub Actions Monitor

A JetBrains IDE plugin that monitors and acts on GitHub Actions for the
project's repository without leaving the IDE. Works with IntelliJ IDEA,
PyCharm, and other IntelliJ-Platform-based IDEs (build 243+ / 2024.3+).

- View workflow runs, jobs, and step summaries in a tool window
- Trigger and re-run workflows
- Pull-request awareness (linked workflow runs)
- Status-bar widget showing the latest run state
- Authenticate via a Personal Access Token *or* your IDE-configured GitHub
  account (Settings → Version Control → GitHub)
- Supports github.com and GitHub Enterprise Server

## Compatibility

| Field         | Value                                          |
| ------------- | ---------------------------------------------- |
| IDE platform  | IntelliJ Platform 243 – 261.\* (2024.3 – 2026.1) |
| IDE products  | IntelliJ IDEA, PyCharm, GoLand, WebStorm, etc. |
| Java runtime  | 17 (bundled with the IDE)                      |
| Required deps | Git4Idea, GitHub plugin (both bundled)         |

## Install from the JetBrains Marketplace

1. Open the IDE → **Settings** → **Plugins** → **Marketplace** tab.
2. Search for **GitHub Actions Monitor**.
3. Click **Install** and restart the IDE when prompted.

## Install from a release ZIP

Use this if you want a specific version, want to install offline, or the
plugin isn't yet listed on the Marketplace.

1. Download the `.zip` from the
   [Releases page](https://github.com/marcelotrevisani/gh_actions_pycharm/releases)
   (or grab the `plugin-distribution` artifact from a Test workflow run).
2. In the IDE: **Settings** → **Plugins** → ⚙️ (gear icon) →
   **Install Plugin from Disk…**
3. Select the downloaded ZIP and restart the IDE when prompted.

## Build and install from source

Requires JDK 17. The Gradle wrapper handles everything else.

```bash
git clone https://github.com/marcelotrevisani/gh_actions_pycharm.git
cd gh_actions_pycharm
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/`. Install it via
**Install Plugin from Disk…** (see previous section).

To run the plugin in a sandboxed IDE for development:

```bash
./gradlew runIde
```

## Configure

After installing and restarting, open **Settings** → **Tools** →
**GitHub Actions Monitor** and pick one of:

- **Use IDE-configured GitHub account** — reuses an account you've already
  added under *Settings → Version Control → GitHub*. Recommended for
  github.com.
- **Personal Access Token (PAT)** — paste a token with `repo` and
  `workflow` scopes. For GitHub Enterprise Server, also set the API base
  URL (e.g. `https://github.mycompany.com/api/v3`).

The tool window appears on the right edge of the IDE under the
**GitHub Actions** title; if you don't see it, enable it via
**View** → **Tool Windows** → **GitHub Actions**.

## Disclaimer

This software is provided "as is", without any guarantee or warranty of
any kind. The author does not provide any guarantee about the software —
use it at your own responsibility. See [LICENSE](LICENSE) for the full
Apache License 2.0 text including the warranty disclaimer (Section 7) and
limitation of liability (Section 8).

## License

Licensed under the [Apache License 2.0](LICENSE).
