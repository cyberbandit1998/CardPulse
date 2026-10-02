# Pokémon Scanner for Android

An unofficial Android companion for a self-hosted
[PokéCollector](https://github.com/Git-Romer/pokecollector) server. It lets you
browse your collection, see your portfolio value, and scan cards with the
phone camera. Recognition is done by your own PokéCollector server; the phone
only captures and uploads the photo.

> **Status: early development.** The first source drop was machine-generated and
> is being reworked against the real PokéCollector API. Expect rough edges.

## Connecting to your server

The app has **no built-in server address**. The first time you open it, enter the
`https://` address of your own PokéCollector server on the sign-in screen, then
your username and password. The address is saved on the phone only; you can edit
it on the sign-in screen at any time.

Only HTTPS addresses are accepted.

### Before you expose a server to the internet

PokéCollector's *single-user mode* has no authentication: every client that can
reach it is treated as the administrator. If the server is reachable from the
internet (for example through a tunnel or reverse proxy), turn on **Multi-User
Mode** first, and set a known admin password **before** enabling it. See the
PokéCollector README for the exact steps.

## Features (v0.1 baseline)

- Sign in with a PokéCollector username and password
- Collection gallery
- Portfolio value and a simple chart
- Camera capture, recognition through your server, and add-to-collection

## Build

**Android Studio:** open this folder, let Gradle sync, run on a device.

**GitHub Actions:** every push builds a debug APK. Open the repository's
*Actions* tab, pick the latest run, and download the `pokemonscanner-debug-apk`
artifact. To install it, allow "install unknown apps" for the app you use to open
the file.

Debug builds from CI are signed with a throwaway key that changes between runs,
so Android will ask you to uninstall the previous build before installing a newer
one (the saved server address and sign-in are lost when you do).

## Security notes

- Use HTTPS. Plain HTTP is blocked by the app.
- The sign-in token is currently kept in app storage without extra encryption;
  hardening this is on the to-do list.
- Never commit signing keys (`*.jks`, `*.keystore`) or credentials; they are
  git-ignored.

## License

No license has been chosen yet.
