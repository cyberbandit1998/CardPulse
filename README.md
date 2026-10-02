# ⚠️ Disclaimer

CardPulse is an experimental, community-driven project built with a healthy amount of vibecoding.

Expect rapid changes, occasional bugs, and plenty of iteration. Use version control, keep backups, and don’t assume every release is production-ready.

Contributions are welcome. If you have a fix, feature, UI improvement, documentation update, or new idea, feel free to open a pull request or issue.

When reporting a bug, please include:

- Steps to reproduce
- What you expected to happen
- What actually happened
- Screenshots or logs when available
- Your Android version and CardPulse version, if relevant

For contributions, please keep pull requests focused and explain the reason behind the change. Update tests or documentation when appropriate and make sure existing checks still pass.

CardPulse is designed to work with a self-hosted PokéCollector backend. Changes should avoid exposing credentials, API tokens, private collection data, or server configuration.

Be kind, be clear, and keep feedback constructive.

Most importantly: have fun building, scanning, collecting, and improving CardPulse.

# CardPulse

CardPulse is an unofficial Android companion for a self-hosted
[PokéCollector](https://github.com/Git-Romer/pokecollector) server. Browse your collection, watch your
portfolio value, and scan cards with the phone camera. Recognition is done by **your own** PokéCollector
server; the phone only takes the photos and uploads them.

> **Status: early development.** Built and checked against PokéCollector 1.51.0. Newer or older servers should
> mostly work (unknown fields are ignored) but are untested.

## Connecting to your server

The app has **no built-in server address**. On first launch, enter the `https://` address of your own
PokéCollector server, then your username and password. The address is stored on the phone only. Plain `http://`
is refused.

### Before you expose a server to the internet

PokéCollector's *single-user mode* has no authentication: every client that can reach it is treated as the
administrator. If the server is reachable from the internet (a tunnel, a reverse proxy, port forwarding), turn on
**Multi-User Mode** first, and set a known admin password **before** enabling it. See the PokéCollector README.
The app warns you if it connects to a server in single-user mode.

## What it does

- **Rapid scan:** the camera stays open while you work through a pile. Each photo is sent to your server the moment
  it is taken and read in the background (up to three at once), so you can photograph the next card straight away.
  Results collect in a tray along the bottom. Photos are kept on the phone until they upload, so a dropped connection or
  a closed app doesn't lose a card, and scans left on the server are picked up again. A scan that keeps spinning can be
  cancelled from its tile; that also deletes it on your server.
- **Check before adding:** tap a result to see your photo beside the match, then confirm the condition, variant,
  language, quantity and purchase price it will be added with. One tap adds it and the next waiting result opens. The
  condition and variant you used last are suggested for the next card.
- **Duplicates:** every result says whether the card is new to your collection or how many copies you already own, and
  in which conditions, variants and languages. Until the collection has loaded it says nothing rather than guess.
- **Collection:** search and sort your whole collection, with official artwork or your own photos (following the
  "prefer my own photos" setting of your PokéCollector account). Open a card to see its details or remove it: one
  copy or all of them, after a confirmation.
- **Portfolio:** total value, gain or loss, a history chart (1W to All), a breakdown, and the week's biggest movers.
  Amounts are shown in the currency chosen in your PokéCollector account.

PokéCollector counts a card with no purchase price as zero cost, so its gain figures look larger than they are
until prices are filled in. The app says so when that applies.

## Install

Every successful build on GitHub produces a debug APK:

- **Easiest:** open the repository's **Releases** page on your phone, pick the newest *Debug build*, tap the `.apk`.
  (Releases are created on request; ask or run the *Android CI* workflow with "publish" ticked.)
- Or open the **Actions** tab, pick the latest run, and download the `cardpulse-debug-apk` artifact.

Android will ask to allow "Install unknown apps" for the app you opened the file from.

Each release page starts with what is new in that build.

Until you set up a signing key (next section), debug builds from CI are signed with a throwaway key that changes on
every build, so Android will not update one in place: **uninstall the old build first** (the saved server address and
sign-in go with it).

## Updating in place (a fixed signing key)

Android only installs a new build over an old one when both were signed with the same key. Give the repository one key
of your own and every later build will install straight over the last, keeping the saved address and sign-in.

1. **Make the key** once, on a computer with Java (`keytool` comes with it):

   ```
   keytool -genkeypair -v -keystore cardpulse.jks -storetype PKCS12 -alias cardpulse -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=CardPulse"
   ```

   It asks for a password twice (nothing shows as you type). Keep `cardpulse.jks` and that password somewhere safe,
   such as a password manager. GitHub never shows a secret again, and replacing the key later means one more reinstall.
   If the command isn't found, install a JDK (17 or newer) first.

2. **Turn the file into text** and copy it:
   - macOS: `base64 -i cardpulse.jks | pbcopy`
   - Linux: `base64 -w0 cardpulse.jks`
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("cardpulse.jks")) | Set-Clipboard`

3. **Add three repository secrets** under *Settings → Secrets and variables → Actions → New repository secret*:

   | Name | Value |
   | --- | --- |
   | `CARDPULSE_KEYSTORE_BASE64` | the text from step 2 |
   | `CARDPULSE_KEYSTORE_PASSWORD` | the password you chose |
   | `CARDPULSE_KEY_ALIAS` | `cardpulse` |

4. **Publish a build** (run the *Android CI* workflow with "publish" ticked). Its release notes say the build is signed
   with your fixed key and show the certificate fingerprint. Install it, removing any older build once. From then on,
   newer builds update it in place.

If a secret is wrong the build fails and says why, rather than quietly using a different key. Every CI build also gets a
higher version number than the last, which Android requires for an update. Never commit the key file: `*.jks` and
`*.keystore` are git-ignored.

## Build it yourself

Android Studio (current stable): open this folder, let Gradle sync, run on a device. Or from a shell with JDK 17:

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The unit tests decode real responses captured from PokéCollector's own API code
(`app/src/test/resources/fixtures`) and run the HTTP stack against a local mock server, so a change in the server's
API shows up as a failing test.

## Security notes

- The sign-in token is stored encrypted with a key in the Android Keystore, and excluded from backups.
- The token is only ever sent to the server address you entered, never to other hosts (card art CDNs, etc.).
- Never commit signing keys (`*.jks`, `*.keystore`) or credentials; they are git-ignored.

## License

No license has been chosen yet.
