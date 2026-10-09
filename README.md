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

- **Bottom bar:** Home, Sets, a round camera button in the middle, Collection and Portfolio. The camera button is the
  only way to the scanner and opens it from any tab; a small number on it says how many scanned cards are waiting to be
  checked. Closing the camera returns to the tab you were on.
- **Home:** a dashboard. Your collection's value with its gain or loss and a small chart of how it has moved, the number
  of cards and sets, the cards you added lately in a row that swipes sideways, the cards worth the most, and how far
  along each of your sets is, as pictures, names and slim progress bars. The three tiles can be pressed: Cards opens
  the whole collection, Sets opens the Sets tab, and Top Card opens the details of the card worth the most (found from
  the server's own list of your most valuable cards, never a fixed one). Every list has a "See all", tapping a card
  opens its details, and tapping a set opens its checklist. A heart in the header opens your wishlist. A one-line warning
  says when some cards have no purchase price, which makes the gain look larger than it is.
- **Search:** a search bar under Home's header opens a search of your server's **whole catalogue**, not only the cards you
  own. One box, and chips for what to look at: **All** (the default), **Pokémon**, **Artist**, **Set** and **Number**. All
  looks at a card's or Pokémon's name, its artist or illustrator, its set's name or code, its collector number, and its
  rarity, and puts the closest match first (a set named in full, then names, then artists, then the rest). A number can be
  typed as printed, with the size of its set ("125/197"), which finds that set's card; "OBF 125" (a set code and a number)
  and "Pikachu 58" (a name and a number) work too. Each card is a row like the wishlist's: picture, name, set, number,
  rarity, who drew it (left out when the catalogue has no artist for the card), what it costs now, how many copies you own
  and a heart for the wishlist. A card opens as a page; on it, and on the details of a card in your collection or on your
  wishlist, the **artist's name is a link**: it opens the search for every card they drew. The cards come a page at a time
  as you scroll, in the language you chose on the server. The sliders at the end of the bar choose what to look at before
  you start typing. This uses PokéCollector's own card search, so there is nothing to install on the server.
- **Wishlist:** the cards you want, kept by your PokéCollector server, so its website and every phone show the same list.
  A heart on a card puts it on the list or takes it off: on every card of a set's checklist (the ones you are missing and
  the ones you own), in a card's details, and next to each result when you add a card by typing; cards on the list carry a
  small heart in the Collection and in Recently added. Each row shows the card's picture, name, set, number, rarity and what
  it costs now (by the price you chose in PokéCollector), says whether you own a copy, and can be put in order of when you
  added it, name, set or price (dearest first, the cards with no price last) and narrowed to All, Missing or Owned. **Owning
  a copy never takes a card off the list**: you may want more, and only you remove it. Open a card to set a **target price**
  (typed in your currency; PokéCollector keeps it as its "alert below" price, so the website shows it too, and the row says
  when the price has reached it) and a **priority** (Low, Medium or High; PokéCollector's wishlist has no priority, so that one
  stays on this phone). The server adds one to the quantity wanted when a card is added twice, so the app never adds a card it
  knows is listed.
- **Friends & trading:** a "Friends & trading" row on Home opens the Friends screen. Add people who have an account on
  your server by **username or invite code**; they accept or decline (a request shares nothing by itself, and an invite
  code never skips the acceptance). **Everything starts private.** Under Sharing you choose, for your **collection**,
  your **wishlist** and your **For Trade** cards one at a time, whether it is *Private* (where everything starts), *Friends only* or *Public* (everyone
  with an account on your server, but not the internet). Cards are **For Trade** only when you say so: open a card in your Collection and offer
  its copies one at a time ("2 of 4 for trade"), never more than you hold, and never automatically, not even duplicates.
  A friend's page opens on the **Trade match**: the cards they have for trade that are on your wishlist, and the cards you
  have for trade that are on theirs; then their For Trade list, wishlist and collection, as far as they share them.
  Tapping a card opens its details, with the heart that puts it on your own wishlist. **Your server decides who sees what,
  on every request**, so a friend can never read what you did not share, whatever app they use; what you paid (and the
  invested amount and profit worked out from it), when you added a card, your price alerts, your photos and cards made by
  hand are never shared at all, not even in the website's Leaderboard. This needs Multi-User Mode and a small update to
  your PokéCollector server (its backend, and two pages of its website): see [`server/`](server/README.md). Without them
  the Friends screen says so and the rest of the app is unchanged.
- **Sets:** every set your server lists, each as "owned / total" (18 / 132, or 0 / 230 for a set you have no cards from
  yet), plus any set you own cards from that the list lacks (such as one in another language). Narrow it to All, Owned,
  Incomplete (some of its cards owned) or Complete (every card owned); search by name, series or the
  code printed on the cards ("OBF"); put it in order of progress, name or cards owned. Progress counts different cards, so
  two copies of one card count once. Tapping a set opens its **checklist**: every card of the set, in card-number order,
  the ones you own in full colour with a tick (and how many copies), the ones you are missing greyed out and marked
  "Missing". All / Owned / Missing chips show only the cards you want, and an owned card opens its details.
- **Light or dark:** Settings, Appearance: light, dark, or whatever your phone is set to. The choice stays on the phone.
- **Rapid scan:** the camera stays open while you work through a pile. Each photo is sent to your server the moment
  it is taken and read in the background (up to three at once), so you can photograph the next card straight away.
  Results collect in a tray along the bottom. Photos are kept on the phone until they upload, so a dropped connection or
  a closed app doesn't lose a card, and scans left on the server are picked up again. A scan that keeps spinning can be
  cancelled from its tile; that also deletes it on your server.
- **Daily scan limit:** an admin of your PokéCollector server can limit how many cards each person may have read by the
  AI scanner in a day (on the website: a default for everyone, a limit for one person, or none). The camera shows what is
  left, such as "23 of 100 scans used today", or "Unlimited scans" when there is no limit. At the limit it says "Daily scan
  limit reached" and when scans start again (at the server's midnight, shown in your phone's time), and the shutter waits
  instead of taking photos the server would turn away; a photo that was turned away stays in the tray and can be sent again
  after the reset. **The limit is kept by your server, never by the app**, so it holds for the website and every other
  client alike. It needs a small update to your PokéCollector server (the second one in [`server/`](server/README.md));
  without it the camera shows nothing about limits and everything works as before.
- **Check before adding:** tap a result to see your photo beside the match, then confirm the condition, variant,
  language, quantity and purchase price it will be added with. One tap adds it and the next waiting result opens. The
  condition and variant you used last are suggested for the next card.
- **Duplicates:** every result says whether the card is new to your collection or how many copies you already own, and
  in which conditions, variants and languages. Until the collection has loaded it says nothing rather than guess.
- **Add a card by typing:** when you'd rather type than scan, enter the card's name and its number (as printed,
  "125/197" is fine). Your server's catalogue is searched as you type, and the card's picture, set, rarity, type, hit
  points and artist fill in by themselves; one match is picked for you, and several are listed to choose from.
  Then the same condition, variant, language, quantity and price choices as for a scan. A card the catalogue doesn't
  have can be made by hand, like the website's "Create card manually"; such a card has no market price.
- **Prices:** after a card that is new to your collection is added (scanned or typed in), the app asks your server to
  look up prices and then refreshes the Portfolio and Collection. That needs an admin account on the server, and it can
  be switched off in Settings.
- **Collection:** search and sort your whole collection, with official artwork or your own photos (following the
  "prefer my own photos" setting of your PokéCollector account). A Filter chip beside Recent / Name / Set narrows it by
  rarity, condition, variant (holo, reverse holo and the rest), how much a card is worth (per card, in your currency),
  how many copies you hold, or to the entries that have no purchase price. Only values your collection has are offered.
  Open a card to see its details or remove it: one copy or all of them, after a confirmation.
- **Portfolio:** total value, gain or loss, a history chart (1W to All), a breakdown, and the week's biggest movers.
  Amounts are shown in the currency chosen in your PokéCollector account. The change over a range is shown as an amount,
  with a percentage only when it means something: not when the range began with almost nothing (under 1 euro) or when the
  gain is more than ten times the start.

PokéCollector counts a card with no purchase price as zero cost, so its gain figures look larger than they are
until prices are filled in. The app says so when that applies.

## Install

Every successful build on GitHub produces an APK. It is a release build: the code and resources nobody uses are trimmed
(R8), which makes it a fraction of the size of a debug build, and it starts and scrolls faster because it isn't a debug
build.

- **Easiest:** open the repository's **Releases** page on your phone, pick the newest build, tap the `.apk`.
  (Releases are created on request; ask or run the *Android CI* workflow with "publish" ticked.)
- Or open the **Actions** tab, pick the latest run, and download the `cardpulse-apk` artifact.

Android will ask to allow "Install unknown apps" for the app you opened the file from.

Each release page starts with what is new in that build.

Until you set up a signing key (next section), builds from CI are signed with a throwaway key that changes on
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

`:app:assembleRelease` makes the trimmed release build the same way CI does (it takes longer, as R8 runs); without the
CI signing key it is signed with the debug key so it can still be installed.

The unit tests decode real responses captured from PokéCollector's own API code
(`app/src/test/resources/fixtures`) and run the HTTP stack against a local mock server, so a change in the server's
API shows up as a failing test.

## Security notes

- The sign-in token is stored encrypted with a key in the Android Keystore, and excluded from backups.
- Friends and sharing are enforced by your server (see [`server/`](server/README.md)), never by hiding things in the app.
  The app asks only for the lists a friend has shared, keeps what it was sent only while that friend's page is open, and
  forgets all of it when you sign out.
- The token is only ever sent to the server address you entered, never to other hosts (card art CDNs, etc.).
- Never commit signing keys (`*.jks`, `*.keystore`) or credentials; they are git-ignored.

## License

No license has been chosen yet.
