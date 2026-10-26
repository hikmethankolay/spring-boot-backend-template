# Contributing

Thanks for helping improve the template. Bug reports, fixes and documentation improvements are welcome.
For larger changes, please open an issue first to agree on the approach.

## Development setup

You need JDK 25 and Docker or Podman: without a container runtime the integration tests fail rather than being
skipped.

```bash
scripts/setup.sh   # checks prerequisites, creates .env, compiles
scripts/dev.sh     # starts PostgreSQL, Redis and Mailpit, then the app
scripts/test.sh    # formats the code, runs all tests and checks coverage, SpotBugs and formatting
scripts/test.sh --unit   # unit tests only, a few seconds, no containers
```

The [Getting started](https://hikmethankolay.github.io/spring-boot-backend-template/guides/getting-started/)
and [Testing](https://hikmethankolay.github.io/spring-boot-backend-template/guides/testing/) guides
explain the details.

## Before opening a pull request

- `scripts/test.sh` passes. New behaviour comes with tests; integration tests extend `IntegrationTest` and are named `*IntegrationTest`.
- `scripts/docs.sh` passes: every public, protected and package-private class, method and field has a Javadoc comment.
- The architecture rules in `ArchitectureTest` still hold (controllers use services, no entities in the API).
- Configuration changes are reflected in `.env.example` and the Configuration guide (`docs/`).
- API changes are reflected in the committed `api/openapi.json`: regenerate it with
  `scripts/test.sh -Dit.test=OpenApiSpecExportIntegrationTest -Dopenapi.update=true` and review the diff.
- Diagrams are drawn in `docs/diagrams/*.py`: change them there and run `python3 docs/diagrams/diagrams.py`,
  which checks their layout and rewrites the light and dark SVGs in `docs/public/diagrams/`.
- Database changes are new Flyway migrations (`V<n>__description.sql`); never edit a released one.

## Commits and pull requests

- Keep commits small and focused, one logical change each.
- Write messages in the [Conventional Commits](https://www.conventionalcommits.org/) style used in
  the history: `feat(auth): ...`, `fix(user): ...`, `docs: ...`, `ci: ...`, `refactor: ...`.
- Describe what changed and why in the pull request, and link the issue it resolves.

## Security issues

Do not report vulnerabilities in public issues. See [SECURITY.md](SECURITY.md).

## License

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
