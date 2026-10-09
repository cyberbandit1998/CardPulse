# The Friends update for your PokéCollector server

**Friends & trading** in CardPulse needs a small update to your PokéCollector server. Everything else in the app works
without it, and the app tells you when the update is missing: **Home → Friends & trading** says *"Needs an update on your
server"*, and the Friends screen explains what to do.

## Why the server has to change

A phone app cannot be trusted to keep one person's cards private from another. If the server handed every collection to
every signed-in user and the app merely *chose not to show* some of them, anyone could read them with a different app or
a few lines of code. So the rules are kept on the server, which checks them on **every** request. The app only asks, and
shows what the server agrees to send.

PokéCollector has no friends, sharing settings or trade lists of its own. This update adds them.

## What the update does

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

### What changes on the website

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

## What you need

- PokéCollector **1.51.0** (the version CardPulse is checked against). The update is made for it. On another version
  `git apply --check` (step 2) says whether it still fits; if it does not, it has to be adjusted first.
- **Multi-user mode switched on.** With it off nobody has to sign in, so nothing could be kept private; the Friends
  routes then answer `403` and the app says so. Your friends need an account on your server (PokéCollector's
  Settings → Users).
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

**2. Get PokéCollector's source for your version, and apply the update.** Anywhere that is *not* inside your PokéCollector
folder (next to it is fine); save `0001-friends-sharing-and-for-trade-lists.patch` from this folder first:

```bash
git clone --branch v1.51.0 --depth 1 https://github.com/Git-Romer/pokecollector.git pokecollector-src
cd pokecollector-src
git apply --check /path/to/0001-friends-sharing-and-for-trade-lists.patch   # prints nothing when it fits
git apply /path/to/0001-friends-sharing-and-for-trade-lists.patch
```

**3. Tell Compose to build the backend and the website from it.** Copy `docker-compose.friends.yml` from this folder next
to your `docker-compose.yml`, and change the two `context:` lines in it if `pokecollector-src` is not next to your
PokéCollector folder.

**4. Build and start the backend and the website.** Both containers are replaced; the database and your data are not
touched. The first build takes a few minutes:

```bash
docker compose -f docker-compose.yml -f docker-compose.friends.yml up -d --build backend frontend
```

If the website shows *502 Bad Gateway* afterwards (its web server remembers where the old backend was), restart it:

```bash
docker compose -f docker-compose.yml -f docker-compose.friends.yml restart frontend
```

**5. Check it.** In CardPulse open **Home → Friends & trading** and press **Check again** if it is already open. The
screen now shows *Friends*, *Requests* and *Sharing*. Or, from the server:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/api/friends/me
```

(`8000` is the backend's port unless you set `BACKEND_PORT`.) `401` means the update is installed: the route exists and
wants you to sign in. `404` means it is not. Then open the website's **Leaderboard** (and a trainer's **Compare** page
from it): each trainer shows their value, cards and best card, and there is no profit or loss on either page. If you still
see a P&L column, the website was not rebuilt: repeat step 4 and reload the page.

**6. Choose what you share.** Everything starts as *Private*. Open **Friends → Sharing** and set the collection, wishlist
and For Trade list one at a time. Then add a friend by username or invite code.

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

The three tables stay in the database, unused and harmless, and Friends shows *"Needs an update on your server"* in the
app again. To remove the tables too: `DROP TABLE friend_settings, friend_links, trade_list;`

### Updating PokéCollector later

Repeat steps 2 to 4 with the new version's source. A newer PokéCollector may change the files this update touches, and
`git apply --check` will say so before anything is changed. Keep using the update's version of `docker-compose.friends.yml`
until then, or the server will go back to the published images.

## Licence

PokéCollector is licensed under the **GNU Affero General Public License v3**, and the patch in this folder is a
modification of it, offered under the same licence. If other people use your modified server over a network (friends
with accounts on it are exactly that), section 13 of the licence asks you to offer them the source of your modified
version. Linking them to PokéCollector's repository and to this folder does that. This is the licence's requirement, not
legal advice.
