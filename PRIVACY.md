# Privacy Policy

a4miniprint is a local printing tool. It is designed to process photos and PDF files on the device and does not include network features.

## Data We Access

When you use the app, it reads image or PDF data from:

- The Android system photo picker or file picker.
- URIs shared to the app by another local app, such as the system gallery, a file manager, or a cloud drive.

The app uses these files only to generate the requested print layout.

## Data We Do Not Collect

a4miniprint does not:

- Request the `INTERNET` permission.
- Upload photos, PDF files, or print documents.
- Include advertising, analytics, accounts, or tracking SDKs.
- Request broad storage permissions.
- Collect your name, contacts, location, printer identifiers, or usage statistics.

## Local Output

The app does not save its own image, PDF, or cache files. It passes the print document to Android's system print service. The system print service or a selected PDF printer may create temporary or user-requested files according to the operating system and printer behavior.

One exception: if a PDF source cannot be read at arbitrary positions (some cloud drives or pipe providers), the app copies that PDF into its private cache directory for the current print job. The copy stays on the device, is never uploaded, and is deleted the next time a PDF is selected or when the app cold-starts.

## Permissions

The app relies on temporary read access granted by the Android photo picker, the system file picker, or the share sheet. It does not request persistent access to your files.

## Changes

If a future version adds network features, this policy must be updated before release. Such a change would conflict with the current project constraints and should be treated as a major design decision.
