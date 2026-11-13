# TailTopia V1.4.1 — Test Cases (Staging · Android)

## Environment

| Item | Value |
|---|---|
| App build | `1.4.1-stag+20` (debug-signed internal build, Google sign-in works) |
| Backend | Staging — `https://api-stag.tailtopia.id` |
| Admin console | Staging — `https://ops-stag.tailtopia.id` |
| Device | Android phone or emulator (Android 13+ for gallery cases; iOS-only cases are marked) |
| App language | Bahasa Indonesia (UI strings below are quoted as they appear in the app) |
| Analytics | Staging builds still send events to the production PostHog project. Emulator events carry `$is_emulator`; filter by app version `1.4.1-stag` |

**Result legend:** ✅ Pass ｜ ❌ Fail (add issue link) ｜ ⏭ Skipped (give reason) ｜ ⚠️ Pass with note

**"Dev-assisted"** means the case needs a developer to prepare data on staging (e.g. change a payment's expiry time, create a banner with a specific target). Ask the dev on duty before running it.

**Out of scope for V1.4.1:** short video (rolled back, ships in a later version), broadcast push to all users (do not send), sending real ops notifications or going live with pop-ups (only check audience counts).

---

## A. Store invite code (Kode toko)

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| MC-01 | Entry point | Saya (Me) → Settings | A "Kode toko" entry is shown | |
| MC-02 | Not bound | Open Kode toko with an account that has never bound a store | 6-box code input; main button disabled until all 6 digits are entered | |
| MC-03 | Invalid code | Enter a 6-digit code that does not exist, submit | Inline error "Kode tidak valid"; stays on the page | |
| MC-04 | Confirmation sheet | Enter a valid code, submit | A bottom sheet asks for confirmation and states the binding cannot be undone; it shows the invite code | |
| MC-05 | Bind succeeds | Confirm in the sheet | Success page; back on Settings the entry shows the store as bound | |
| MC-06 | Already bound (read-only) | Open Kode toko again | Read-only page with the bound store code and "Terhubung sejak {date}"; cannot be changed | |
| MC-07 | Network error | Turn off network, submit a valid code | Toast "Gagal terhubung, coba lagi"; NOT shown as an invalid code; not bound | |

> A binding is permanent. Use a dedicated test account — it cannot be reset from the app.

## B. Bug regressions

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| BUG-599 | Diary order within a day | Post two Diary entries on the same day, open the timeline | Entries of the same day are listed newest first | |
| BUG-600 | Shop banner not cropped | Open Toko (staging has a 16:9-ish banner) | Banner image is shown in full, left/right not cropped; title text on top stays readable over the gradient | |
| BUG-604 | Push rate limit | Dev-assisted: check staging config and logs. **Do not send a broadcast push** | Rate limiter active (25 users/sec); no automatic re-sends | |

## C. Auto-follow TailUs

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| TU-01 | Existing users | Dev-assisted: check staging data | Every active real user follows TailUs (deactivated accounts are excluded by design) | |
| TU-02 | New user | Register a brand-new account (needs a Google account never used on staging) | The new account follows TailUs only (no longer the old official account) | |
| TU-03 | Unfollow sticks | Unfollow TailUs; dev restarts the backend | TailUs is NOT re-followed automatically | |
| TU-04 | Visible in app | Saya → Following list | "TailUs by Tailtopia" appears in the list | |

## D. Ops audience filters (check counts only — do not send)

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| OT-01 | Ops notification form | Admin → Ops notifications → New | Four filters: level tier, pet type, last active, has paid | |
| OT-02 | Audience count | Pick one value per filter (and a few combinations), read the estimated count | Count matches the dev's independent database query | |
| OT-03 | Pop-up form | Admin → Launch pop-up drawer | "More filters" shows the same four filters; the list shows a filter summary | |
| OT-04 | Pop-up targeting | Covered by automated tests (pop-ups are not put live) | Guests do not see a filtered pop-up; signed-in users see it only if they match | |

## E. QRIS payment page

Where the QR sheet appears: any cash purchase paid by QRIS — e.g. KTP HD unlock, sending a gift, keepsake, AI unlock, Toko order. Top-up and vet consult use a full page with the same parts.

> Staging's QRIS gateway currently cannot generate real QR codes (error P04). Cases that need a real QR are dev-assisted: the dev turns a payment into "pending" with a test QR.

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| PAY-01 | Sheet layout | Start any cash purchase, choose QRIS | Sheet takes 60% of the screen; amount and countdown on one row; QR code; order number with copy; "Menunggu pembayaran…" | |
| PAY-02 | Scroll to bottom | Scroll inside the sheet | 9 payment-app logos + "lainnya" + 5 steps; buttons stay pinned at the bottom | |
| PAY-03 | Copy order number | Tap the order number | Toast "Tersalin"; clipboard holds the order number | |
| PAY-04 | Save QR | Tap "Simpan Kode QR" | Toast "Tersimpan ke galeri"; gallery has one 1080-wide image with brand, QR, amount, order number and valid-until time | |
| PAY-05 | Gallery permission denied (**iOS**) | Deny photo access, tap save | In-app dialog "Perlu izin"; "Buka Pengaturan" opens system Settings. (Android 13+ needs no permission, so this cannot be triggered on Android) | |
| PAY-06 | Expired | Dev-assisted: set the payment to expire in 1 minute; wait | Countdown shows 00:00 in grey; QR is covered with an expired notice; only "Tutup" remains | |
| PAY-07 | Paid | Dev-assisted: mark the payment as paid with the staging payment simulator | Sheet closes by itself; the purchase shows its normal success state | |
| PAY-08 | Top-up page | Saya → PawCoin → Top up → choose a tier → continue | Title + amount card (coins, "Batas bayar") + QR card + logos + steps + pinned save button; no cancel button | |
| PAY-09 | Top-up without QR | When the gateway returns no QR (current staging state) | Placeholder "Mode uji"; save button disabled | |
| PAY-10 | Vet consult page | A vet accepts the consult → pay by QRIS | Same structure as PAY-01; countdown in red (5-minute window); cancel button present | |
| PAY-11 | Toko order number | Pay a pending Toko order | Order number shows the readable `TOKO-…` number, not an internal id | |
| PAY-12 | **New** — paid right after expiry | Dev-assisted: let the sheet reach the expired state, then mark the payment as paid | Within about 10 seconds the sheet closes by itself and the purchase shows success (it no longer stays on "expired") | |
| PAY-13 | **New** — double-tap save | Tap "Simpan Kode QR" twice quickly | Only one image is saved (only one permission dialog on iOS when denied) | |
| PAY-14 | **New** — consult page after time-out | On the vet consult payment page, let the countdown reach 0 | Save button is disabled; the page then leaves as before | |

## F. Diary header

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| DH-01 | Entry card | Open your own Diary | One two-row card: icon + title + one-line subtitle + right arrow; both rows are tappable | |
| DH-02 | Achievement bar | Same | No border, soft shadow, counts in purple | |
| DH-03 | View switch | Same | "Linimasa" / "Kalender" split the row equally | |
| DH-04 | Status bar | Same | Pet switcher sits below the status bar and does not overlap the clock | |
| DH-05 | Title row | Own Diary with 2+ pets | No "Diary {name}" title row; share and edit buttons are on the pet-switcher row, right side | |
| DH-06 | Switching pets | Tap another pet | The first row keeps the same layout while loading — nothing jumps | |
| DH-07 | Visitor / guest | Open someone else's Diary; also as a guest | Title row "Diary {name}" is still shown | |
| DH-08 | Read-only pet lock | Own Diary with a read-only (locked) pet | Lock badge on the avatar is fully visible, not clipped | |

## G. Toko banner — tap target and analytics

Banner targets are configured in Admin → Shop → Top banner. Analytics events are checked in PostHog (filter by your device / app version).

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| BN-01 | Admin validation | Admin banner drawer: try `http://…`, `javascript:…`, a product token that does not exist | Each is rejected with an error; nothing is saved | |
| BN-02 | Impression | Open Toko | `toko_banner_shown` (banner_id, position = 1) is sent once; scrolling up and down does not send it again | |
| BN-03 | Re-entry | Switch to another tab, come back to Toko | `toko_banner_shown` is sent once more | |
| BN-04 | Tap — category | Banner target = Category | `toko_banner_tapped` (target_type = category_list); Toko switches to that category | |
| BN-05 | Tap — product | Banner target = Product (on sale) | `toko_banner_tapped` (product_detail); product detail opens | |
| BN-06 | Tap — external link | Banner target = External link (https) | `toko_banner_tapped` (external_link); system browser opens the link | |
| BN-07 | Not tappable | Banner target = Not tappable | Tapping does nothing; no `toko_banner_tapped` | |
| BN-08 | **New** — product taken off sale | Dev-assisted: banner targets a product, then the product is taken off sale | Banner becomes not tappable (no navigation, no tap event) | |
| BN-09 | **New** — collapsed header | Scroll Toko until the banner image has fully faded; tap an empty spot on the top bar | Nothing happens; no tap event | |
| BN-10 | **New** — double tap | Banner target = Product; double-tap the banner quickly | Product detail opens once (one page to go back from); one tap event | |
| BN-11 | **New** — admin category dropdown | Admin banner drawer: change "Tap action" between the four types | Only the matching field is shown: product token box / category dropdown (Food, Medicine & supplements, Treats, Grooming & care) / https box / nothing. Saving a category shows its name (not a code) in the list. Switching back to "Not tappable" clears the target | |
| BN-12 | **New** — very wide banner | Dev-assisted: a very wide banner (e.g. 10:1). Open Toko, scroll until it is gone, switch tab and come back | No extra `toko_banner_shown` while the banner is scrolled away | |

## H. Short video rolled back (compatibility)

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| VD-01 | No video posting | Open the post composer | Only photos can be added; no video entry | |
| VD-02 | Existing video posts | Open staging posts 549–551 (created as videos earlier) | Shown as normal photo posts using the cover image; no crash | |
| VD-03 | Admin | Admin console | No video whitelist / member-level pages | |

## I. General regression

| ID | Case | Steps | Expected | Result |
|---|---|---|---|---|
| RG-01 | Favourites | Saya → Favourites | Posts and places are listed normally | |
| RG-02 | Photo upload | Create a post with a photo | Uploads normally, the app does not freeze | |
| RG-03 | Cold start | Kill and reopen the app | Opens to Home with no error dialog | |

---

## Notes for testers

- Please record any test data you create (posts, bindings, payments) at the end of your run so it can be cleaned up.
- Report a failure with: case ID, device/OS, steps, screenshot or screen recording, and the time it happened (WIB).
- Analytics cases (BN-02/03/04/05/06/09/10/12) need PostHog access; if you do not have it, mark them ⏭ and note "no PostHog access".
