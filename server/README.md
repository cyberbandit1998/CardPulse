# Updates for your PokéCollector server

CardPulse works with PokéCollector as it is. Two optional updates add what a phone app cannot be trusted to do alone,
because the rules have to be kept by the server:

| Update | Patch in this folder | What it adds | In CardPulse |
| --- | --- | --- | --- |
| **Friends & trading** | `0001-friends-sharing-and-for-trade-lists.patch` | Friends, what each person shares, For Trade lists | **Home → Friends & trading** says *"Needs an update on your server"* until it is installed |
| **Daily scan limits** | `0002-daily-scan-limits.patch` | A limit on how many cards each user may have read by the AI scanner per day, set by an admin on the website | The scanner shows *"23 of 100 scans used today"* (or *Unlimited*) and, at the limit, *"Daily scan limit reached"* with when scans reset. Without the update the line is left out |

**The second builds on the first**, so install them in that order; the steps below do both. Everything else in the app works
without either. Already have the Friends update? Jump to [Adding the scan limits to a server that has the Friends
update](#adding-the-scan-limits-to-a-server-that-has-the-friends-update).

## Friends & trading: why the server has to change

A phone app cannot be trusted to keep one person's cards private from another. If the server handed every collection to
every signed-in user and the app merely *chose not to show* some of them, anyone could read them with a different app or
a few lines of code. So the rules are kept on the server, which checks them on **every** request. The app only asks, and
shows what the server agrees to send.

PokéCollector has no friends, sharing settings or trade lists of its own. This update adds them.

## Friends & trading: what the update does

- **Everything starts private.** Nobody sees anyone else's cards until its owner says so, for each list on its own:
  **collection**, **wishlist** and **For Trade** cards, each *Private*, *Friends only* or *Public*.
  *Public* means everyone with an account **on your server**, not the internet.
- **Friends are made by consent.** Someone asks (by username or by invite code) and the other person accepts or
  declines. An invite code is only another way to find a person: it never skips the acceptance.
- **For Trade is deliberate.** A card is only on the list if its owner marks it, and for a card held several times they
  choose how many copies. Nothing is ever marked automatically.
- **Trade match** compares two people: the cards they have for trade that are on your wishlist, and the cards you have
  for trade that are on theirs.
- **A person who may not see something gets nothing.** A friend who has not been given access is refused (`403`); anyone
  else is told the person or list does not exist (`404`), so a private account looks like a missing one.
- **What never leaves its owner:** what was paid, the amount invested and the profit or loss worked out from it, when a
  card was added, scan photos, price alerts and target prices, and cards made by hand.
- **The same card and price data.** Friends' lists are rows of the catalogue and prices your server already has; nothing
  is copied.

It adds three small tables (`friend_settings`, `friend_links`, `trade_list`), created when the server starts. No existing
table or row is changed. The details, the endpoints and the exact privacy rules are in `docs/FRIENDS.md`, which the update
adds to the project.

### Friends & trading: what changes on the website

PokéCollector already had a few routes that let **any signed-in user read another user's whole collection** (the
leaderboard's *view collection*, *compare* and *achievements*). Leaving them as they are would have made the new settings
meaningless, so they now follow the same setting. In practice: a user who has not shared their collection no longer
appears in the website's Leaderboard for others, and cannot be compared with or opened. The older *view collection* route
also used to send what the owner paid and the date each card was added, along with the cards; it no longer does, for
anyone but the owner.

The Leaderboard and Compare also **stop carrying anyone's invested amount or profit**, your own included. They keep to
the username, avatar, number of cards, what the collection is worth, the best card, the sets completed and similar
counts. The two website pages that draw them (`Leaderboard.jsx` and `Compare.jsx`) are changed to match: **no profit or
loss column or block, and no way to sort the Leaderboard by profit.** Nothing is shown in their place, not even a zero.
In another person's achievements the *Investor* badge ("In the Green"), which says whether they are in profit, is left
out too (your own list keeps it).

Everything else on the website is as it was, and there are no new screens on it. Your own pages (Home, Analytics, your
collection and so on) still show you what you paid and made. Because two website pages change, **the website has to be
rebuilt as well as the backend** (step 4).

## Daily scan limits: what the update does

An admin can limit how many cards each user may have read by the AI scanner in a day. The limit is kept **on the server**,
right before the vision model (Gemini, or an OpenAI-compatible service such as Groq) is asked, so the website, the CardPulse
app and any other client are held to it alike. The app only shows it.

- **Only real reads count.** A scan counts when the server starts to read a photo. Opening the scanner or the camera does
  not, nor does a photo the server turns away first (not an image, too big, no API key set up), nor matching the card with
  the catalogue, nor the retries the queue makes by itself. A photo you read again with **Retry** counts again.
- **Per user, per day, in the server's time.** Usage starts again at the server's local midnight. It is stored in the
  database, so restarting the backend does not reset it. (In Docker the server's time zone is UTC unless you set `TZ`; for
  Michigan that is `TZ=America/Detroit`: step 5.)
- **A default, and exceptions.** On the website, **Settings → General → AI / Card Scanner** (admins) has the default daily
  limit, or *unlimited*, and shows the server's time zone and the next reset. **Settings → Users** shows each user's limit:
  *Use default*, *Custom* or *Unlimited* (an admin can be unlimited too), and what they have used today, such as
  `34 / 100 today`. Only admins can change any of it.
- **Nothing changes until you choose.** The default starts as *unlimited*, so installing the update limits nobody.
- **Out of scans:** the server answers **HTTP 429** with the daily limit, the scans used, the scans remaining and the reset
  time. Photos already waiting in the queue that no longer fit are marked failed (not retried by themselves) and can be read
  with **Retry** after the reset.

It adds three small tables (`scan_usage`, `scan_limit_overrides`, `scan_item_charges`), created when the server starts; no
existing table or row is changed. The details and the API are in `docs/SCAN_LIMITS.md`, which the update adds to the project.
The website's **Settings** page is changed, so **the website has to be rebuilt as well as the backend** (step 4).

## What you need

- PokéCollector **1.51.0** (the version CardPulse is checked against). The update is made for it. On another version
  `git apply --check` (step 2) says whether it still fits; if it does not, it has to be adjusted first.
- **Multi-user mode switched on** for Friends. With it off nobody has to sign in, so nothing could be kept private; the
  Friends routes then answer `403` and the app says so. Your friends need an account on your server (PokéCollector's
  Settings → Users). The scan limits work either way, but a limit of a user's own only means something with several users.
- A Docker Compose install, and permission to build images on the machine that runs it. The backend and the website are
  both built, so it takes several minutes and needs internet access for the packages they use.
- `git` on that machine.

## Install it

Steps 1, 3 and 4 run in the folder where your PokéCollector `docker-compose.yml` and `.env` are; step 2 runs next to it.
**Do not move or recreate that folder**: Docker Compose names your database volume after it, and a different folder
would start an empty database.

**1. Back up first.** Always, before changing a server:

```bash
backup_file="backup_$(date +%Y%m%d_%H%M%S).sql"
umask 077
docker compose exec -T postgres pg_dump -U pokemon pokemon_tcg --clean --if-exists > "$backup_file"
test -s "$backup_file"
```

Do not continue if the last line fails.

**2. Get PokéCollector's source for your version, and apply the updates.** Anywhere that is *not* inside your PokéCollector
folder (next to it is fine); save the two `.patch` files from this folder first:

```bash
git clone --branch v1.51.0 --depth 1 https://github.com/Git-Romer/pokecollector.git pokecollector-src
cd pokecollector-src
for patch in 0001-friends-sharing-and-for-trade-lists 0002-daily-scan-limits; do
  git apply --check /path/to/$patch.patch   # prints nothing when it fits
  git apply /path/to/$patch.patch
done
```

**3. Tell Compose to build the backend and the website from it.** Copy `docker-compose.friends.yml` from this folder next
to your `docker-compose.yml`, and change the two `context:` lines in it if `pokecollector-src` is not next to your
PokéCollector folder. (The file keeps its name from the first update. It also passes your time zone to the backend, and uses
Michigan's, `America/Detroit`, when your `.env` sets none: step 5.)

**4. Build and start the backend and the website.** Both containers are replaced; the database and your data are not
touched. The first build takes a few minutes:

```bash
docker compose -f docker-compose.yml -f docker-compose.friends.yml up -d --build backend frontend
```

If the website shows *502 Bad Gateway* afterwards (its web server remembers where the old backend was), restart it:

```bash
docker compose -f docker-compose.yml -f docker-compose.friends.yml restart frontend
```

**5. Set your time zone.** The day's scans start again at the server's local midnight. In Docker that is the backend
container's time zone, which is **UTC unless you set one**, so a limit would start over at midnight UTC (8 PM in Michigan
in summer, 7 PM in winter) instead of yours. For Michigan, put this in the `.env` file next to your `docker-compose.yml`:

```
TZ=America/Detroit
```

(`America/Detroit` is Michigan's own zone: Eastern time, with the switch to and from daylight saving looked after by
itself. The compose file from step 3 uses it when `.env` has no `TZ`, so the line is there to be explicit and to change
later. For another place use its name, such as `Europe/Berlin` or `Asia/Tokyo`.) Start the backend again so it picks it up:

```bash
docker compose -f docker-compose.yml -f docker-compose.friends.yml up -d backend
```

**6. Check it.** In CardPulse open **Home → Friends & trading** and press **Check again** if it is already open. The
screen now shows *Friends*, *Requests* and *Sharing*. Or, from the server:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/api/friends/me
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/api/scan-limits/me
```

(`8000` is the backend's port unless you set `BACKEND_PORT`.) `401` means the update is installed: the route exists and
wants you to sign in. `404` means it is not. Then open the website's **Leaderboard** (and a trainer's **Compare** page
from it): each trainer shows their value, cards and best card, and there is no profit or loss on either page. If you still
see a P&L column, the website was not rebuilt: repeat step 4 and reload the page. For the scan limits, sign in as an admin
and open **Settings → General**: under **AI / Card Scanner** there is a *Daily scan limit* card that names the server's time
zone (*America/Detroit (UTC-04:00)* in summer, *UTC-05:00* in winter) and the next reset, which is midnight there. If it says
UTC, step 5 did not reach the backend: check that `.env` is next to your `docker-compose.yml`, that the backend was started
again with both compose files, and that the shell you run Compose from has no `TZ` of its own (`echo $TZ` should print
nothing: one set there wins over `.env`). The backend's own clock shows what it is using:

```bash
docker compose -f docker-compose.yml -f docker-compose.friends.yml exec backend date
```

It ends in `EDT` (summer) or `EST` (winter) when the zone is Michigan's.

**7. Choose what you share, and what scanning costs.** Friends: everything starts as *Private*. Open **Friends → Sharing** and
set the collection, wishlist and For Trade list one at a time. Then add a friend by username or invite code. Scan limits:
nothing is limited yet. To limit scanning, set a *default daily scan limit* (and switch *Unlimited by default* off) in the
card from step 6, and give individual users another limit, or none, under **Settings → Users**. **Admins are limited like
everyone else until you set them to *Unlimited* there.**

### Checking the usage and the 429

**On screen.** On the website, **Settings → Users** shows what each user has used today, such as `34 / 100 today`, and
**Settings → General → AI / Card Scanner → Daily scan limit** shows the default and the next reset. In CardPulse the
**Rapid scan** camera shows *"23 of 100 scans used today"* (or *Unlimited scans*), and at the limit *"Daily scan limit
reached"* with when scans start again.

**From the server.** Sign in once and keep the token (use your own username and password; `8000` is the backend's port
unless you set `BACKEND_PORT`; with `jq` installed, `jq -r .access_token` does what the `python3` part does):

```bash
TOKEN=$(curl -s -X POST http://localhost:8000/api/auth/login --data-urlencode 'username=YOUR_USERNAME' --data-urlencode 'password=YOUR_PASSWORD' | python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])')
```

What you have used and have left today, and when the day starts over:

```bash
curl -s http://localhost:8000/api/scan-limits/me -H "Authorization: Bearer $TOKEN"
```

```json
{"daily_limit":100,"unlimited":false,"used":23,"remaining":77,"resets_at":"2026-10-10T00:00:00-04:00","resets_in_seconds":41025,"timezone":"America/Detroit"}
```

(`"unlimited":true` and `null` for the limit and what is left, for a user with no limit.) As an admin, the same address with
`/api/scan-limits/users` instead lists every user with their setting, the limit that applies and what they used today, which
is what **Settings → Users** shows.

To see the **429** without using a scan or asking the model anything, give yourself a limit of 0 (**Settings → Users**, your
row, **Custom**, `0`, **Save**) and try to queue any photo:

```bash
curl -si -X POST http://localhost:8000/api/cards/recognize/jobs -H "Authorization: Bearer $TOKEN" -F "files=@/path/to/photo.jpg"
```

```
HTTP/1.1 429 Too Many Requests
retry-after: 41010
content-type: application/json

{"detail":"Daily scan limit reached. Scans reset at midnight (America/Detroit).","code":"scan_limit_reached","daily_limit":0,"unlimited":false,"used":0,"remaining":0,"resets_at":"2026-10-10T00:00:00-04:00","resets_in_seconds":41010,"timezone":"America/Detroit"}
```

`retry-after` is the seconds until the reset, and `code` tells this 429 from the rate limiter's, which answers 429 too when
something is asked too often within a minute. Put yourself back on **Use default** afterwards. To watch real scans counted
instead, set a small limit (say 3) and scan with CardPulse or the website: the camera, `…/api/scan-limits/me` and the Users
tab move by one for each photo the scanner starts to read, and the next photo after the last one is refused. A photo that is
already queued when the limit is reached fails with *"Daily scan limit reached…"* and can be read again with **Retry** once
the day has started over.

### Adding the scan limits to a server that has the Friends update

Back up first (step 1). In the `pokecollector-src` folder that already has the Friends update, apply only the second patch,
then copy the new `docker-compose.friends.yml` over the old one (it gained the time zone), set `TZ` (step 5) and rebuild:

```bash
cd pokecollector-src
git apply --check /path/to/0002-daily-scan-limits.patch   # prints nothing when it fits
git apply /path/to/0002-daily-scan-limits.patch
cd ..
docker compose -f docker-compose.yml -f docker-compose.friends.yml up -d --build backend frontend
```

Then check it (step 6) and restart the website if it shows *502 Bad Gateway* (step 4).

### Starting each time

Use the same two files whenever you start or update the server, or Compose goes back to the published images, which do
not have the update:

```bash
docker compose -f docker-compose.yml -f docker-compose.friends.yml up -d
```

(`restart: unless-stopped` brings the containers back after a reboot by itself, with the update.)

### Going back

Start the stack without the second file, which brings back the published images:

```bash
docker compose up -d
docker compose restart frontend
```

The tables stay in the database, unused and harmless; Friends shows *"Needs an update on your server"* in the app again, and
nobody is limited any more. To remove them too: `DROP TABLE friend_settings, friend_links, trade_list;` for Friends, and for
the scan limits `DROP TABLE scan_item_charges, scan_limit_overrides, scan_usage;` and
`DELETE FROM settings WHERE key IN ('scan_limit_default', 'scan_limit_default_unlimited');`

### Updating PokéCollector later

Repeat steps 2 to 4 with the new version's source. A newer PokéCollector may change the files these updates touch, and
`git apply --check` will say so before anything is changed. Keep using the updates' version of `docker-compose.friends.yml`
until then, or the server will go back to the published images.

## Licence

PokéCollector is licensed under the **GNU Affero General Public License v3**, and the patches in this folder are
modifications of it, offered under the same licence. If other people use your modified server over a network (friends
with accounts on it are exactly that), section 13 of the licence asks you to offer them the source of your modified
version. Linking them to PokéCollector's repository and to this folder does that. This is the licence's requirement, not
legal advice.
