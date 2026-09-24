# Reliable yearly percentage returns

**Goal:** Replace LLM-researched `yearlyPercentageReturns` with a deterministic, testable workflow that calculates conventional calendar-year adjusted-close returns (prior-year final close to period-end close) without adding a paid market-data API.

## Context

- The implementation repository is `/home/karl/git/investment`; this planning document intentionally lives in `/home/karl/git/dev-lake-utils/plans` as requested.
- `StockDetailRefreshService` currently invokes `StockYearlyReturnsUpdater` before the other report refresh work (`/home/karl/git/investment/alphascout/src/commonMain/kotlin/com/nimblygames/investment/stock/StockDetailRefreshService.kt`).
- `StockYearlyReturnsUpdater` creates rows for the current calendar year plus four prior years. It refreshes the current-year row and fills missing or malformed historical values, but delegates each row independently to `YearlyPercentageReturnResearcher` (`.../stock/StockYearlyReturnsUpdater.kt`).
- The production researcher is `LlmYearlyPercentageReturnResearcher`. It asks an LLM to call Yahoo's chart endpoint, perform arithmetic, and return one terse value (`.../stock/YearlyPercentageReturnResearcher.kt` and `.../stock/YearlyPercentageReturnPrompt.kt`). This is avoidable dynamic behavior around deterministic data retrieval and arithmetic.
- The output contract currently says to use adjusted close, the first trading day on or after the range start, and the last completed trading day on or before the range end (`.../information/StockDetail.kt` and `.../information/YearlyPercentageReturn.kt`). **Decision:** replace that definition with conventional calendar-year return: last completed trading day's adjusted close *within the immediately preceding calendar year* to last completed trading day's adjusted close on/before the range end. Update documentation/prompts accordingly; old stored figures must not be treated as equivalent.
- The refresh path currently passes only a ticker into the yearly-return updater even though `StockResearchSubject` contains ticker, exchange, company name, and stock type (`.../stock/StockResearchSubject.kt`). Exchange identity is needed for symbols such as Toronto-listed `VCE` (`VCE.TO` at Yahoo) and share classes such as `BRK.B` (`BRK-B` at Yahoo).
- The present universe includes NASDAQ, NYSE, NYSE Arca, Cboe BZX, OTC, and TSE entries, including `BRK.B`, `VCE`, and `VCN` (`/home/karl/git/investment/notebook/stocks-to-consider.json`). At planning time, anonymous Yahoo chart requests returned daily adjusted-close arrays for `MSFT`, `VCE.TO`, and `BRK-B`; that probe is evidence, not a supported API guarantee.
- News research established useful boundaries to reuse: a workflow separate from report orchestration, a narrow provider adapter, typed transport/authentication/throttling/malformed-response failures, file caching, fixture-backed HTTP tests, and a live reliability fitness test (`.../stock/research/news/`). Returns should follow those architectural boundaries without copying news-specific types.
- **Decisions:** Anonymous Yahoo chart access is acceptable if failures are explicit and the adapter replaceable; failed current-year fetch aborts refresh without changing the stored report; explicit refresh recalculates historical values; initial scope is the concrete US/OTC and TSE universe, failing closed for unknown exchange conventions. On the first deterministic refresh, recalculate stored historical returns despite apparently valid numeric values because they lack provenance and may reflect the old definition; leave the report unchanged if that refresh fails. A stock without a close in the preceding calendar year gets `N/A` rather than a since-IPO return. These decisions were confirmed in this plan's Open Questions.
- Every implementation PR in `/home/karl/git/investment` must pass `./gradlew clean build` from that repository root (`/home/karl/git/investment/AGENTS.md`).

## Recommended approach

Use Yahoo Finance's chart JSON endpoint as the sole price-data source in the first release, but call it directly from Kotlin rather than through an LLM or browser:

1. Introduce a `YearlyReturnsWorkflow` that accepts a full `StockResearchSubject` and all requested date ranges, resolves the Yahoo symbol once, fetches enough daily history to include the previous year's final trading close for the oldest requested year, validates it, and calculates returns locally. On migration, recompute previously stored historical figures derived under the old definition rather than silently trusting numeric strings with unknown provenance.
2. Put Yahoo HTTP and JSON details behind a `YahooChartPriceHistorySource`. Decode timestamps and `adjclose` together, reject length mismatches and non-finite/non-positive values, and never substitute unadjusted `close` when adjusted close is absent.
3. Resolve known exchange syntax deterministically before fetching: preserve ordinary US/OTC symbols, map US share-class dots to Yahoo hyphens, and add `.TO` for TSE symbols. Validate Yahoo response metadata against the requested/resolved symbol. Do not use Brave snippets to guess prices or perform arithmetic.
4. Fetch a single window broad enough for all five rows and the oldest year's prior close. For year `Y`, the application layer selects the last valid adjusted close **in calendar year `Y-1`** and the last valid completed daily bar on/before the requested end, then calculates `(end / baseline - 1) * 100` with stable two-decimal formatting. No previous-calendar-year close means `N/A`, even if the instrument listed during `Y`.
5. Treat the current daily bar as usable only after Yahoo's regular-session close metadata says the session is complete; otherwise select the prior completed bar. Inject clock/session decisions so tests do not depend on wall time.
6. Cache validated raw daily observations and provider metadata with explicit coverage, including the prior-year baseline. Normal fetches may reuse completed historical years while fetching fresh current-year history; avoid coupling historical cache availability to a stale or unavailable current-year response. Historical cache entries are reusable until explicit refresh, unless invalidated by a calculation-policy version change; current-year data gets a short freshness policy and is never silently presented as fresh after expiry.
7. Distinguish definitive absence from operational failure. A range without a pre-year baseline or a usable end observation becomes `N/A`; transport, throttling, authentication, and malformed payload failures fail the refresh without overwriting an existing value with `N/A`.
8. Remove the production LLM yearly-return path after the deterministic workflow is wired and old values can be migrated. Keep each return row's JSON shape unchanged; a top-level calculation-definition version may be added for provenance.

### Why not Brave Search or the Yahoo account first?

Brave is useful for discovering documents, not for authoritative daily adjusted-close series. Search snippets cannot reliably prove symbol identity, corporate-action adjustment, exact boundary dates, or numerical precision. Using Brave as a numeric fallback would increase apparent availability by risking silent wrong answers.

A personal Yahoo login is also not a sound first dependency. **A Yahoo Finance account is not an API key**: I could not identify a documented Yahoo-issued personal key for historical adjusted-close chart access. Yahoo developer keys for unrelated services and third-party products branded “Yahoo Finance API” should not be mistaken for one. The anonymous chart endpoint worked in a small local probe, but access/terms and reliability can change. Browser login adds credential, MFA, captcha, and session-expiry failure modes without a supported data contract. Start with anonymous chart access plus caching, bounded retry, and explicit failures. If anonymous access is later the measured dominant failure mode, investigate a supported provider or an optional cookie/crumb adapter separately; do not put credentials in prompts or logs.

This approach trades provider support/SLA for zero new API cost. It can be made robust against bad data and transient failure, but it cannot honestly guarantee Yahoo availability. A supported paid market-data provider remains the clean fallback if availability requirements later exceed what caching and explicit retry can deliver.

## Acceptance tests

Each numbered acceptance test maps to one story, ticket, and PR. The migration and first-year `N/A` behaviors are confirmed.

1. **Calculate a US return without an LLM**
   - Given `MSFT` has no 2024 return and Yahoo supplies validated adjusted closes of `100.00` on the final 2023 trading day and `120.00` on the final 2024 trading day, when its report is refreshed, then the 2024 row shows `20.00%` instead of an LLM-generated return.

2. **Use actual trading days at closed historical boundaries**
   - Given a 2024 row whose 2023 year-end and 2024 year-end both fall on non-trading days and validated adjusted closes on the preceding trading days are `100.00` and `110.00`, when the return is calculated, then the 2024 row shows `10.00%`.

3. **Exclude an incomplete current-session bar**
   - Given today's Yahoo daily bar exists while the exchange's regular session is still open, when the current-year return is calculated, then it uses the preceding completed trading day's adjusted close.

4. **Resolve a US share-class symbol**
   - Given the NYSE entry `BRK.B`, when yearly returns are refreshed, then the workflow requests and validates Yahoo symbol `BRK-B` and populates the return for `BRK.B`.

5. **Resolve a Toronto-listed symbol**
   - Given the TSE entry `VCE`, when yearly returns are refreshed, then the workflow requests and validates Yahoo symbol `VCE.TO` and populates the return for `VCE`.

6. **Represent genuine missing baseline history**
   - Given `FIG` first traded during 2025 and has no adjusted close before `2025-01-01`, when the 2025 yearly return is refreshed successfully, then the 2025 row shows `N/A` rather than a since-listing return.

7. **Do not turn provider failure into data**
   - Given a stored `MSFT` report and Yahoo returns HTTP 429 during refresh, when bounded retry is exhausted, then the refresh fails with a classified throttling error and the stored report remains unchanged.

8. **Reuse validated completed-year history**
   - Given cached validated 2023/2024 `MSFT` closes and a successful fresh current-year Yahoo fetch, when the report is refreshed normally, then the 2024 return is computed from the historical cache without requesting that historical window again.

9. **Refresh cached history explicitly**
   - Given cached 2024 `MSFT` observations produced `20.00%` and Yahoo now returns corrected adjusted closes producing `21.00%`, when an explicit refresh is requested, then the 2024 row shows `21.00%`.

10. **Replace old-definition stored historical figures**
   - Given a stored `MSFT` 2024 row shows `18.00%` from the first-trading-day convention and validated Yahoo prices yield `20.00%` from the prior-year close, when the first deterministic refresh succeeds, then the 2024 row shows `20.00%`.

## Sequence and trade-offs

1. Start with the direct Yahoo tracer bullet for an ordinary US ticker and one batched history request that includes the oldest year's prior-year close.
2. Prove conventional year-boundary calculation separately from transport.
3. Protect current-year correctness by excluding incomplete daily bars.
4. Add explicit symbol policies for US share classes and TSE listings.
5. Distinguish genuine no-baseline history from provider failures.
6. Recompute old-definition stored rows after source/error semantics are safe; don't let cached old numeric values masquerade as migrated ones.
7. Add caching only after the validated source and failure semantics are stable.
8. Add explicit refresh last because it modifies cache policy rather than the core calculation.

The sequence optimizes for correctness before availability. It deliberately avoids a Brave numeric fallback: returning no new report is safer and diagnosable; returning a plausible percentage for the wrong listing or adjustment basis is not.

## Stories

### 1. Calculate missing US yearly returns through a direct Yahoo workflow ✅

**Acceptance criteria:** Given `MSFT` has no 2024 return and Yahoo supplies validated adjusted closes of `100.00` on the final 2023 trading day and `120.00` on the final 2024 trading day, when its report is refreshed, then the 2024 row shows `20.00%` instead of an LLM-generated return.

**Expected edits:** Add return-specific domain/application/adapter files under `/home/karl/git/investment/alphascout/src/commonMain/kotlin/com/nimblygames/investment/stock/research/returns/`, including `YearlyReturnsWorkflow`, a price-history port, calculation types, and `YahooChartPriceHistorySource`; update `StockYearlyReturnsUpdater.kt` to pass `StockResearchSubject` and request rows as a batch; provide an opt-in/injected path through `StockDetailRefreshService.kt`, `StockReportService.kt`, `AlphaScoutComponent.kt`, and construction in `AlphaScout.kt`/`AlphaScoutProvider.kt`; add fixture-backed tests under matching `commonTest` and `jvmTest` packages.

**Scope:** One anonymous Yahoo chart request for an ordinary US ticker, including the preceding year's final close, strict adjusted-close decoding, local calculation, two-decimal formatting, and opt-in production-path wiring until existing reports can be migrated in Story 10. Preserve the five-row output and report JSON schema. Exclude non-trading boundary edge cases, symbol translation, caching, and retries. Exclude an uncompleted current-day bar conservatively from day one; Story 3 refines the session-completion policy.

**Notes:** Fetch enough history for all requested rows, including the prior close of the oldest year, even though the assertion names one row. For 2024, the baseline is the final trading close before `2024-01-01`, not the first 2024 close. Keep HTTP details out of `StockYearlyReturnsUpdater`. Inject the workflow so unit tests prove the old LLM researcher is not called. Do not fall back from `adjclose` to `close`. Until migration in Story 10, existing numeric historical rows may still have the old semantics; don't describe the entire report as migrated.

### 2. Select historical trading-day boundaries deterministically ✅

**Acceptance criteria:** Given a 2024 row whose 2023 year-end and 2024 year-end both fall on non-trading days and validated adjusted closes on the preceding trading days are `100.00` and `110.00`, when the return is calculated, then the 2024 row shows `10.00%`.

**Expected edits:** Calculation and date-selection code under `.../stock/research/returns/application/`; domain observation types under `.../returns/domain/`; focused tests with weekends, holidays, null adjusted-close slots, and provider timezone metadata under `alphascout/src/commonTest/.../returns/`.

**Scope:** Historical closed ranges only. Excludes current-session completion and HTTP behavior.

**Notes:** Pair timestamps and adjusted-close slots by index before dropping nulls. Reject mismatched array lengths rather than shifting values. Require a final valid observation within the immediately preceding calendar year and a completed valid observation on/before the end; don't use a post-January-1 bar or a close from years earlier as baseline.

### 3. Exclude incomplete current-day prices ✅

**Acceptance criteria:** Given today's Yahoo daily bar exists while the exchange's regular session is still open, when the current-year return is calculated, then it uses the preceding completed trading day's adjusted close.

**Expected edits:** Yahoo metadata decoding in `.../returns/adapter/YahooChartPriceHistorySource.kt`; a clock/session-completion policy in `.../returns/application/`; adapter and calculation tests under `commonTest`/`jvmTest`; current-year behavior in `StockYearlyReturnsUpdaterTest.kt`.

**Scope:** Current range endpoint selection only. Excludes extended-hours returns and intraday calculation.

**Notes:** Base completion on provider regular-session metadata and an injected clock, not the machine's local timezone. If completion cannot be established safely, exclude today's bar.

### 4. Resolve Yahoo symbols for US share classes ✅

**Acceptance criteria:** Given the NYSE entry `BRK.B`, when yearly returns are refreshed, then the workflow requests and validates Yahoo symbol `BRK-B` and populates the return for `BRK.B`.

**Expected edits:** Add a symbol resolver under `.../stock/research/returns/adapter/`; pass the full `StockResearchSubject` through `StockYearlyReturnsUpdater.kt`; add resolver, request, metadata-validation, and end-to-end updater tests.

**Scope:** Dot-to-hyphen class notation for the supported US exchanges. Excludes fuzzy search and unknown exchange conventions.

**Notes:** Keep the report's canonical ticker as `BRK.B`; the Yahoo symbol is adapter-local identity. Reject response metadata for a different symbol.

### 5. Resolve Yahoo symbols for Toronto listings ✅

**Acceptance criteria:** Given the TSE entry `VCE`, when yearly returns are refreshed, then the workflow requests and validates Yahoo symbol `VCE.TO` and populates the return for `VCE`.

**Expected edits:** Extend the symbol resolver under `.../returns/adapter/`; add TSE cases for `VCE` and `VCN` in resolver/adapter tests and one refresh-path acceptance test.

**Scope:** TSE-to-`.TO` mapping for the current universe. Excludes speculative mappings for exchanges with no representative entry.

**Notes:** The exchange field, not company-name matching or Brave search, owns this decision. Normalize known aliases deliberately; malformed `NAQ` data should be corrected or treated through an explicit alias rather than broad fuzzy logic.

### 6. Separate unavailable history from Yahoo failures ✅

**Acceptance criteria:** Given `FIG` first traded during 2025 and has no adjusted close before `2025-01-01`, when the 2025 yearly return is refreshed successfully, then the 2025 row shows `N/A` rather than a since-listing return.

**Expected edits:** Result types in `.../returns/domain/`; range evaluation in `.../returns/application/`; update `StockYearlyReturnsUpdater.kt` and tests for a newly listed instrument; retire or narrow `normalizeYearlyPercentageReturnAnswer`/`shouldResearchYearlyPercentageReturn` usage in `YearlyPercentageReturnResearcher.kt`.

**Scope:** Successful provider responses with genuinely missing prior-year baseline history. Excludes HTTP/provider failures.

**Notes:** `N/A` is data, not an error sentinel. An adjusted-close series without a pre-year baseline cannot produce a conventional calendar-year return. Never substitute unadjusted close.

### 7. Fail safely on unusable Yahoo responses

**Acceptance criteria:** Given a stored `MSFT` report and Yahoo returns HTTP 429 during refresh, when bounded retry is exhausted, then the refresh fails with a classified throttling error and the stored report remains unchanged.

**Expected edits:** Typed exceptions and HTTP classification under `.../returns/adapter/`; response validation in `YahooChartPriceHistorySource.kt`; orchestration/error tests in `StockDetailRefreshService`/`StockDetailGenerator` tests; keep the LLM path available for old reports until the migration story switches production fully.

**Scope:** Cancellation-safe error classification, bounded retry for transient statuses, and no partial report persistence. Excludes fallback providers and account login.

**Notes:** Preserve coroutine cancellation. Bound retries using `Retry-After` where available and jittered backoff otherwise. Add separate fixture/unit cases for 401, 403, 5xx, invalid JSON, chart errors, misaligned arrays, and cancellation; none become `N/A`. Those cases support the same user-visible invariant rather than becoming separate tickets.

### 8. Reuse validated completed-year price history

**Acceptance criteria:** Given cached validated 2023/2024 `MSFT` closes and a successful fresh current-year Yahoo fetch, when the report is refreshed normally, then the 2024 return is computed from the historical cache without requesting that historical window again.

**Expected edits:** Add a return-history cache contract and file implementation under `.../returns/adapter/`, modeled on but separate from `NewsDiscoveryCacheStore.kt` and `FileNewsDiscoveryCacheStore.kt`; wrap the Yahoo source with cache policy in `.../returns/application/` or an adapter decorator; add filesystem, corruption, and offline workflow tests.

**Scope:** Validated completed historical observations only. Fresh current-year data is still required for a successful full report refresh; exclude serving expired current-year data as fresh.

**Notes:** Cache raw dated adjusted-close observations plus resolved symbol, provider, calculation-policy version, fetch time, exchange timezone/session metadata, and covered window including the pre-year baseline. Avoid a single indivisible five-year cache key that prevents reuse with independently fetched current-year data. Validate cached content on read; ignore corrupt entries and surface the provider failure if refetch also fails.

### 9. Bypass return-history cache on explicit refresh

**Acceptance criteria:** Given cached 2024 `MSFT` observations produced `20.00%` and Yahoo now returns corrected adjusted closes producing `21.00%`, when an explicit refresh is requested, then the 2024 row shows `21.00%`.

**Expected edits:** Thread a refresh option through the report command/service boundary, `StockDetailRefreshService.kt`, `StockYearlyReturnsUpdater.kt`, and `YearlyReturnsWorkflow`; update cache policy and command/service tests.

**Scope:** Explicit cache bypass and replacement only. Excludes automatic reconciliation between providers.

**Notes:** If no explicit report refresh control currently reaches this path, expose the smallest reversible service/CLI option rather than overloading file staleness. A failed forced refresh must leave both the prior report and last validated cache intact.

### 10. Recalculate previously stored historical returns under the new definition

**Acceptance criteria:** Given a stored `MSFT` 2024 row shows `18.00%` from the first-trading-day convention and validated Yahoo prices yield `20.00%` from the prior-year close, when the first deterministic refresh succeeds, then the 2024 row shows `20.00%`.

**Expected edits:** Add a return-definition version or provenance marker to report storage in `/home/karl/git/investment/alphascout/src/commonMain/kotlin/com/nimblygames/investment/information/StockDetail.kt` and the load/save boundary in `.../stock/StockDetailGenerator.kt`; switch production bindings in `AlphaScoutComponent.kt`/`AlphaScout.kt`/`StockReportService.kt`; update `StockYearlyReturnsUpdater.kt`, `StockDetailRefreshService.kt`, `.../information/YearlyPercentageReturn.kt`, and report/markdown tests under `alphascout/src/commonTest/`. Remove production use of the LLM researcher and its obsolete prompt here.

**Scope:** First-refresh migration of stored yearly-return values; retain the existing return-row JSON structure and date labels. No global backfill on startup, database conversion, or rewrite of unrelated fields.

**Notes:** Old reports have no provenance. Treat their historical numeric returns as requiring recalculation exactly once after new semantics are enabled; persist a definition version only after all required returns have been computed and the report write succeeds. If Yahoo fails, preserve the old report untouched; do not label it as migrated. A 2024 row labeled `2024-01-01 to 2024-12-31` still uses the last trading close *before* that labeled start, so make the field documentation explicit. See `StockYearlyReturnsUpdater.kt`, `StockDetail.kt`, and existing report JSON in `/home/karl/git/investment/notebook/stocks/`.
