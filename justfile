set shell := ["bash", "-cu"]

# Default plugin version for `package` / `verify` when not overridden.
# Override per-invocation: `just package 0.2.0`
default_version := "0.1.0"

# List available recipes.
default:
    @just --list

# Run the full test suite.
test:
    ./gradlew --no-daemon test

# Run a single test class or method.
# Example: just test-only com.example.ghactions.api.GitHubClientTest
test-only filter:
    ./gradlew --no-daemon test --tests "{{filter}}"

# Build the plugin distribution ZIP. Output: build/distributions/<name>-<version>.zip
package version=default_version:
    ./gradlew --no-daemon -PpluginVersion={{version}} clean buildPlugin
    @ls -lh build/distributions/

# Verify the plugin against the configured target IDEs.
verify version=default_version:
    ./gradlew --no-daemon -PpluginVersion={{version}} verifyPlugin

# Full release-candidate build: tests + package + verifier.
release version=default_version:
    ./gradlew --no-daemon -PpluginVersion={{version}} clean test buildPlugin verifyPlugin
    @ls -lh build/distributions/

# Launch a sandboxed IDE with the plugin installed for manual testing.
run:
    ./gradlew runIde

# Remove build outputs.
clean:
    ./gradlew --no-daemon clean
