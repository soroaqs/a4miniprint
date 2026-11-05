# Security Policy

## Supported Versions

Only the latest published release is supported.

## Reporting a Vulnerability

Please report security issues privately through GitHub Security Advisories for this repository.

Do not include private photos in a public issue. If a reproduction requires a specific image, use a synthetic test image or share only non-sensitive technical details.

Relevant areas include:

- Malformed or malicious image decoding.
- URI handling from the system share sheet.
- Excessive memory use while processing large images.
- Crashes in the print layout or print document pipeline.
- Signing or release packaging problems.

## Scope

The app intentionally does not:

- Connect to the internet.
- Implement a custom print service.
- Communicate directly with printers.
- Modify printer configuration or firmware.

## Safe Usage

Only install the APK from this repository's GitHub Releases. Verify the provided checksum when it is available.
