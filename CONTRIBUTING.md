# Contributing to ShaaziFi

Thank you for helping. Bug reports, router test results and focused pull requests are welcome.

## Ground rules

- **Safety first.** Every change to a router goes through `RouterWriter` (an allowlist of exact
  calls) and `SafeChangeRunner` (confirmation, apply with automatic rollback, confirm after
  reconnect). New changes extend the allowlist narrowly and come with a test proving nothing is
  sent before confirmation.
- **Reads are allowlisted too** (`RouterReader`, `AdGuardEndpoints`). Add a call only with a
  source: LuCI, rpcd or AdGuard Home code or documentation, linked in the code comment.
- **Tests first.** Write the failing test, then the code. `./gradlew test` must pass.
- **No secrets in code, tests or fixtures.** Use documentation addresses (192.0.2.x,
  198.51.100.x, 203.0.113.x, 100.64.0.7) and made-up fingerprints. Real router captures must be
  scrubbed before they are committed.
- **Credit.** If you adapt code from another project, keep its notice, name the source in the
  file and add it to `CREDITS.md` (licence must be compatible with GPL-3.0).

## Reporting a router issue

Please include: OpenWrt version, LuCI version, router model, which screen, what you expected,
what happened. Do not include passwords, public addresses or certificate fingerprints.

## Licence

By contributing you agree that your contribution is licensed under GPL-3.0, like the rest of
ShaaziFi.
