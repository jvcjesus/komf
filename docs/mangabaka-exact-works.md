# MangaBaka collection/work support (Phase 1)

This fork extends upstream KOMF's provider and existing Komga API updater. It has
no Mango runtime dependency. No new archive rewriting, filesystem mapping,
ComicInfo replacement, cover injection, or canonical backup machinery is added.
Upstream's existing COMIC_INFO mode still exists: do not enable it for this phase.

## Explicit collection selection

Configure MangaBaka in the existing provider configuration, preferably under the
specific test library's `metadataProviders.libraryProviders` entry:

```yaml
metadataProviders:
  defaultProviders:
    # Disable other providers as appropriate for the isolated test configuration.
    mangaBaka:
      enabled: false
  libraryProviders:
    YOUR_TEST_LIBRARY_ID:
      mangaBaka:
        enabled: true
        mode: API
        nameMatchingMode: EXACT
        collection:
          language: en
          edition: Standard Edition
          medium: digital
        bookMetadata:
          thumbnail: true
```

All three selectors must be nonempty. Matching is case-insensitive, otherwise
exact (including edition name); nothing treats Standard, Omnibus, Collector's,
paperback, and digital as interchangeable. Zero or multiple matching collections
fail closed before metadata is updated. Omit `collection` to retain upstream's
series-only behavior. DATABASE mode does not contain collection/work data;
collection selection in DATABASE mode reports unsupported rather than silently
falling back. These settings and book metadata options are also exposed through
the existing configuration DTO/patch API. No new browser UI is included.

Both collection and work lists follow pagination (maximum 100 pages), reject
cycles and cross-origin/cross-endpoint next links, and retain all fetched works.
The selected collection must be unique; work IDs and numeric work sequences must
be unique. Duplicate sequences fail
closed. Individual book lookup accepts only work IDs in the selected collection.
KOMF's existing filename/volume parser associates those provider books to local
books. No cover similarity is used to guess a work or edition.

`count_main` is the selected collection's main-book count, including zero. It is
never replaced by the parent series's `final_volume`; if absent the collection
count remains unknown. Extra works may be enumerated without changing that main
count. Book release date, description (string or `desc` object), ISBN identifier,
sequence, title/name when present, and work ID are mapped into existing KOMF
metadata models. Catalog `pages` remains in the MangaBaka work model; it is never
written as the archive's actual page count or used to validate local page counts.

## Exact work artwork

An image must explicitly reference the resolved work ID. Explicit foreign series,
language or non-volume image records are rejected. The raw image is preferred,
then scaled variants of that same work image. There is no series, paperback,
neighboring-volume or other-provider artwork fallback inside this provider.
JPEG, PNG and WebP are decoded with existing ImageIO support, bounded to 16 MiB
and 16 million pixels, with a 15-second download deadline. Missing/invalid artwork
or network failure yields no replacement, while metadata enrichment can continue.
The provider does not infer a different cover after a failed selected URL.

The resulting BookMetadata.thumbnail uses the existing Komga thumbnail upload
API. Uploads must succeed before a previous KOMF-managed thumbnail is removed.
Missing/disabled/empty replacements, refused/null uploads, and upload exceptions
preserve the existing thumbnail and its tracked ID. The same preservation rule
also applies to series thumbnails. Explicit reset operations retain their removal
semantics. Existing cover-selection policy (`overrideExistingCovers`) is honored.
This is not a transaction spanning all book metadata and thumbnail API calls.

## Test deployment boundaries

Use a separate configuration/data directory, bind to loopback, and disable both
Komga and Kavita event listeners. Restrict library providers to the test library;
event library filters do not restrict manual mutation endpoints. For Phase 1 use
`updateModes: [API]` only. No canonical media mount is necessary for API mode.
Use a separate/test Komga or credentials without mutation permission for read-only
evaluation. Do not call identify/match/reset endpoints against production: they
perform writes immediately. A preview/approval workflow is not part of Phase 1.
Do not run library matching. Merely disabling cover flags does not make metadata
updates read-only. Configuration examples here are instructions, not a deployment.

## Regression fixture and checks

`komf-core/src/jvmTest/resources/mangabaka/` contains a reduced snapshot of the
user-supplied read-only API output for Goodnight Punpun (series 1357). Collection
and work identities, `count_main`, dates, ISBNs, descriptions, pages and work image
references are captured values. Series fields absent from the diagnostic output
have neutral synthetic values solely to satisfy upstream's complete series model;
no conclusions rely on them. Image payload tests use tiny generated PNG bytes,
not external HTTP requests. Paperback work images are empty in the snapshot.

The source series reports 13 volumes. English paperback and digital collections
both report seven main books. Tests select digital collection
019e1d1b-b52d-7a5e-b609-18f87987c7a4 and verify every v01–v07 work ID from the supplied
fixture, plus exact artwork URL selection. Production code contains none of these
fixture IDs or titles. Generic ambiguity, absent/zero count, foreign work image,
missing image, disabled image, invalid image, network failure, pagination, unsafe
pagination, upload failure, cancellation and successful upload ordering are tested.

```sh
./gradlew :komf-core:jvmTest :komf-mediaserver:jvmTest :komf-app:test
./gradlew :komf-app:shadowJar
```

These deterministic tests do not establish live Komga version/permission support,
local edition authenticity, or current remote image availability. Production
metadata/covers and canonical files must remain untouched until separately
approved acceptance.

Validation completed for this implementation: 8 provider/API tests, 5 thumbnail
replacement tests and 2 application configuration tests passed (15 total, zero
failures). The combined command also compiled the client and API models and built
the application shadow JAR:

```sh
./gradlew :komf-core:jvmTest :komf-mediaserver:jvmTest :komf-app:test \
  :komf-client:jvmTest :komf-api-models:jvmTest :komf-notifications:jvmTest \
  :komf-app:shadowJar
```

The client, API-model and notification test targets have no test sources. Android
and Wasm targets were not tested. Existing upstream deprecation warnings and an
Android analytics-directory warning remain. No live library/API mutation or
server deployment was performed.

For an explicit one-book API operation, see [book identification](book-identification.md).
