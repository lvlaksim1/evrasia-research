# Refactoring

## Stage 1 — v51
- Removed obsolete implementation generations.
- Removed legacy, unused components from the active application graph.

## Stage 2 — v52
- Removed the obsolete NetworkDebugger compatibility shim.
- Removed the global AlertDialog sizing workaround from the application theme.
- Request-details geometry remains scoped to the request-details dialog during the release build.

The refactoring stages are intended to preserve application behavior while reducing legacy coupling and compatibility debt.
