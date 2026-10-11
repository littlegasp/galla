# Galla — setup guide

Galla is one web app that captains open from a personal link and add to their home screen. It tracks online time per app, trips, expenses, today's net earnings, ₹ per hour, and a weekly best-hours report. **Galla Kavach** adds a ride safety recorder (audio + front-camera video) with SOS and safety reports. It never reads or touches any ride-hailing app.

## What's in the folder

| File / folder | Upload to GitHub? | What it is |
| --- | --- | --- |
| `index.html` | Yes | The whole app (Hindi, Marathi, Gujarati, English) |
| `manifest.webmanifest` | Yes | Lets captains install Galla like an app, with the Galla icon |
| `sw.js` | Yes | Lets the app open with no internet |
| `assets/` | Yes | Icon, logo, hero and vehicle images |
| `firestore.rules` | No, paste into Firebase | Each phone can only see its own captain code |
| `storage.rules` | No, paste into Firebase | Kavach: phones can only add encrypted recordings, never read or delete |
| `lifecycle.json` | No, apply to the bucket | Kavach: deletes recordings after 7 days |
| `tools/` | **No, keep on your computer** | Kavach admin tool: keys, holding and opening recordings for a safety report |
| `captain-codes.csv` | **No, keep private** | 20 personal codes for pilot captains |
| `SETUP.md` | No | This guide |

## 1. Try it now (demo mode)

Until you paste a Firebase config, Galla runs in **demo mode**: data stays in that one browser, and a yellow banner says so. Upload the files to GitHub Pages (step 3) and open the link on your phone to try the full flow.

## 2. Create the Firebase project (about 15 minutes)

1. console.firebase.google.com → **Add project** → name it `galla` (Analytics not needed).
2. **Build → Authentication → Get started → Sign-in method** → enable **Anonymous**.
3. **Build → Firestore Database → Create database** → region **asia-south1 (Mumbai)** → production mode.
4. Firestore → **Rules** tab → replace everything with `firestore.rules` → **Publish**.
5. **Project settings (gear) → Your apps → Web `</>`** → register → copy the `firebaseConfig`.
6. In `index.html`, find `const FIREBASE_CONFIG = {` and paste your values. Set `SUPPORT_PHONE` to your WhatsApp number.

The free Spark plan covers the pilot.

### 2b. Turn on screenshot reading (Firebase AI Logic)

1. Firebase console → **Build → AI Logic → Get started** → choose the **Gemini Developer API** (free tier, stays on Spark).
2. **App Check** (mandatory for AI Logic from 2 November 2026): Firebase console → **App Check** → register the web app with **reCAPTCHA Enterprise**, add `littlegasp.com`, copy the **site key**, and paste it into `RECAPTCHA_SITE_KEY` in `index.html`. Then enforce App Check for AI Logic.
3. Before real captains use it, check Google's terms for the free Gemini tier on how submitted content may be used. These are captains' earnings, so if the free tier allows content to be used for improving Google's models, link a billing account and use the paid tier.
4. The model is set in `AI_MODEL` (`gemini-3.8-flash`). The Firebase SDK version is in `FIREBASE_VERSION` (`13.0.0`).

How it works: the captain picks 2–4 screenshots of Rapido **All Orders → Day**. Each is shrunk on the phone and sent once to Gemini, which returns only date, time, earning, vehicle and pickup area. The image is never stored. Galla removes trips that appear in two screenshots, checks the sum against Rapido's own summary box, estimates online hours from the trip times (the captain can adjust them), and saves.

### 2c. Turn on Galla Kavach (about 30 minutes)

Kavach needs the paid **Blaze** plan, because file storage isn't on the free plan. Set a budget alert first: Google Cloud console → Billing → Budgets & alerts → for example ₹1,000 a month.

1. **Make the key pair (once, ever).** Install Node.js 20+ on your computer. In the `tools` folder run `npm install`, then `node kavach-admin.mjs keys`. It saves `kavach-private.jwk.json` and prints one line starting `const KAVACH_PUBLIC_KEY =`. Paste that line into `index.html` in place of `const KAVACH_PUBLIC_KEY = null;`.
   - **Back up `kavach-private.jwk.json` in two offline places** (pen drive plus one more). If it's lost, no recording can ever be opened. If it leaks, every recording can be. Never put it on GitHub, WhatsApp or email.
2. Firebase console → **Upgrade to Blaze** → **Build → Storage → Get started** → location **asia-south1 (Mumbai)**.
3. Storage → **Rules** tab → replace everything with `storage.rules` → **Publish**. When Firebase asks to let Storage rules read Firestore, allow it (the rules check that a phone owns the captain code).
4. Firestore → **Rules** → paste the new `firestore.rules` → **Publish** (adds the `kavach` and `reports` sections).
5. **Auto-delete after 7 days.**
   - Recordings: Google Cloud console → Cloud Storage → your bucket → **Lifecycle** → Add rule → Delete object, age 7 days, object name prefix `kavach/`. You can also run `gcloud storage buckets update gs://YOUR-BUCKET --lifecycle-file=lifecycle.json`. Held evidence lives under `evidence/` and is **not** auto-deleted.
   - Hash records: Firestore → **TTL** (Time-to-live) → Create policy → collection group `kavach`, timestamp field `expireAt`.
6. **Service account for the admin tool:** Project settings → Service accounts → **Generate new private key** → save it as `tools/service-account.json`. Keep it off GitHub too. If your bucket isn't named `<project-id>.firebasestorage.app`, set `GALLA_BUCKET=<name>` before running the tool.
7. **Stickers:** every pilot vehicle needs a visible notice before the captain records, e.g. "यह राइड सुरक्षा के लिए रिकॉर्ड होती है · Recorded for safety by Galla · deleted in 7 days unless a safety report is filed". The app asks the captain to confirm the sticker once and saves the time as `profile.kavachOk`.
8. **Before real riders are recorded:** have a lawyer check the sticker wording, the 7-day policy, how police requests are handled (only on a written request), the rules on recording women passengers, and how this sits with the ride apps' captain terms. Galla is the data fiduciary for these recordings under the DPDP Act.

#### How Kavach works

- One tap starts audio, plus front-camera video unless the captain turns video off. Every minute becomes a separate file.
- On the phone, each file gets a **SHA-256 hash** at the moment of recording. The hash, time and GPS are logged to Firestore (`captains/<code>/kavach`). The file is then **encrypted** with a fresh AES-256 key, and that key is locked with your public key. The phone can't open its own recordings, and Galla staff can't either without the private key on your computer.
- **Audio** (about 11 MB an hour) uploads every minute to `kavach/<code>/` in Storage.
- **Video** (about 90 MB an hour) stays on the phone for 2 hours and then deletes itself. It uploads only when the captain files a report or presses SOS: the reported time ±10 minutes. After an SOS, video keeps uploading for the next 30 minutes too.
- Storage rules allow a phone to **add** files under its own code only. Nobody can read, list, replace or delete them from an app.
- **SOS:** starts recording, files an SOS report, and offers Call 112 plus an SMS of the location to the captain's 2 emergency numbers. Those numbers are kept on the phone only.
- **Test-version limit:** a web page can't record while another app is on screen. While Rapido is open, recording may pause. The native Android app (next step) fixes this with a foreground service.

#### When a captain files a safety report

```
cd tools
node kavach-admin.mjs reports                       # open reports
node kavach-admin.mjs hold   <code> <reportId>      # copy that window's recordings to evidence/ (kept past 7 days)
node kavach-admin.mjs open   <code> <reportId>      # decrypt to tools/evidence/<code>-<reportId>/ + evidence-log.txt
node kavach-admin.mjs status <code> <reportId> shared   # what the captain sees: held / shared / closed
```

- Run `hold` within 7 days of the incident, or the recordings will already have been deleted. Running it again is safe, and picks up video the phone uploaded later.
- `open` checks every file three ways: that it decrypts (any change breaks it), that its hash matches the one sealed inside, and that it matches the hash logged at recording time. The results go into `evidence-log.txt`.
- Hand recordings only to the police (on written request) or to the captain's lawyer, with the evidence log. A court needs a Section 63(4) BSA certificate signed by the person responsible for this system. Take the log to your lawyer to prepare it.
- Never share recordings on WhatsApp or social media, or with a ride app, unless there's a written legal request.

## 3. Host on GitHub Pages

1. The repo is `littlegasp/galla` (public). It lives in the Little Gasp GitHub account, so it uses that account's custom domain.
2. Only the app files are in the repo. `tools/` and `captain-codes.csv` are kept out by `.gitignore`. Never add them.
3. **Settings → Pages** → deploy from `main`, root folder.
4. The app is at `https://littlegasp.com/galla/`. When Galla gets its own domain, add a `CNAME` file with that domain to this repo only. Captains' saved links will change once.
5. Firebase → **Authentication → Settings → Authorized domains** → add `littlegasp.com`.

**When you change a file later:** open `sw.js` and raise the number in `galla-v3` (to `galla-v4` and so on) (and so on), then upload. Otherwise phones keep showing the old cached version.

## 4. Give each captain their link

1. `captain-codes.csv` already has the full links (`https://littlegasp.com/galla/?c=CODE`).
2. Send one link per captain on WhatsApp, e.g. `https://…/galla/?c=U5UZALG4`.
3. Ask them to open it once **with internet**, do the 10-second setup, then Chrome menu → **Add to Home screen**. Galla then opens like an app, even without internet.
4. Keep a private note of which code belongs to which captain.

The first phone to open a code owns it. If a captain changes phones: in the Firestore console delete the document `captains/<code>` (only that document, not its `entries`). Their new phone claims it on the next open and keeps the history.

## 5. Reading the pilot data

Firestore → `captains` → each code → `entries`:

| type | fields |
| --- | --- |
| `trip` | `app`, `fare`, `pay` (cash/online, empty for screenshots), `km` (optional), `vehicle`, `area` (pickup locality, screenshots only), `source` (`shot` when imported), `ts` |
| `expense` | `cat` (fuel/service/fees/other), `amount`, `ts` |
| `session` | `app`, `start`, `end` (one online stretch on one app); `est: true` when estimated from screenshot trip times |

Kavach collections: `kavach` (one record per recorded minute: `kind`, `start`, `end`, `hash`, `size`, `lat`, `lng`, `expireAt`; the phone can create but never read) and `reports` (`cat`, `note`, `from`, `to`, `ts`, `status` new/held/shared/closed, plus `items` once held).

Times are milliseconds since 1970. The captain document holds `profile` (vehicle, apps, language) and `online` (apps they are on right now).

Phase 0 gate: each week, count how many of the 10 codes logged trips on most days.

## How the numbers work

- **Net earnings** = fares − expenses.
- **₹ per hour** = net ÷ online time. If a captain is online on two apps at once, that time counts once.
- **Per-app ₹/hour** = that app's fares ÷ time online on that app.
- **Best-hours grid** (last 7 days) = fares ÷ online time in each 3-hour slot. Darker means more ₹ per hour; a slot needs at least 15 minutes online to show.
