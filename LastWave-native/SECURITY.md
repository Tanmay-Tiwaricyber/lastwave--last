# Security Policy

## Supported Versions

Security fixes are provided for the latest stable release of LastWave.

| Version | Supported          |
| ------- | ------------------ |
| 5.0.x   | Yes                |
| < 5.0.0 | No                 |

## In-App Updates

The updater in *Settings → About & Community → Updates* downloads an APK from this repository's GitHub
releases and hands it to the system installer. Trust decisions it makes, in `data/update/`:

1. **Only a release asset that looks like ours is offered.** `.apk`, named `LastWave…` or
   `Silent Programmer…`, not a legacy/OEM slice. Source archives and sidecars are never install targets,
   and a release with no matching APK produces a message, not a fallback to "first APK found".
2. **Size, then digest.** The downloaded byte count must equal the size the release recorded, and the
   streaming SHA-256 of the file must equal the `digest` GitHub records per asset — or the corresponding
   line of a `CHECKSUMS.txt` asset. A digest from a different algorithm is treated as no digest, never as a
   mismatch: reporting tampering because nobody computed the same hash would be a false alarm with real
   consequences.
3. **Then the archive itself.** `getPackageArchiveInfo` must parse the file, its `versionCode` must be higher
   than the installed one, and its signing certificate must be this app's. Any of those failing deletes the
   file and refuses to show an install prompt.
4. **The platform enforces the last step.** Android's package installer refuses an update signed with a
   different key, so a mismatched signature can never become a data-losing overwrite even if the in-app
   comparison is inconclusive on an OEM build. When the comparison *is* inconclusive the dialog says the
   signature was not checked instead of implying it was.

Lookups use the anonymous GitHub API, so the app stores no credential and sends none. Failures (404 for a
missing or private repository, 403 for the rate limit, no connection) are reported as themselves and never as
"you are up to date".

## Download Mirrors

*Settings → Audio & Playback → Download mirror* (and the same control in a track's menu) chooses which
*existing* source an offline download is asked for first. Security notes, in `data/download/`:

1. **No new network endpoints are introduced.** The choices are the app's own sources — the add-on servers
   configured in Settings → Modules, the legacy single add-on URL, and YouTube Music. Nothing in this feature
   can point the downloader at a host the user did not already add, and no mirror host is bundled.
2. **A pin is strict at the source, and the fallback is explicit.** Asking one add-on server that has no copy
   yields no credentials for that pin rather than quietly substituting another server; the download cascade
   then continues to the next source and the completion notice names whichever one served the file.
3. **Credentials stay off the screen.** A mirror row is labelled by host, not by URL, and the host extraction
   drops any `user:token@` authority component — add-on URLs in this app frequently carry a token, and a label
   that echoed the URL would put it in a screenshot.
## Reporting a Vulnerability

If you discover a security vulnerability in LastWave, please report it privately rather than creating a public issue.

### How to Report
* Reach out privately to the maintainers on Telegram:
  * Channel: https://t.me/clashprojects
  * Discussion Group: https://t.me/clashdiscussion
* Alternatively, submit a private security advisory through the GitHub repository.
* Include:
  * Description of the vulnerability
  * Affected versions and components
  * Steps to reproduce or proof-of-concept
  * Potential impact assessment

### Response Timeline
* **Initial acknowledgment:** Within 48 hours.
* **Assessment & mitigation plan:** Within 5 business days.
* **Public disclosure:** Coordinated after a patched release is published.

Thank you for helping keep LastWave secure for all users.
