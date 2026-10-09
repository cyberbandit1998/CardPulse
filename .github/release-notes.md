**Your daily scans, on the camera.**

- **How many scans you have left today.** The rapid-scan camera says, for example, "23 of 100 scans used today", or "Unlimited scans" if your server puts no limit on you.
- **At the limit** it says "Daily scan limit reached" and when scans start again (at your server's midnight, shown in your phone's time). The shutter waits instead of taking photos your server would turn away, and a photo that was turned away stays in the tray to be sent again after the reset.
- **The limit is set on your server, not in the app.** An admin chooses the default limit, and a limit for each person (or none, admins included), on the PokéCollector website: Settings → AI / Card Scanner, and Settings → Users, where each person's use today is shown too. The server checks it right before a photo is read, so it holds for the website and every app alike. Only photos the scanner starts to read are counted: opening the camera, a photo that is turned away first, and the scanner's own retries are not.
- This needs the second update in the `server/` folder (it also needs the website and backend rebuilt, and your time zone set so the day starts at your midnight). Without it the camera shows nothing about limits and everything works as before.

If anything looks wrong on your phone, tell me which screen it was.
