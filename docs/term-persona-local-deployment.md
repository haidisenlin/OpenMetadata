# Local 2.0.2 deployment

The feature is published on `feat/term-persona-context`, based on the fork's
`a952d034` main branch. That main branch combines incompatible source versions
and does not currently compile as a full distribution.

The local deployment branch, `deploy/term-persona-2.0.2`, applies the same feature
to the complete `d7aa16d7` source backup (2.0.2 plus the existing RecordBinding
customizations). This matches the running local service and avoids a database
version change. Existing visibility guards and unrelated 2.0.2 functionality are
preserved. Generated glossary TypeScript models are regenerated from this
branch's schemas.

## Build verification

- Full service and MCP reactor package build: passed.
- Relevant tests in the real reactor: 31 service tests and 126 MCP tests passed.
- Five UI test suites: 49 tests passed.
- Scoped Java Spotless check: passed.
- Full UI production and distribution builds: passed.
- Three browser scenarios (save/reload/clear, read-only access, failed-save recovery): passed.

The distribution assembly descriptor is corrected to read `conf`, `bin`,
`bootstrap`, and `README.md` from the repository root. Its previous
`${project.basedir}./...` paths do not exist.

## Build inputs

- Java 22 targeting Java 21.
- Node 22.22.1 and Yarn 1.22.22, using the checked-in lockfiles.
- UI core components built before the application.
- ANTLR JavaScript and connection schemas generated before the UI build.
- A fresh UI production build must exist before packaging with the frontend
  plugin's skip flags; those flags are only used to avoid building it twice.

## Service replacement

The original service is the `openmetadata_server` container in the
`docker-compose-quickstart` Compose project. Its old image is
`openmetadata-server:2.0.2-record-bindings` (image ID starts `a9de0251098c`).
Keep this image for rollback. Replace only `openmetadata-server` using
`docker compose up -d --no-deps openmetadata-server`; do not remove volumes or
restart the database, Elasticsearch, or ingestion containers.

The term binding fields are optional JSON fields and do not require SQL
migrations. After replacement, verify the server version/revision, health check,
REST persistence, MCP context selection and the glossary AI context UI.

## MCP request cache isolation

Live REST-to-MCP validation exposed that MCP worker threads do not pass through
the JAX-RS request cache filters. Clear `RequestEntityCache` before each tool
execution and in `finally` so edits to term bindings take effect on subsequent
calls. A regression test seeds stale entity data and covers both successful and
failed tool executions.

## Publication

The feature branch is published to the requested fork. Pushing the separate
2.0.2 deployment history was rejected because the current GitHub OAuth token
lacks `workflow` scope and that history contains different workflow files.
Keep the deployment branch locally; no workflow files were changed to bypass
that restriction.
