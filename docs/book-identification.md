# Identify exactly one book

`POST /api/komga/metadata/identify/book` identifies and updates exactly one Komga
book through the existing media-server API adapter. Only the Komga adapter opts
into the required single-book capability. Kavita is refused because its existing
book-thumbnail adapter actually uploads a volume cover shared by chapters.
No canonical media mount is needed.

```json
{
  "bookId": "YOUR_KOMGA_BOOK_ID",
  "provider": "MANGA_BAKA",
  "providerSeriesId": "1357",
  "libraryId": "YOUR_KOMGA_LIBRARY_ID"
}
```

`bookId` is one required scalar ID, not a list. `provider` and
`providerSeriesId` explicitly identify the metadata provider's series. The
optional `libraryId` asserts membership; it cannot redirect the operation to a
different library's configuration. The containing series/library are loaded from
the actual target book. The client SDK exposes `KomfMetadataClient.identifyBook`.

The response follows existing metadata-job conventions:

```json
{"jobId":"JOB_UUID"}
```

Use the existing job status/event APIs to determine completion or failure.
Receiving a job ID is not proof that metadata or a cover was applied. The existing
job-history schema retains the containing series ID as context; no series manual
provider match is saved or changed by this operation. Application logs identify
the target book and resolved provider work ID.

## Mutation boundary

The adapter must explicitly guarantee book-only metadata and thumbnail writes.
The selected library must have `updateModes: [API]` exactly. Empty, COMIC_INFO,
and combined API/COMIC_INFO configurations are rejected before a job is started.
The new action never calls the series-wide updater, series metadata/thumbnail
writes, sibling-book enumeration, scans/refreshes, library matching, or automatic
matching. It never opens a canonical archive, writes ComicInfo, injects a cover,
or invokes an optimizer. Existing series identify/match actions remain unchanged
and must not be used as a substitute for this endpoint.

The service loads the target book and its containing series/library, verifies
identities and deletion state, and selects providers using that actual library.
Immediately before writing it re-reads only the target and its series, refuses
moves/renames/number changes, and uses current metadata locks for mapping. The
updater maps metadata and optionally uploads/replaces only that book's thumbnail.

This path reuses existing provider series identification, book fetching,
aggregation, post-processing, metadata mapping, media-server adapter, thumbnail
policy, thumbnail ownership tracking, and metadata job machinery. Shared helpers
have an opt-in strict work-association mode; the existing series path retains its
original permissive association semantics. The single-book post-processor reuses
field/ordering processing without the series-oriented oneshot transformation,
which would otherwise drop exact work fields and borrow series artwork.

## Exact work identification

The local filename must yield an exact volume/book/chapter number using KOMF's
existing library-type parser. Exactly one provider book in the applicable edition
must have that same range. Missing numbers, zero matches, or duplicate plausible
matches fail before mutation. A provider containing one work does not justify
matching an unrelated local volume. Returned provider IDs and sequences, when
present, are checked against the selected work.

For MangaBaka, configure the existing collection target with explicit language,
edition and medium; see [exact-work configuration](mangabaka-exact-works.md).
The operation uses the same collection resolver and work images as the series
path. Missing/ambiguous collections or duplicate sequences fail closed. No
cross-medium/edition fallback or series-cover substitution is introduced.

For the supplied Goodnight Punpun fixture, series 1357 selects English Standard
Edition/digital collection 019e1d1b-b52d-7a5e-b609-18f87987c7a4 with `count_main=7`.
v01 resolves to work 019e1d1b-b538-708a-945d-09649968641f and its image reference.
The provider's selected count stays seven rather than parent `final_volume=13`.
The containing Komga series count is intentionally not changed by a book-only
operation. Catalog page count is not written as the archive's actual page count.
Fixture identities appear only in documentation/tests, not production logic.

Configured aggregation still uses the existing provider search/merge policy,
but all secondary provider lookups receive only the target book and strict work
association. Secondary providers that return a series match without an exact
numbered book cause a safe job failure, not a sibling/series fallback. The
explicit primary provider remains the sole artwork authority: a missing/failed
primary cover is never substituted with another provider's thumbnail.

For isolated MangaBaka validation, enable only MangaBaka, disable aggregation and
both event listeners, and use `seriesMetadata.thumbnail: false` plus
`bookMetadata.thumbnail: true`. This avoids fetching an unused series cover.
`bookCovers` and `overrideExistingCovers` retain their existing upload/selection
meaning. No automatic processing is enabled by adding this action.

## Failure and thumbnail semantics

Identification failures leave target metadata/covers untouched and fail the job;
preflight argument/configuration errors return HTTP 400. Unavailable target or
containing resources fail through the existing media-server error handling.
No failure broadens the operation to other books. Missing, disabled, invalid,
failed-download, or failed-upload artwork preserves the existing cover using
Phase 1's shared thumbnail replacement helper. Metadata can succeed while artwork
is skipped; verify thumbnail state separately during live acceptance. A successful
upload precedes removal of a previous KOMF-managed cover.

The metadata patch and thumbnail upload are separate API requests, not an atomic
transaction. Revalidation cannot eliminate a concurrent server-side move after
the last read. Use an isolated test with other writers disabled for acceptance.
Do not assume job completion guarantees a thumbnail was uploaded.

## Preview and validation

No preview/dry-run API is added. Existing jobs report progress, not a serializable
proposal/diff. A next phase can factor the resolution portion of
`MetadataService.setBookMetadata` into a read-only result, expose a proposal DTO
with work provenance and proposed metadata/artwork, then revalidate that result
before applying. That requires an explicit API contract beyond this action.

Deterministic tests use three local book fixtures, actual MangaBaka work fixtures,
a mocked artwork HTTP client, a recording media-server implementation and the
existing SQLite job/thumbnail/match repositories. They reject any series mutation,
series-thumbnail access, sibling enumeration, or refresh during book tests, and
check target-only API writes and unchanged canonical fixture bytes. They also
cover ambiguous collections, missing/duplicate works, failed download/upload,
disabled covers, changed/deleted/mismatched contexts, API-only enforcement,
oneshot field preservation, and unchanged series-scoped behavior. Request/client
tests cover the explicit scalar-book request and dedicated endpoint.

No server deployment, production metadata/cover writes or live compatibility
claim is implied by these tests. After packaging, this endpoint is suitable for
the previously planned isolated v01-only live validation; inspect its job result,
book metadata/thumbnail state, sibling state and canonical fingerprints before
and after that separately authorized test.

Validation for this change: 25 tests passed with zero failures (8 MangaBaka,
5 shared thumbnail replacement, 7 book/series service isolation, 4 application
configuration/request, 1 SDK request). Ten tests are new in this change. All JVM
test targets and the application shadow JAR task succeeded:

```sh
./gradlew :komf-core:jvmTest :komf-mediaserver:jvmTest :komf-app:test \
  :komf-client:jvmTest :komf-api-models:jvmTest :komf-notifications:jvmTest \
  :komf-app:shadowJar
```

API-model and notification modules have no test sources. Android/Wasm were not
validated. Service fixtures use temporary WAL SQLite databases and the real
repository/job machinery; no live Komga or MangaBaka calls are made by the tests.
