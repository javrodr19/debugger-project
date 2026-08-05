# Contributing to Aegis Debug

Thank you for contributing to Aegis Debug! Please follow these guidelines when proposing changes.

---

## 1. Commit Message Convention

We follow Conventional Commits: `<type>(<scope>): <subject>`.

- **Allowed Types:** `feat`, `fix`, `docs`, `test`, `chore`, `refactor`, `build`, `perf`, `ci`, `revert`
- **Subject Line:** Imperative mood, 72 characters max, no trailing period.
- **Body:** Wrapped at 72 characters explaining *why* the change was made, referencing prior versions, audit findings, or roadmap items when relevant.
- **Footer:** `Co-Authored-By:` or `refs #issue` where applicable.

### Git Message Template & Hook

Run to set up local template and commit-msg linting hook:

```bash
git config core.hooksPath .githooks && chmod +x .githooks/commit-msg
git config commit.template .gitmessage
```

---

## 2. Branch Convention

- All work stems from short-lived feature or fix branches off `main` (`feat/<topic>`, `fix/<topic>`, `stream/<topic>`).
- Direct commits to `main` are reserved for trivial doc fixes. All non-trivial changes go through branch + PR + green CI.
- Branches must be deleted after merging into `main`.

---

## 3. Build & Test Prerequisites (JBR Setup)

Aegis Debug uses the IntelliJ Platform Gradle Plugin (IPGP). Instrumentation tasks (`instrumentTestCode`) require the **JetBrains Runtime (JBR)**, not a generic OpenJDK.

Before running build or test tasks, export `JAVA_HOME` pointing to the JBR:

```bash
export JAVA_HOME=$(find ~/.gradle/caches -path "*ideaIC-2024.3.2*/jbr" -type d | head -1)
export PATH=$JAVA_HOME/bin:$PATH
```

### Common Gradle Verification Commands

```bash
./gradlew compileKotlin
./gradlew test
./gradlew detekt
./gradlew verifyPlugin
```
