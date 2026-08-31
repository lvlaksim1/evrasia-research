# Refactoring

The refactoring series preserves application behavior and, in particular, keeps the raw research archive independent from the debugger correlation/display layer.

## Stage 1 — v51
- Removed obsolete implementation generations and unused legacy components.

## Stage 2 — v52
- Removed the global AlertDialog sizing workaround from the application theme.
- Preserved required debugger compatibility behavior after validation.

## Stage 3 — v53
- Replaced reflection-based browser/archive access with explicit internal APIs.
- Removed obsolete debugger compatibility code and build-time dialog source mutation.

## Stage 4 — v54
- Extracted display correlation into `NetworkDisplayMerger`.
- Extracted endpoint grouping into `NetworkEndpointAnalyzer`.
- Extracted network-event classification into `NetworkEventClassifier`.

## Stage 5 — v55
- Replaced versioned cookie provider names and JavaScript markers with stable names.

## Stage 6 — v56
- Centralized `ResearchArchive` mutation methods.
- Removed periodic archive-to-debugger mirroring.
- Kept raw archive records as the source for HAR and raw export.

## Stage 7 — v57
- Extracted HTTP resource and external-script copying into `WebResourceCapture`.

## Stage 8 — v58
- Extracted browser JavaScript payloads into `WebResearchScripts` without rewriting their behavior.

## Stage 9 — v59
- Extracted browser capture orchestration and JavaScript bridge handling into `WebCaptureController`.

## Stage 10 — v60
- Extracted request replay/editor behavior into `NetworkReplayController`.

## Stage 11 — v61
- Extracted debugger event correlation/merge logic into `NetworkEventCorrelator`.

## Stage 12 — v62
- Extracted browser navigation, bookmarks, and cookie-statistics controllers from `WebResearchV10Activity`.

## Stage 13 — v63
- Extracted incremental debugger-store synchronization into `NetworkDebuggerDataSource`.

## Stage 14 — v64
- Extracted WebView client orchestration into `WebResearchWebViewController`.
- Extracted ZIP export lifecycle into `WebResearchExportController`.

## Stage 15 — v65
- Removed `TextColorCompat` and `SpinnerCompat` compatibility shims.
- Replaced their remaining call sites with explicit platform APIs.

## Stage 16 — v66
- Added `NetworkRecordPipeline` as the explicit boundary between raw archive capture and correlated debugger storage.
- Preserved the invariant that raw records are appended before debugger correlation.
- Preserved debugger-only synthetic records for inline scripts.
- Preserved `ResearchArchive.records` as the source for HAR, API summaries, source logs, and `raw-events.json`.

## Current architecture boundaries
- `WebResearchV10Activity`: browser screen and top-level Android lifecycle/orchestration.
- `WebCaptureController`: browser instrumentation, snapshots, JavaScript bridge, chunk assembly.
- `WebResourceCapture`: HTTP copying of resources and external scripts.
- `WebResearchScripts`: JavaScript payload definitions.
- `WebResearchWebViewController`: WebView clients and WebView event routing.
- `WebNavigationController`: URL normalization and active-window navigation.
- `WebBookmarkController`: bookmark persistence and selection.
- `WebCookieStatsController`: cookie statistics panel lifecycle.
- `WebResearchExportController`: ZIP document creation/export flow.
- `ResearchArchive`: raw capture state and export generation.
- `NetworkRecordPipeline`: raw-to-debugger routing boundary.
- `NetworkDebugStore`: correlated debugger storage and revisions.
- `NetworkEventCorrelator`: debugger-store correlation policy.
- `NetworkDisplayMerger`: derived display-only merging.
- `NetworkEndpointAnalyzer`: endpoint grouping and normalization.
- `NetworkEventClassifier`: event classification helpers.
- `NetworkDebuggerDataSource`: incremental debugger-store synchronization.
- `NetworkReplayController`: replay/editor UI and request execution.

The critical invariant after v66 is that correlation and display transformations do not replace or mutate the raw archive used for exports.
