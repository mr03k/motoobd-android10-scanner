# Verification report

The old report's 329 assertions were not reproducible: its referenced test runner was not in the repository, and its simulated compression/freeze-frame claims were not evidence of ECU behavior.

The 2.4.0 replacement includes versioned JVM parser tests, Playwright UI/bridge regression tests and CI. See `docs/REVIEW.md` for checks actually executed and remaining hardware limitations.
