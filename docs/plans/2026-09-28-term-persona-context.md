# Term and user scoped Persona context

Status: implemented on 2026-09-28; focused verification passed. Full application
build and deployed integration/E2E verification remain blocked by the existing
workspace baseline described below. The running service has not been deployed.

## Behavior

Glossary maintainers configure an optional default `contextPersona` and optional
`contextPersonaOverrides` entries containing `user` and `persona` references.
Each user can occur only once. Multiple terms and users may reuse a Persona.
Bindings select context; they do not change an account's active/default Persona
or grant access to a Persona.

MCP selects the override matching the authenticated subject's UUID, otherwise
the term default. With no binding it leaves existing query behavior unchanged.
An inaccessible, deleted, or disabled selected Persona is reported as unavailable;
it does not silently select a different Persona. Returned MCP data never exposes
other users' override entries.

The built-in MCP enriches explicit glossary term matches in metadata/semantic
search, entity details, context discovery, term graphs and record-binding tools.
Only structured, authorized term references are eligible. It does not infer
terms from arbitrary descriptions, compressed tag strings or RDF text.
The independent sibling Python ontology MCP is outside this built-in tool change.

Persona documents reuse the existing authorization, materialization cache and
deterministic pagination. Duplicate Personas are loaded once per response.
The combined response stays within the existing 100,000-character ceiling;
when a complete first part cannot fit, it returns a deferred status and exact
`get_persona_context` arguments. Further parts retain `hasMore` and fingerprint
metadata. A client must follow continuation calls to retrieve a large document.

## Implementation and verification

1. Add fields in `openmetadata-spec/src/main/resources/json/schema/entity/data/glossaryTerm.json`
   and `api/data/createGlossaryTerm.json`, with the shared type in
   `type/termContextPersonaOverride.json`. Regenerate Java, Python and affected
   TypeScript models rather than editing generated models manually.
2. Extend `openmetadata-service/src/main/java/org/openmetadata/service/resources/glossary/GlossaryTermMapper.java`
   and `jdbi3/GlossaryTermRepository.java`: validate typed, non-deleted UUID
   references, reject duplicate users, persist optional fields in existing JSON,
   and record changes through the existing PATCH/PUT/versioning path.
   No SQL column or relationship migration is needed for these optional JSON fields.
3. Add pure binding selection and validation tests under `openmetadata-service/src/test/`;
   add CRUD/validation/authorization coverage in the glossary integration tests.
   Verify with focused Maven tests and schema compilation.
4. Extract a shared MCP Persona reader and add bounded, authorized enrichment
   after successful read tools in `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/`.
   Preserve structured matches for markdown context discovery and explicit entity
   types for details. Update `tools.json` to accept Persona names from binding results.
   Test selection, missing/denied context, deduplication, fixed-path extraction,
   privacy, pagination and combined JSON response budgets.
5. Add an AI context binding editor to the glossary term details UI, using existing
   Persona/user selectors and `onUpdate` JSON Patch. Permit editing only with
   `EditAll` and outside version views. Test defaults, overrides, duplicate user
   validation, clearing, persistence and read-only/error states using Jest and Playwright.
6. Run scoped Spotless, UI lint/type checks, focused backend/frontend tests and
   available integration/E2E checks. Apply the project code-review skill and
   distinguish verified results from any environment-blocked checks.

## Environment baseline

Before implementation, the workspace has extensive unrelated modifications.
The relevant glossary and MCP source files are initially clean except
`SparqlResultSet.java`, which is not part of this feature.
Offline baseline Maven testing is blocked by a missing installed local
`openmetadata-service:2.0.0-SNAPSHOT` artifact. The default Maven mirror is a private
server; temporary settings can isolate the build without changing user settings.
The project Python venv and generated Python models are initially absent.
UI node_modules exist. The default shell and setup script discover different Java
versions, so verification must explicitly select a compatible JDK.

## Implementation notes

- The UI entry is **Glossary term details → AI context**, with optional default
  Persona and up to 100 unique user overrides. Save failures keep the user's draft.
- Stable term UUIDs are retained in search/context results and used for both
  authorization and entity reads. With no UUID, both operations use the FQN.
  A stale search name cannot select a different term after a rename/reused name.
- A required adjacent fix replaces a call to the nonexistent
  `CommonUtils.readEntityForCaller` in `FindRecordRelatedAssetsTool` with the
  existing authorized entity read pattern. Tests verify authorization precedes
  catalog access. No unrelated worktree changes were reverted.
- Configuration and rollout guidance is in [term-persona-context.md](../term-persona-context.md).

## Verification evidence

All temporary verification files below are local execution artifacts, not build
configuration changes committed to the project.

| Check | Result | Evidence |
| --- | --- | --- |
| Project Python model generation | Passed `make generate`, generated imports and request/entity round trip | `/tmp/openmetadata-persona-model-generation.log` |
| Java schema generation and compilation | Passed in reactor; current spec jar used for focused compilation | `/tmp/openmetadata-persona-reactor-package.log` |
| TypeScript models | Regenerated the three affected files with repository quicktype schema preprocessing | `/tmp/openmetadata-persona-generate-types.cjs` |
| Service repository, mapper and binding helper | Compiled current source successfully | `/tmp/openmetadata-persona-backend-compile.log` |
| API integration tests | All 10 tests compiled with current SDK and IT support; not executed against a service | `/tmp/openmetadata-persona-backend-it-compile.log` |
| Java/MCP focused regressions | 153 passed, 0 failures/errors/skipped, including real Persona cache/materialization success | `/tmp/openmetadata-persona-green.log` |
| Java formatting | Scoped Spotless passed | `/tmp/openmetadata-persona-backend-spotless.log`, `/tmp/openmetadata-persona-finder-spotless.log`, `/tmp/openmetadata-persona-mcp-spotless.log` |
| UI regressions | 49 tests passed across five suites; new UI line coverage 97.93% | `/tmp/term-ui-yarn-tests.log`, `/tmp/term-ui-coverage/` |
| New UI and E2E lint/format | Scoped ESLint clean; scoped project Prettier applied | `/tmp/term-ui-lint-clean.log`, `/tmp/term-ui-prettier.log` |
| Feature TypeScript diagnostics | No errors reported for feature files by the real compiler | `/tmp/term-ui-owned-types.log` |
| Independent review | Fixed stale FQN/reused-name issue; no remaining blocking findings | UUID selection and source-preservation regression tests |

The focused Java harness at `/tmp/openmetadata-persona-check` compiles unchanged
copies of the current feature source, current generated spec, and required source
dependencies against the locally cached OpenMetadata 2.0.2 dependencies. It uses
no substitute implementations. This provides compilation and unit-regression
evidence, **not** a successful full build or deployed API/E2E result. The privacy
regression was first observed failing against the prior implementation; its
passing result is included in the final focused test run.

Final focused command (JDK 22, target release 21, local temporary Maven settings):

```text
mvn -o -gs /tmp/openmetadata-persona-maven-settings.xml \
  -s /tmp/openmetadata-persona-maven-settings.xml \
  -f /tmp/openmetadata-persona-check/pom.xml '-Dtest=*Test' test
Tests run: 153, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

JaCoCo 0.8.13 line coverage for the new production classes:

| Class | Line coverage |
| --- | --- |
| `GlossaryTermPersonaBindings` | 97.56% |
| `TermPersonaContextEnricher` | 93.75% |
| `TermPersonaContextEnricher.CatalogAccess` | 94.44% |
| `PersonaContextReader` | 96.72% |
| `McpTermReferences` | 97.33% |
| `McpPersonaPrivacy` | 100% |

Reports: `/tmp/openmetadata-persona-check/target/jacoco.xml` and
`/tmp/openmetadata-persona-check/target/coverage/index.html`.

Remaining validation limits:

- Full Maven reactor compilation fails on existing missing `FeedRepository` and
  `org.openmetadata.service.rdf.rebuild` sources. See
  `/tmp/openmetadata-persona-reactor-package.log`.
- Full UI type checking fails on existing workspace errors. New feature issues
  discovered during that check were fixed and rechecked separately.
- Three new Playwright scenarios were added. Execution is blocked before test
  discovery by the existing missing `playwright/utils/glossaryPicker` module;
  no live E2E success is claimed. See `/tmp/term-persona-playwright.log`.
- Full Playwright lint has no errors, but exits on stale entries in the existing
  suppression ledger; it was left unchanged. The new spec passes scoped lint.
- Coverage of repository persistence/permission adapters requires the added
  deployed integration tests. Focused helper coverage is not whole-service
  coverage.
