# Decisions Log

Decisions not covered in `docs/ARCHITECTURE.md` are recorded here (one line: decision, reason).

| Date | Decision | Rationale |
|---|---|---|
| 2026-10-30 | Maven multi-module scaffold targeting Java 21 | Modular architecture and compilation target as specified in architecture |
| 2026-10-30 | Use latest stable dependency and plugin versions | Spring Boot 3.5.16, ArchUnit 1.5.1, jqwik 1.10.2, Testcontainers 2.0.5, JaCoCo 0.8.16, SpotBugs 4.10.5.0 |
| 2026-10-30 | Explicit `<relativePath>../pom.xml</relativePath>` across all child POMs | Prevents IDE language server resolution errors when inspecting submodule POMs |
| 2026-10-30 | Use `${maven.multiModuleProjectDirectory}` for checkstyle and spotbugs configs | Guarantees deterministic configuration resolution from both project root and submodule directories |
