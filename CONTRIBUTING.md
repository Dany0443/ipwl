# Contributing to IPWL

Thank you for contributing to IPWL! To keep the repository organized and ensure seamless automated builds and releases, please follow these guidelines.

## Branching Model

IPWL uses a **multi-version branching strategy**:

- **`main`**: The primary development branch. Contains the latest codebase targeting the newest Minecraft version (**26.3**).
- **`26.3`**: Tracks Minecraft 26.3 (Active / Maintained).
- **`26.2`**, **`26.1`**: Dedicated branches for 26.x versions (Maintained).
- **`1.21.1`** through **`1.21.11`**: Dedicated branches for legacy 1.21.x releases (Paused).

### Submitting Pull Requests
- New features and active enhancements should be submitted against **`main`**.
- Fixes or backports specific to an older Minecraft version should target that version's dedicated branch (e.g. `1.21.1`).
- All code for the mod is located directly at the root of each branch (`src/`, `build.gradle`, etc.).

## Commit Message Convention

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification. All commit messages and pull request titles should use the format:

```text
<type>(<optional scope>): <description>
```

### Common Types

| Prefix | Description | Example |
|---|---|---|
| `feat:` | A new feature or capability | `feat(whitelist): add subnet CIDR matching support` |
| `fix:` | A bug fix | `fix(auth): fix race condition during disconnect handling` |
| `security:` | Security hardening, vulnerability fix, or bot protection | `security(ratelimit): enforce tempban on rapid credential stuffing` |
| `chore:` | Build system changes, dependency updates, maintenance | `chore(gradle): upgrade Fabric Loom to 1.17.11` |
| `docs:` | Documentation improvements | `docs(readme): add branch structure guide` |
| `refactor:` | Code change that neither fixes a bug nor adds a feature | `refactor(manager): extract connection state logic` |
| `perf:` | A code change that improves performance | `perf(cache): optimize IP lookup lookup time` |
| `ci:` | Changes to CI/CD workflows and scripts | `ci(release): add automated draft release workflow` |
| `test:` | Adding or modifying tests | `test(ip): add unit tests for IPv6 parsing` |

## Local Development and Testing

1. Ensure you have **Java 25** installed.
2. Clone the repository and switch to your desired version branch:
   ```bash
   git checkout <branch-name>
   ```
3. Build the mod using the Gradle wrapper:
   ```bash
   ./gradlew build
   ```
4. Compiled jars will be generated in `build/libs/`.

## Automated CI and Releases

- **Continuous Integration (`build.yml`)**: Compiles the jar file automatically on every push and pull request across all branches. Jars are uploaded as GitHub Actions artifacts.
- **Draft Releases (`release-draft.yml`)**: Automatically compiles jars across all supported Minecraft version branches in parallel and attaches them to a GitHub Release draft.
