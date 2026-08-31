# Security policy

## Reporting a vulnerability

Use GitHub's private vulnerability reporting feature for this repository when available. Do not open a public issue containing exploit details, access tokens, private documents, unredacted printer/device serial numbers, or sensitive diagnostics.

No security contact email has been published. Do not send reports to guessed addresses.

## Sensitive data guidance

- Never attach private documents, page images, or print payloads to an issue.
- Review diagnostic exports before sharing them.
- Remove printer and device serial numbers or other unique identifiers that were not automatically redacted.
- Do not commit API tokens, credentials, keystores, `local.properties`, `.env` files, or absolute local paths.
- Revoke any secret immediately if it is accidentally published; deleting it from the latest commit is not sufficient.

## Application privacy and attack surface

USB Print processes documents locally and has no INTERNET permission, analytics, Firebase, advertising, cloud upload, or account system. USB printer responses and user-selected documents must still be treated as untrusted input. Protocol parsers therefore use explicit size/count/depth limits, and raster calculations use checked arithmetic.

The first public APK is debug-signed for testing. No production signing key is included in the repository.

## Supported versions

Security fixes are applied to the latest public release and the default branch. Older builds may not receive backports.
