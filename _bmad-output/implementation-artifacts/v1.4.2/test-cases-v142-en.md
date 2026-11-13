# V1.4.2 Test Cases (stag · Android emulator · admin console in browser)

> Environment: stag backend (api-stag / ops-stag, `petgo-server-stag` @ stag `98adc0d5f`, deployed 2026-11-08) + stag debug build on Android emulator emulator-5554 (connected to api-stag, Firebase / TikTok / Meta disabled). App account user84 (acting for Hanwei).
> Actions by other users: the temporary server script `/tmp/qa_api.py` signs test-account JWTs with the stag `JWT_SECRET` to call APIs (deleted after use). Seeding data directly in `petgo_stag` is allowed; record it in the seed-data list at the end.
> stag limitations: the QRIS gateway is unavailable (pay with PawCoin or the admin console payment simulation); Biteship is a stub; Lark shop reminders are not configured; admin console login goes through Lark, **do not use emergency login** (it sends an alert to all super admins).
> Result: ✅ Pass | ❌ Fail (attach issue number) | ⏭ Skipped (state reason) | ⚠️ Pass with remarks.
> UI fidelity: score each screen against the final UI design, standard >= 95; see the "UI fidelity" section of each volume for the list.

| Volume | Prefix | Cases | Fidelity screens |
|---|---|---|---|
| A. Pet follow & topics (social-topic) | ST- | 230 | 62 (App 43 + admin console 19) |
| B. Shop App (shop) | SH- | 245 | 93 |
| C. Shop admin console (shop-admin) | SA- | 273 | 69 |
| E. Growth points reversal (exp-reverse) | ER- | 9 | No mockup |
| F. Five small requests (1108) | PH/RN/PG/KT/AV- | 27 | — |


---

## A. Pet Follow & Topics Line (social-topic) Test Cases (stag · Android emulator · admin console in browser)

> **Scope**: all 20 stories of V1.4.2 social-topic (per `sprint-status-v1.4.2-social-topic.yaml`) - pet follow (1-1 ~ 1-5), account-level S-tier milestones (2-1 ~ 2-3), share milestone to community (3-1 / 3-2), content topics + browse by day + three hide entry points (4-1 ~ 4-4), @ scope expansion (5-1 / 5-2), admin console topic management and topic page pinning (6-1 / 6-2), ops notification variables and audiences (7-1 / 7-2).
> **Precedence**: the social-topic decision log (R-1 ~ R-5) > PRD > architecture delta > design mockup. Wherever a case differs from the PRD text, it follows the decision log (e.g. R-1 narrowed block, R-3 skipped column, R-4 notification does not include the pet name, R-5 hidden / disabled topic escalation).
> **Design mockups**: App `ui-1-4-2-social-topic-integrated-v1.html` (43 screens: A1–A15 / B1–B4 / C1–C11 / D1–D7 / E1–E6); admin console `ui-admin-1-4-2-social-topic-integrated-v1.html` (19 screens: F1–F9 / G1 / G1a / G1b / G2 / I1–I6). The "Screen" column references these numbers for UI fidelity comparison.
> **Result**: ✅ Pass | ❌ Fail (attach issue number) | ⏭ Skipped (state reason) | ⚠️ Pass with remarks. The result column is filled in during execution.

### Execution Environment and Conventions

| Item | Description |
|---|---|
| Backend | stag: `https://api-stag.tailtopia.id` (container `petgo-server-stag`, env `~/.env.petgo-stag`), database `petgo_stag` (seeding writes allowed), Redis DB 3 (`FLUSHALL` forbidden, only `redis-cli -n 3`) |
| Admin console | `https://ops-stag.tailtopia.id/admin`, Lark login (super admin). ⚠️ Do not use emergency login (it sends an alert to all super admins) |
| App | stag debug build on Android emulator `emulator-5554` (connected to api-stag). The only usable real test account for login, referred to below as **U0** (confirm its user id in the DB before running; it was user 84 last round) |
| "Other people" | Another Google account is unavailable. When another person's action is needed, **sign a JWT for a test account on the server using the stag `JWT_SECRET`** and call the API, or write to the DB directly. Signing: HS256, `iss=petgo` (if env sets `JWT_ISSUER`, use that), `sub=<userId>`, `role=USER`, `iat/exp` filled in as needed (e.g. 2 hours). Example: on the server run `set -a; . ~/.env.petgo-stag; set +a`, then assemble the token with python hmac + base64url; **do not copy the secret off the server** |
| Test roles | **U1** = pet owner (>= 2 ACTIVE pets, including a cat and a dog, ideally plus 1 READ_ONLY pet); **U2** = third party (follows U0's pets, posts under topics, follows U0); **U3** = a person with no relationship to U0 (outside @ scope); **U4** = user with no pets (excluded by AB-37). Prefer virtual / internal QA accounts on stag, **do not pick cloned real users** (stag pushes are real). Record the chosen ids in the seed-data list at the end |
| Execution method tags | Step column starts with [Emulator] [Admin] [API] [DB] tags; when several methods are combined, write them in order |
| Red lines | ① Do not delete cloned old posts / old pets (OSS shares a bucket with production); deletion cases only apply to posts / pets created in this run. ② Pushes are real on stag: push cases may only be triggered for U0 / test roles. ③ Do not perform any operation on production |
| Time | "Daily" always means a natural day in WIB (UTC+7); the topic hotness job runs hourly at `:20` (WIB), browse-by-day cleanup at 03:40, pet follow notification dedup log cleanup at 03:30 |

### 0. Prerequisites and Seed Data (ST-P)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-P-01 | stag contains this line's code and migrations | [DB] `select version, success from flyway_schema_history where version like '20261104%' order by 1;` [API] `/actuator/health` | All 8 migrations `20261104.2000 / 2010 / 2020 / 2030 / 2040 / 2050 / 2055 / 2058` are success; health = UP | — | |
| ST-P-02 | Notification constraint includes new types | [DB] `pg_get_constraintdef` on `ck_notifications_type`, `ck_user_push_mutes_category` | The former contains `PET_FOLLOWED`, `PET_DIARY_POSTED` and has not lost shop-line / existing types (matches the full set in the last migration in the current tree that rebuilt it); the latter contains `PET_DIARY` | — | |
| ST-P-03 | Emulator build version | [Emulator] Settings → About; `adb logcat` to check the request domain | A stag build containing dev_1.4.2, requests go to api-stag | — | |
| ST-P-04 | Sign test JWTs | [API] On the server sign one token each for U1 / U2 / U3 / U4, try `GET /api/v1/me/push-settings` | 200; tokens stay inside the server session only | — | |
| ST-P-05 | Seed U1's pets | [DB] Confirm U1 has 1 ACTIVE cat and 1 ACTIVE dog, and if needed 1 READ_ONLY pet; note the pet ids and cardToken | Pets are complete and recorded | — | |
| ST-P-06 | Seed test topic posts | [API] U1 and U2 each publish 2–3 public posts (mixed Moment / Diary / Tips), body containing topic names dedicated to this run such as `#QaTopik142`, `#qa_kucing` | Post returns 200, response carries `topics:[{name, official, created}]`; new topics have `created=true` | — | |

### 1. Pet Follow (ST-F)

#### 1.1 Backend Relationship and APIs (story 1-1)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-F-01 | Follow someone else's ACTIVE pet | [API] U2 `POST /api/v1/me/pet-follows/{U1 cat id}` [DB] `pet_follows`, `pet_profiles.follower_count` | 200; 1 new row; `follower_count` +1 | — | ✅ 89 follows 119: 200, relationship 1, follower_count 1 |
| ST-F-02 | Repeated follow is idempotent | [API] Call the same again | 200, no new row, count not +1 again | — | ✅ Repeated follow idempotent, count unchanged |
| ST-F-03 | Follow a read-only pet | [API] U2 follows U1's READ_ONLY pet | Succeeds (read-only pets can be followed) | — | ✅ 117 temporarily set to READ_ONLY can be followed (restored to ACTIVE) |
| ST-F-04 | Cannot follow own pet | [API] U1 follows own pet | 422 (ProblemDetail, no stack trace) | — | ✅ Following own pet 422 (ProblemDetail, no stack trace) |
| ST-F-05 | Pet does not exist | [API] Follow a non-existent petId | 404 | — | ✅ Non-existent 404 |
| ST-F-06 | I blocked the owner | [API] U2 `POST /api/v1/me/blocked-users {targetUserId:U1}` then follow U1's pet | 403; after the test `DELETE /me/blocked-users/{U1}` | — | ✅ 89 blocked 115 then follow 403 "You have blocked this user" |
| ST-F-07 | Owner blocked me (not revealed) | [API] U1 blocks U2, U2 follows U1's pet | Succeeds as usual (block not revealed); U1 does **not** receive `PET_FOLLOWED` (see ST-F-34) | — | ✅ 115 blocked 89, 89 followed 117 successfully, 115's PET_FOLLOWED count unchanged (2→2) |
| ST-F-08 | Unfollow | [API] U2 `DELETE /api/v1/me/pet-follows/{id}` | Relationship deleted, count -1; calling again when not following still returns 200 | — | ✅ Unfollow count back to 0; repeated unfollow 200 |
| ST-F-09 | 30 per minute limit | [API] U2 alternately follows / unfollows several pets 31 times within 1 minute (following a person `PUT /users/{token}/follow` also counts) | 429 from the 31st; recovers after 1 minute | — | ✅ 91 got 429 "Too many operations" from the 31st within one minute |
| ST-F-10 | Daily combined quota of 500 | [DB] Seed U3 with 500 rows of same-day USER-source follows + pet follows combined (or confirm the quota check reads the table), then [API] U3 follows a pet / person | Both 429. ⚠️ Seeding volume is large; may instead confirm L1 `PetFollow*` coverage and mark ⏭ | — | |
| ST-F-11 | Block also removes pet follows | [API] U2 follows two of U1's pets → U2 blocks U1 [DB] | All of U2's follows on U1's pets deleted, counts corrected; U1's follows on U2's pets (if any) unchanged | — | ✅ After block, the 89→119 relationship deleted, count 0 |
| ST-F-12 | Unblock does not restore | [API] U2 unblocks U1 [DB] | Previously deleted follows are not restored | — | ✅ Unblock does not restore |
| ST-F-13 | Report-hide does not affect follow | [API] After U2 follows U1's pet, `POST /api/v1/account-reports` reports U1 | Follow relationship still exists | — | |
| ST-F-14 | Pet deletion cascade | [API] U1 creates a test pet P-del → U2 follows → U1 deletes P-del (**only delete the one created in this run**) | The pet's row in `pet_follows` disappears, no notification; U2's Hewan list no longer has this pet | — | |
| ST-F-15 | Owner banned: relationship retained | [Admin] Ban U1 (or [DB] set DEACTIVATED) → [API] U2 queries `GET /users/{U2 token}/pet-follows`, visits U1's pet visitor API, unfollows | The list still has the pet; visitor API treats it as non-existent (404); unfollow succeeds; after the test unban, follow remains as before | A14 | |
| ST-F-16 | Follower banned: relationship retained | [Admin] Ban U2 → [DB] | `pet_follows` not deleted; remains as before after unban | — | |
| ST-F-17 | Owner account deletion cascade | Needs a disposable account that can be deleted (seed it): create account + pet → U2 follows → go through account deletion | Follow relationship deleted, no notification. ⚠️ A new Google account cannot be registered on stag; if a disposable account cannot be created, mark ⏭ (covered by L1) | — | |
| ST-F-18 | Followed pets list API | [API] `GET /api/v1/users/{U2 token}/pet-follows` (U2 themselves / U0's view / guest) | Sorted by follow time descending; each row has avatar, name, species, owner nickname, `iFollow`, `ownerToken`, `iFollowOwner`; carries `petFollowingCount`; guest relationship fields all false; cursor pagination correct | — | ✅ GET /users/{89}/pet-follows (84's view): 117, 118, ownerToken/iFollow/iFollowOwner complete, petFollowingCount=2 |
| ST-F-19 | New fields on visitor profile | [API] U0 calls U1's pet visitor profile / stats | Carries `iFollow`, `iFollowOwner`, `ownerToken`; stats carry `followerCount`; user home page carries `petFollowingCount` | — | |

#### 1.2 App Pet Visitor Page (story 1-4)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-F-20 | Not-followed state | [Emulator] U0 goes to U1's home page → tap the pet avatar to enter the pet visitor page | Full-width purple "Ikuti" below the info card; the 4th cell of the stats bar "N Pengikut"; one-line header: back button + "Diary {name}" + Kirim Hadiah pill | A1 | ✅ 94's QAPet06 visitor page: Diary header + Kirim Hadiah + 4th cell Pengikut + purple Ikuti, A1 fidelity 97 |
| ST-F-21 | Follow | [Emulator] Tap Ikuti [DB] | Button becomes white background with gray border "Mengikuti", Pengikut +1; a new relationship in the DB | A2 | ✅ Mengikuti white background gray border, Pengikut 0→1; pet_follows(84,111), follower_count=1; 94 received PET_FOLLOWED, A2 fidelity 97 |
| ST-F-22 | Tap again to unfollow | [Emulator] Tap Mengikuti | No second confirmation, changes straight back to Ikuti, Pengikut -1 | A1 | ✅ Tapping again unfollows with no confirmation, relationship deleted, count 0 |
| ST-F-23 | Failure rollback | [Emulator] After entering the page turn on airplane mode then tap Ikuti | Button rolls back to the original state + top light toast "Gagal mengikuti…" (per mockup) | A3 | ✅ Airplane mode, tap Ikuti → top "Gagal mengikuti, coba lagi" and rollback, A3 fidelity 97 |
| ST-F-24 | Rate-limit toast | [DB] / [API] Optional: sign a JWT for U0 on the server, within 1 minute follow / unfollow other pets 30 times to fill the quota → [Emulator] immediately tap Ikuti on the visitor page | Bottom light toast (rate-limit copy), no crash | A3 | |
| ST-F-25 | Count formatting | [DB] Temporarily change a U1 pet's `follower_count` to 12345 → [Emulator] refresh the visitor page | Shows "12.3K Pengikut" (>= 10,000 shows K); change back after the test | A1 | |
| ST-F-26 | Owner's own Diary tab | [API] U2 follows U0's pet → [Emulator] U0 Me → Diary | 4th cell of the info card shows the Pengikut count; no follow button, the list cannot be opened; no back button | A4 | ✅ Owner's Diary 4th cell Pengikut, no follow button, A4 fidelity 97 |
| ST-F-27 | No button on own pet | [Emulator] Enter U0's own pet page from a post / home page | The Ikuti button is not shown | A4 | |
| ST-F-28 | No follow button elsewhere | [Emulator] Feed card, post detail; [Browser] U1's pet card H5 | None has a pet follow button (Feed / detail still only follow the author; the H5 main button is still download App) | — | |
| ST-F-29 | Invalidated (non-existent) | [API] U1 creates a test pet P-del2 → [Emulator] U0 follows it, then stays on U1's home page (pet avatar still in the old cache) → [API] U1 deletes P-del2 → [Emulator] tap that pet avatar to enter (deleting a pet cascades to delete follows, so it is no longer in the Hewan list; the most reliable entry is the ban path in ST-F-30) | Top-left back arrow + invalid icon (magnifier + solid circle ×) + "Konten ini sudah tidak ada", **no retry**; back button works | A14 | |
| ST-F-30 | Owner banned = non-existent | [Admin] Ban U1 → [Emulator] U0 taps U1's pet from the Hewan list | Same as A14; the pet is still in the Hewan list and can be unfollowed; restored after unban | A14 | |
| ST-F-31 | Load failure can retry | [Emulator] Turn on airplane mode and enter the pet visitor page → turn off airplane mode and tap "Coba lagi" | Shows "Gagal memuat, silakan coba lagi" + "Coba lagi"; loads normally after tapping | A15 | |

#### 1.3 Notifications and Push (story 1-2 / 1-5)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-F-32 | Followed notification | [API] U2 follows U0's pet → [Emulator] U0 notification center; system notification bar | One `PET_FOLLOWED` in the notification center: "**{U2 nickname}** mulai mengikuti **{pet name}**" (nickname and pet name bold), no follow-back button; system push arrives (controlled by the "new followers" switch) | A12 | ✅ 89 follows Mochi(118) → notification center "QA-Claude-01 mulai mengikuti Mochi" bold, no follow-back, A12 fidelity 95 (the Ikuti Balik of existing follow notifications is light-purple filled, the mockup is white with outline) |
| ST-F-33 | Tap destination | [Emulator] Tap that notification (once in the notification center, once from the system push) | Goes to U2's profile page | A12 | ✅ Tapping the follow notification goes to the QA-Claude-01 home page |
| ST-F-34 | Not sent when blocked | [API] U0 blocks U2 (if U0 has no signed token, block in [Emulator]) → U2 follows U0's pet | Follow succeeds, but U0 does not receive `PET_FOLLOWED`; unblock after the test | — | |
| ST-F-35 | No duplicates within 24 hours | [API] U2 unfollows then follows the same pet again (within 24h) [DB] `notifications` | Only the original 1; unfollowing produces no notification | — | |
| ST-F-36 | Different pets notified separately | [API] U2 follows U0's 2nd pet | Another 1 received (not merged) | A12 | |
| ST-F-37 | Followed pet posts a Diary notification | [Emulator] U0 follows U1's cat on the visitor page → [API] U1 publishes a public Diary linked to that cat | U0 receives `PET_DIARY_POSTED`: "**{cat name}** punya cerita baru", actor is the pet name; system push arrives | A12 | ✅ 84 follows 106 → 89 posts Diary (GROWTH_MOMENT) 571 → 84 receives PET_DIARY_POSTED "QAPet01 punya cerita baru"; DAILY (Moment) 570 does not trigger (consistent with "Diary only") |
| ST-F-38 | Tap to open post | [Emulator] Tap that notification | Opens that post's detail | A12 | ✅ Tapping the Diary notification enters post 571 |
| ST-F-39 | Post deleted | [API] U1 deletes that Diary (created in this run) → [Emulator] tap the notification again | Prompt that the content no longer exists (existing behavior) | A12 | |
| ST-F-40 | 1 per WIB day | [API] U1 publishes 2 more public Diaries linked to that cat the same day [DB] `pet_diary_notify_log` | U0 gets only 1 that day; a new post the next day (WIB) gets 1 more (retest next day, or edit the DB `wib_date` to simulate and note it) | — | |
| ST-F-41 | One post with multiple pets gets only 1 | U0 follows both U1's cat and dog (none received that day) → [API] U1 publishes one Diary linked to both pets | Only 1 received, copy uses the first pet's name | — | |
| ST-F-42 | Already-following owner still receives | [Emulator] U0 also follows U1 themselves (next day or a different pet) | Still receives `PET_DIARY_POSTED` | — | |
| ST-F-43 | Cases where it is not sent | [API] U1 posts: private Diary / Moment / Tips; block in either direction; while U1 or U0 is banned | None produces `PET_DIARY_POSTED` | — | |
| ST-F-44 | Re-sent after review approval | [DB] Find a U1 Diary that went into manual review (or have U1 publish one that triggers review) → [Admin] approve | After approval U0 receives `PET_DIARY_POSTED` (if none received the same day) | — | |
| ST-F-45 | New push settings category | [Emulator] Me → Settings → Push settings | In the INTERAKSI group, after "Pengikut baru" a new "Hewan yang kamu ikuti" is added + description "Diary baru dari hewan yang kamu ikuti", default on; "Pengikut baru" description has two lines: "Seseorang mulai mengikutimu" / "Seseorang mulai mengikuti hewanmu" | A13 | |
| ST-F-46 | Turn off category: no push but in notification center | [Emulator] Turn off "Hewan yang kamu ikuti" [API] `GET /me/push-settings` confirms `PET_DIARY` off → U1 posts a Diary next day | No system push; the notification center still has it; turn it back on after the test | A13 | |
| ST-F-47 | New-follower switch governs PET_FOLLOWED | [Emulator] Turn off "Pengikut baru" → [API] U2 follows another of U0's pets | No push, present in the notification center; turn it back on after the test | A13 | |

#### 1.4 Following List / Following Feed (story 1-3 / 1-5)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-F-48 | Two Following filters | [Emulator] Me → Mengikuti | At the top of the Following tab are "Pengguna N" / "Hewan N" pills, default Pengguna, no "All"; N matches the DB | A5 | |
| ST-F-49 | Hewan row | [Emulator] Switch to Hewan | Each row has a round pet avatar + name + species + "milik @owner nickname" + permanent "Mengikuti"; tapping a row enters the pet visitor page | A6 | |
| ST-F-50 | Row kept after unfollow | [Emulator] Tap Mengikuti on a row | Becomes light-purple "Ikuti", the row stays; after pull-to-refresh the row disappears | A7 | |
| ST-F-51 | Own Hewan empty state + recommendations | [Emulator] Unfollow all, then enter Hewan | Empty state "Kamu belum mengikuti hewan apa pun"; below it the "Kenalan sama anabul lain" recommendation cards (square card + avatar + days + Ikuti); tapping Ikuti follows directly; tapping a card enters the visitor page | A8 | |
| ST-F-52 | Recommendations hidden when empty / failed | [Emulator] With the recommendation area showing, go offline and refresh; [DB] or confirm the recommendation API returns empty | The whole recommendation block is hidden, only the empty state remains | A8 | |
| ST-F-53 | Someone else's list | [Emulator] Enter U2's home page → Mengikuti → Hewan | Can see the pets U2 follows; **no** button in rows; when empty shows only "Belum mengikuti hewan apa pun", no recommendations | A9 | |
| ST-F-54 | Four-cell Mengikuti counts users only | [Emulator] The four cells on the Me page and U2's home page | Mengikuti count = Pengguna count, excluding followed pets | A5 | |
| ST-F-55 | Followers tab unchanged | [Emulator] Switch to the Pengikut tab | Same as 1.4.1, no filter | — | |
| ST-F-56 | Following feed merges followed-pet Diaries | [Emulator] U0 only follows U1's cat, not U1 → [API] U1 publishes a public Diary linked to the cat + a Moment + a private Diary → [Emulator] Social → Following | Only that public Diary appears; the Moment and the private Diary do not | A10 | |
| ST-F-57 | Dedup by post | [Emulator] U0 also follows U1 themselves; U1 publishes a Diary linked to both cat and dog (U0 follows both) | That post appears only once in the following feed | A10 | |
| ST-F-58 | reason field | [API] Capture or use U0's session to query `GET /api/v1/me/following-feed` | Each item carries `reason` (followed_user / followed_pet); first page carries `followingUserCount`, `followingPetCount` | A10 | |
| ST-F-59 | Block visibility (R-1 narrowed) | [API] U1 blocks U0: ① while U0 does not follow U1 themselves ② while U0 also follows U1 themselves (blocking deletes U1→U0's follow, not U0→U1) → [Emulator] refresh the following feed | ① U1's pet Diaries do not appear; ② U1's content (including pet Diaries) still appears via "followed people" (keeps the 1.3.4 status quo, as R-1 expects); unblock after the test | A10 | |
| ST-F-60 | No new activity | [Emulator] U0 only follows one pet with no recent Diary, and the followed people have no recent posts (or [DB] temporarily reduce U0's follows to 0, keeping only pet follows) | Shows "Belum ada postingan baru" + "Postingan baru dari orang dan hewan yang kamu ikuti akan muncul di sini." + 5 recommended people to follow + "Jelajahi Discover ›" | A11 | |
| ST-F-61 | Follows and pet follows both 0 | [DB] Temporarily clear U0's follows and pet follows (record a backup, restore after the test; note the official account auto-follow optout) | Shows the existing "go follow others" empty state rather than A11 | — | |
| ST-F-62 | Pet recommendations exclude followed | [Emulator] Social home "Kenalan sama anabul lain" horizontal row + collection page; follow one of them then refresh | Followed pets no longer appear | — | |
| ST-F-63 | Recommendation sort by likes in last 14 days | [DB] Hand-compute the top 10 by the PRD definition (latest public growth record WIB date descending → likes in last 14 days → last post time → id), compare with [API] `GET /api/v1/me/pet-recommendations` | Order matches; likes older than 14 days are no longer counted | — | |
| ST-F-64 | Pagination | [API] Page to page 2 with cursors on following-feed and pet-follows | No duplicates, no omissions | — | |

### 2. Account-level S-tier Milestones (ST-M)

> ⚠️ U0 on stag has most likely already followed people and saved posts, and **the launch backfill (migration ⑥) has marked both as completed and already celebrated**. To see the "first time" effect on the emulator, first clear U0's flags and completion records per ST-M-01 (seed data; no need to restore after the test but record it). Backend trigger rules are verified separately through the API with U1 / U3.

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-M-01 | Check backfill result | [DB] `user_milestone_flags`, `milestone_completions` (new codes), `growth_ledger where rule_code='LAUNCH_BACKFILL' and idem_key like 'BACKFILL142:%'` | Accounts that had USER-source follows / post saves before launch all have a flag; their ACTIVE pets' new codes are completed with non-null `celebrated_at`; one +5 per account per flag, `growth_accounts` total in sync; record the number of backfilled accounts in the result | — | |
| ST-M-02 | Backfill has no notification / popup | [DB] No new-code `MILESTONE_SM_NODE` before or after the backfill time; [Emulator] U0 enters the milestone list | No notification, no replayed popup, no level-up celebration | — | |
| ST-M-03 | List contains the two new entries | [Emulator] U0's cat / dog milestone list; [API] visitor view | Newly added "Teman pertama", "Simpan pertama" (grouping and order per code); visitor view denominator is 35 for cat/dog, 20 for other species | B3 | |
| ST-M-04 | Reset U0 to retest | [DB] After backup, delete U0's `user_milestone_flags` (FIRST_FOLLOW_USER / FIRST_COLLECT_CONTENT), delete the completion records of the new codes for each of U0's pets (or set to not completed), record the seed data | U0's new codes return to not completed | — | |
| ST-M-05 | First follow of a person (multiple pets) | [Emulator] U0 (>= 2 ACTIVE pets) manually follows a new user [DB] | All ACTIVE pets complete the species-specific code (C-S19 / D-S19 / G-S12), read-only pets do not complete; **no celebration popup** at the moment of following; growth points only 1 row +5 (idem `MILESTONE_ACCT:{U0}:FIRST_FOLLOW_USER`) | — | |
| ST-M-06 | Only 1 notification, no push | [Emulator] Notification center; system notification bar | Only 1 `MILESTONE_SM_NODE`, copy does not include the pet name (R-4); no system push | B4 | |
| ST-M-07 | Tap notification to replay celebration | [Emulator] Tap that notification | Goes to the milestone list of the currently selected pet and shows the celebration page once (if the current pet is not ACTIVE, the first ACTIVE one) | B4 / B2 | |
| ST-M-08 | Multi-pet celebration page | Same as above | Shows all ACTIVE pets' avatars and names, does not show KOLEKSI; title "Teman pertama! 🤝", body "Kamu mulai mengikuti seseorang — makin seru bareng sesama pecinta anabul." | B2 | |
| ST-M-09 | Celebrated recorded per account | Close the celebration page → enter another pet's milestone list [DB] | Does not pop up again; `celebrated_at` of that code is written for all pets under the account | B3 | |
| ST-M-10 | Entering the list directly also replays | After reset (ST-M-04) save a post → do not tap the notification, go straight from the profile into any pet's milestone list | Pops up once "Simpan pertama! 🔖" / "Postingan pertama sudah tersimpan — buka lagi kapan saja." | B2 | |
| ST-M-11 | Single-pet celebration page | [DB] Temporarily set U0's other pets to READ_ONLY (record, restore after the test) → reset and trigger once | Celebration page same as the existing single-pet style | B1 | |
| ST-M-12 | Two pending celebrations show only one | Reset both → do both follow + save → enter the list | Only one pops up, the other is marked celebrated together; if there is also an uncelebrated M / L tier, that one pops up | — | |
| ST-M-13 | Saving own post also counts | [API] U1 (flag cleared) saves their own post [DB] | Triggers FIRST_COLLECT_CONTENT | — | |
| ST-M-14 | Cases that do not trigger | [API] U3 (flag cleared): follow a pet, save a place `POST /places/{token}/collect`; [DB] system auto-follows the official account (new accounts covered by L1) | None writes a flag or completes | — | |
| ST-M-15 | Unfollow / unsave does not revoke | After completing, unfollow, unsave, then follow / save again | Still completed; does not trigger again, no more +5 | — | |
| ST-M-16 | Account with no pets | [API] U4 (no pets, flag cleared) follows a person for the first time | Writes flag + growth points +5, writes no completion, no notification | — | |
| ST-M-17 | Pets created later complete directly | [API] U4 creates a pet; or restore U1's READ_ONLY pet to ACTIVE | New code is directly "completed and celebrated", no celebration, no growth points, no notification | — | |
| ST-M-18 | OTHER species and mixed-species targetRef | [DB] An OTHER-species pet completes G-S12 / G-S13; mixed-species account notification `target_ref` | G codes are correct; `target_ref` is the code of the first ACTIVE pet's species | — | |
| ST-M-19 | Concurrency triggers only once | [API] U3 (flag cleared) concurrently calls `PUT /users/{token}/follow` on two users | Only 1 flag, 1 +5, 1 notification | — | |
| ST-M-20 | Original per-pet milestone regression | [Emulator] U0 completes an ordinary S-tier (e.g. Diary-related posting) | Instant celebration, growth points rule "max 5 times per code" unchanged | — | |
| ST-M-21 | Badge assets | [Emulator] The badges of the two new entries in the list and celebration page | Mapped to `first_friend` / `first_save`; when assets are not yet delivered, `MilestoneBadge` fallback is used (record as ⚠️ assets pending) | B1 / B3 | |

### 3. Share Milestone to Community (ST-MC)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-MC-01 | New button group | [Emulator] Any celebration page (the one popped up in ST-M-07 works) | Sticky bottom "Bagikan ke Komunitas" / outlined "Bagikan ke luar" / "Lihat semua milestone →", top edge fades, content area scrolls; for L-tier long body text the primary button is still on the first screen | D1 | |
| ST-MC-02 | Loading state + anti double-tap | [Emulator] Quickly tap "Bagikan ke Komunitas" 3 times | Spinner immediately and not tappable; the compose page opens only once | D2 | |
| ST-MC-03 | Post to community (single pet) | [Emulator] Tap "Bagikan ke Komunitas" on a single-pet milestone celebration page | Celebration page closes → compose page: type Diary, this pet preselected, 1 milestone card image, body "{pet name} baru membuka tonggak "{short title}"! 🎉", no #topic | D4 | |
| ST-MC-04 | Account-level prefill | [Emulator] Tap the same button on an account-level milestone (>= 2 pets) | All ACTIVE pets preselected; body "Kami baru membuka tonggak "{short title}"! 🎉"; the card shows all pets | D5 / D7 | |
| ST-MC-05 | Publish succeeds | [Emulator] Publish directly | The post appears in the Discover Feed and that pet's growth archive; U2, who follows that pet ([API] check U2's following-feed), can see it | — | |
| ST-MC-06 | Cancel does not pop back | [Emulator] Cancel / back on the compose page | Returns to the original page, the celebration page does not reappear | — | |
| ST-MC-07 | Card image generation failure | A card image failure must be constructed locally (e.g. an invalid pet avatar URL: [DB] temporarily change the pet avatar to a non-existent objectKey and then trigger) | Light toast above the button group "Gagal membuat kartu…", the compose page does not open, the celebration page does not close and can retry; restore the avatar after the test. ⚠️ If it cannot be constructed, mark ⏭ and rely on L0 | D3 | |
| ST-MC-08 | Share outside | [Emulator] Tap "Bagikan ke luar" | Brings up the system share sheet, content is copy + H5 link `/m/{shareToken}` | D1 | |
| ST-MC-09 | Share outside degrades offline | [Emulator] After airplane mode tap "Bagikan ke luar" | The share sheet still appears, with copy only; the sheet is not swallowed | D1 | |
| ST-MC-10 | 6 popup entry points | [Emulator] Tap the primary button on the celebration page popped up from each of: auto-popup after posting (host compose page already closed), health record, instant celebration, milestone list replay, check-in picker, companion 100 / 365 days ([DB] adjust the pet's home date to trigger) | All open the prefilled compose page; the auto-popup after posting in particular must pass (the original "tap does nothing" defect) | D1 | |
| ST-MC-11 | Companion milestone card face | Same as above, companion 100 / 365 days | Card face is the corresponding L-tier badge and title | D6 | |
| ST-MC-12 | Milestone card visuals | [Emulator] Open the card image full-size in the compose page; after publishing save the original in post detail (or [DB] fetch the OSS original URL and download) | 1:1; rosette (badge + tier color ring + ribbon) + tier chip + short title + pet avatar and name · completion date + TailTopia wordmark; purple gradient background, white footer; M-tier ribbon #6C48AE + white stroke; no QR code, no watermark; 1–5 pets laid out correctly | D6 / D7 | |

### 4. Content Topics (ST-T)

#### 4.1 Topic name rules and posting (story 4-1 / 4-4)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-T-01 | Create a user topic | [Emulator] U0 publishes a public Moment "#QaKucingLucu hari ini" | Published successfully; [DB] `topics` has a new USER topic with display form `QaKucingLucu`; `content_topics` has the association | C5 | ✅ 572 "#QaKucingLucu" created a USER topic, display form preserved |
| ST-T-02 | Case-insensitive | [API] U2 posts "#qakucinglucu" | Goes into the same topic, display form unchanged | — | ✅ GET /topics/QAKUCINGLUCU hits the same topic, displayName=QaKucingLucu |
| ST-T-03 | Parsing boundaries | [Emulator] Post "#2024 #1 #kucing, #a #(Chinese text) #ab_12 #😀cat" | `#2024`, `#1`, `#a` (1 character) are plain text; for `#kucing,` only `#kucing` is a topic; letters of any language (e.g. Chinese) can form a topic; the topic ends at an emoji | C5 | ✅ #2024 #1 #a do not form topics; for "#kucingQA," only kucingQA is taken |
| ST-T-04 | 31 characters | [API] Post a 31-character topic name | Does not become a topic (or is truncated per the rules; verify against the shared sample `topic-parse-cases.json`) | — | ✅ 31 characters does not form a topic |
| ST-T-05 | Duplicates in one post count once | [Emulator] "#qa1x #QA1X #qa2x" | Only 2 topics, using 2 slots | — | ✅ #QaDup #qadup #QaDua → 2 |
| ST-T-06 | Maximum 5 | [Emulator] Write 6 different topics in the body and publish | The first 5 are associated and highlighted/tappable; the 6th is plain text and no topic is created | C4 / C5 | ✅ 6 topics, only the first 5 taken |
| ST-T-07 | All three types can carry topics | [Emulator] Post one each of Moment / Diary (public) / Tips with a topic | All three appear on the topic page | — | ✅ Moment / Diary both associated (Tips is posted from the admin console, see ST-T-11) |
| ST-T-08 | Private Diary is not associated | [Emulator] Private Diary with "#QaPrivTopik" | No topic created, not associated; not visible on the topic page | — | ✅ Private Diary creates no topic, not associated |
| ST-T-09 | Sensitive words | [API] Post with a sensitive word in the topic name | The whole post is handled per the existing moderation rules; topics are not moderated separately | — | |
| ST-T-10 | Post response / event | [API] Check the post response; [DB] or logs for the post event | Response `topics:[{name, official, created}]`; all outputs that return post body text (Feed / detail / Following feed / profile / pet Diary list) carry `topicNames` (only ACTIVE / HIDDEN) | — | |
| ST-T-11 | Admin-console posts also write topics | [Admin] Publish a seed post containing "#QaTopik142" using a publishing identity (official account / virtual account) | Associations are created the same way | — | |

#### 4.2 Input highlighting and # suggestions (story 4-4)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-T-12 | Typing only # | [Emulator] Type "#" in the post body on the publish page | Panel shows popular topics, official first; no search box, no "# Topik" button | C2 | |
| ST-T-13 | Keep typing for suggestions | [Emulator] Type "#Qa" and pause 300ms | Prefix match, official first, popular user topics after; panel sits against the top of the keyboard (max 220), cursor line visible | C2 | |
| ST-T-14 | New topic with no match | [Emulator] Type "#QaBrandNew999" | Panel shows only one row "#QaBrandNew999 · Topik baru"; tap it or type a space to confirm | C3 | |
| ST-T-15 | Hidden / disabled excluded from suggestions | [Admin] Hide one topic and disable another → [Emulator] type their prefixes | Neither appears in suggestions | C2 | |
| ST-T-16 | 6th # prompt | [Emulator] With 5 topics already, type "#" again | No suggestions; a hint at the **top of the screen**: "Maksimal 5 topik per postingan" | C4 | |
| ST-T-17 | Highlighting while typing | [Emulator] 6 topics + 1 @ picked from the @ list + a hand-typed "@abc" | The first 5 topics and the picked @ are purple; the 6th topic and hand-typed @abc are in normal color; after deleting a character inside an @, that @ becomes invalid and normal color | C4 | |
| ST-T-18 | Comment box highlighting | [Emulator] Pick a person with @ in the comment box | @ is purple; `#` is not parsed when displayed in the comment section | — | |
| ST-T-19 | Keyboard docking | [Emulator] Use the soft keyboard (turn off hardware keyboard input: AVD settings or `adb shell settings put secure show_ime_with_hard_keyboard 1`) | Panel sits against the top of the soft keyboard; ⚠️ if the emulator can only show a floating Gboard, mark ⚠️ pending real device | C2 | |
| ST-T-20 | Suggestion failure | [Emulator] After typing #, turn on airplane mode and keep typing | No crash; panel shows a failure state / keeps the new-topic row (per the code) | — | |

#### 4.3 Display and topic detail page (story 4-2 / 4-3)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-T-21 | Topics in body are tappable | [Emulator] Feed cards and post detail | `#topic` is purple; tapping a topic opens the topic page, tapping elsewhere on the card opens the post; @ behavior unchanged; body is shown exactly as the author wrote it | C5 | |
| ST-T-22 | Official topic page | [Admin] Create an official topic (with ID / EN descriptions) → [Emulator] open the topic | Title row (back button + #name) + "Resmi" + description (matching the App language); no cover, no post count, no share button | C7 | |
| ST-T-23 | User topic · Populer | [Emulator] Open a user topic, switch to Populer | Terbaru is newest first; Populer is sorted descending by "likes + comments × comment_weight" ([DB] hand-calculate and compare the first 5) | C8 | |
| ST-T-24 | Loading | [Emulator] Enter the topic page with developer-options network throttling / weak network | Skeleton screen | C6 | |
| ST-T-25 | Empty state | [Emulator] Open a topic with no public posts (e.g. `#QaPrivTopik` with only a private Diary must already exist: [DB] create an ACTIVE topic with 0 posts) | Icon + "Belum ada postingan di topik ini" + "Yuk, jadi yang pertama berbagi di sini!" + "Buat Postingan"; below, "Topik populer lainnya" shows at most 10 (excluding current / hidden / disabled), arrow aligned right | C9 | |
| ST-T-26 | Empty state post prefill | [Emulator] Tap "Buat Postingan" | Opens the publish page, body prefilled with "#topicname " | C9 | |
| ST-T-27 | No recommended topics | [DB] Temporarily set all other topics to HIDDEN or heat 0 → open the empty topic page | The recommendation block is hidden entirely; restore after testing | C9 | |
| ST-T-28 | Nonexistent / disabled | [Emulator] Deep link or tap in the body to a nonexistent topic; [Admin] disable a topic, then enter from an old post | "Topik tidak ditemukan" / "Topik ini sudah tidak tersedia", with a back button; in the old post body the topic becomes plain, non-tappable text | C10 | ✅ Nonexistent topic returns 404 (App-side C10 pending emulator) |
| ST-T-29 | Load failure | [Emulator] Enter the topic page in airplane mode → restore network and tap retry | Failure state + retry; works normally after tapping | C11 | |
| ST-T-30 | Hidden topic still accessible | [Admin] Hide a topic → [Emulator] tap into it from the body | Topic page is viewable as normal and still tappable in the body; but it does not appear in the home topic row or suggestions | C8 | |
| ST-T-31 | List filtering | [API] U1 blocks U0 → [Emulator] U0 enters a topic where U1 has posted | U1's posts do not appear; posts hidden by reports / from deactivated authors are filtered likewise; rate-limited posts still appear; private ones do not appear | C8 | |
| ST-T-32 | Guest access | [Emulator] Log out (guest), enter the topic page, like / tap "Buat Postingan" | Can browse; interactions trigger login | C7 | |
| ST-T-33 | API contract | [API] `GET /api/v1/topics/{name}`, `/topics/{name}/posts?sort=latest|popular`, `/topics/{name}/related`, `/topic-suggestions?q=`, `/topics/home-row` (including guest, with / without token) | Detail carries display form / `official` / description; DISABLED / nonexistent returns 404; latest uses cursor, popular uses page number; suggest requires login, excludes HIDDEN / DISABLED, official first; related returns 10, excluding current and HIDDEN / DISABLED | — | ✅ home-row / detail / related / suggestions (empty, q=qa official first) / posts?sort=popular all 200 with complete fields |
| ST-T-34 | Display form change | [Admin] Change the display form of `#qakucinglucu` to `QAKucingLucu` → [Emulator] | Topic page title, home topic row, and suggestions show the new form; the original post remains under the topic, and the body still shows the author's original form | C7 | |

#### 4.4 Home topic row and heat (story 4-2 / 4-3)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-T-35 | Discover topic row | [Emulator] Social → Discover | One horizontally scrolling row of topics between the category tabs and the pinned card (text links + official ones with a Resmi badge), at most 5; scrolls with the Feed and does not stick to the top; not shown on the Following tab | C1 | |
| ST-T-36 | Ops-designated first | [Admin] Put 2 topics in the home topic row (positions 1, 2) → [Emulator] refresh | These 2 are placed first by position, the rest fill up to 5 by heat | C1 | |
| ST-T-37 | Fewer than 1 hides the whole row | [DB] Temporarily set `featured_order` to null and `heat_score` to 0 for all topics (back up first) → [Emulator] | The whole row is hidden; restore after testing | C1 | |
| ST-T-38 | Tap to enter topic page | [Emulator] Tap an item in the topic row | Opens the corresponding topic page | C1 | |
| ST-T-39 | Heat job | [DB] After WIB `:20` passes, check `topics.heat_score / post_count`; hand-calculate by formula (new posts in last 7 days × 1 + sum of daily unique viewers over last 7 days × 0.1 + likes and comments in last 7 days × 1) | Matches the hand calculation; `post_count` is refreshed in sync | — | |
| ST-T-40 | topicNames after disabling | [Admin] Disable a topic → [API] query the detail of a post containing that topic | `topicNames` does not include it; hidden ones are still included | — | |
| ST-T-41 | Same name not associated while disabled / restore | [Admin] Disable `#QaTopik142` → [API] U2 posts with that topic → [Admin] restore | Posts made during the disabled period are not associated; after restore, posts from before disabling return under the topic, and the one made during the disabled period is not backfilled | — | |
| ST-T-42 | Topic page pinned slot yields | [Admin] Configure a content pin for a topic (post X) → [Emulator] enter the topic page | Post X is shown at the top with "Disematkan"; post X does not appear again in the list | C7 | |

#### 4.5 Daily views (story 4-1, FR-155)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-T-43 | First / repeat on the same day | [Emulator] U0 opens a post detail of U1 twice; [DB] `content_post_view_days` | Only 1 row for the day (`view_date` is the WIB date) | — | |
| ST-T-44 | Counted again next day | Open again the next day (WIB) | 1 new row for the next day | — | |
| ST-T-45 | Author's own / Feed impressions not counted | [Emulator] U0 opens own post; scrolls past U1's post in the Feed without opening it | Daily table unchanged | — | |
| ST-T-46 | Cumulative metrics unchanged | [Admin] Content list columns "Views / Unique viewers"; [DB] `content_post_views` | Consistent with the pre-launch metrics | — | |
| ST-T-47 | 30-day cleanup | [DB] Insert a daily row from 31 days ago, check after 03:40 the next day | Already cleaned up (or confirm the job log) | — | |

#### 4.6 Temporarily hidden three entry points (FR-165)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-T-48 | No venue entry on home | [Emulator] Social home | No "Tempat Ramah Hewan" entry row, no gap | C1 | |
| ST-T-49 | Two Kenali Hewanmu cards hidden | [Emulator] Enter Kenali Hewanmu | No "Jejak Anabul" or "Boarding Pass"; the remaining cards move up with no gap | — | |
| ST-T-50 | Other paths unchanged | [Emulator] Enter from a post with a check-in location, saved venues, venue share link, order detail | Venue / footprint / boarding pass pages behave the same as before launch | — | |

### 5. Expanded @ candidate scope (ST-AT)

> If U0's token cannot be extracted from the emulator, run the API cases with the signed tokens of U1 / U2, and check the UI for the emulator cases.

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-AT-01 | Quick list | [Emulator] Type "@" in the post body on the publish page without typing anything else | First lists the people I follow (newest follow first); if fewer than 30, fills up with interaction candidates without duplicates; the "Cari nama…" filter box has been removed | E1 | ✅ Quick list has 19 people: followed ones first (following), filled with interaction candidates (interacted), ≤30 |
| ST-AT-02 | Quick list rules | [API] U2 `GET /api/v1/me/mention-candidates`; [DB] U2's follows (by created_at descending) and `mention_candidates` | Order and fill match; total ≤ 30; each item carries `relation` | E1 | |
| ST-AT-03 | Typed query | [Emulator] Type a nickname fragment after "@" | Queries after a 300ms pause, shows searching → results; contains-match across the three groups (case-insensitive), ≤20 items, ordered followed → following me → interacted | E2 / E3 | ✅ q=claude contains-match, case-insensitive, 10 items |
| ST-AT-04 | People who follow me can be found | [API] U2 follows U0 (U0 does not follow U2, no interaction) → [Emulator] U0 types U2's nickname | U2 can be found (relation = follower) | E3 | ✅ Follower 94 can be found (relation=follower) |
| ST-AT-05 | Out of scope not found | [Emulator] Type U3's nickname | No-result state "…" (per the design mockup) | E4 | |
| ST-AT-06 | Special character escaping | [API] `q=%` / `q=_` | Does not return everything; matched literally | — | ✅ q=% matched literally, 0 items |
| ST-AT-07 | Discard stale results | [Emulator] Change the query quickly several times in a row | Only the result of the last query is shown, no flashing back to old results | E3 | |
| ST-AT-08 | Query failure | [Emulator] After typing "@", turn on airplane mode and keep typing | Failure state inside the panel | E5 | |
| ST-AT-09 | No one at all | [DB] Temporarily clear U0's follows (including the official account, note the optout impact) and interaction candidates → [Emulator] type "@" | "Belum ada orang yang bisa di-@" + "Ikuti seseorang dulu, nanti kamu bisa menyebut mereka di sini"; restore after testing | E6 | |
| ST-AT-10 | Official account | [API] Check the quick list of an account that only follows the official account; the official account (publishing identity) posts and @s any user | Quick list includes the official account; the official account can @ anyone (its followers are all users) | — | |
| ST-AT-11 | Block / deactivation excluded | [API] U2 blocks U0 → U0 / U2 query each other; DEACTIVATED users | A block in either direction, deactivation, or ban means not in candidates | — | ✅ 93, who blocked 84, cannot find them |
| ST-AT-12 | Comments use the same set | [Emulator] @ in the comment box | Same scope as posting; the comment overlay still sits above the input bar, only the filter box is removed | — | |
| ST-AT-13 | Server-side validation on posting | [API] U2 posts with `mentionedUserIds` containing U3 (out of scope) + U0 (in scope) | The post is published as usual; U3 is silently dropped, no notification; U0 receives a mention notification | — | |
| ST-AT-14 | More than 5 people | [API] 6 entries in `mentionedUserIds`; [Emulator] pick the 6th person | API returns 422; the App prompt position is the same as before | — | |
| ST-AT-15 | Unfollowing does not affect sent @ | [API] U2 @s U0 then unfollows | The @ and notification in the already published post are unaffected | — | |
| ST-AT-16 | Panel docks to keyboard | [Emulator] @ panel on the publish page | Sits against the top of the keyboard, same form as the # panel; ⚠️ if the emulator has no soft keyboard docking, pending real device | E1 | |

### 6. Admin-console topic management and topic page pinning (ST-ADM)

> All run in the `ops-stag` admin-console browser; switch language with the language option at the top right of the admin console (ZH / EN / ID). After write operations, [DB] `admin_audit_log` (use the actual audit table name) has records.

#### 6.1 Topic management (story 6-1)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-ADM-01 | Menu and permissions | [Admin] Super admin views the sidebar; [DB] grant a read-only role account `content.topic_view` (skip if none) | Under the "✍️ Content" group, "Topics" appears after "Content tags"; with VIEW only, it is visible but write operations return 403 / buttons are unavailable | F1 | |
| ST-ADM-02 | List | [Admin] Open the topics page | 8 columns (Topic name / Type / Status / Post count / 7-day heat / Home topic row toggle + position / Created at WIB / Actions); summary bar with 5 cells; default sort by heat descending; pagination | F1 | |
| ST-ADM-03 | Filter / search | [Admin] Filter by type and status, search by name | Results are correct; with no results shows "No topics match the criteria." | F9 | |
| ST-ADM-04 | Create an official topic | [Admin] "+ New topic" → name `QaOfficial142` + description ID / EN | Success toast "Topic #QaOfficial142 created", returns to the list and locates the new row; type "Official" | F2 | |
| ST-ADM-05 | Name validation | [Admin] Enter name `1`, `2024`, `a b`, 31 characters, `#xx` | Each reports an error and cannot be saved | F2 | |
| ST-ADM-06 | Upgrade on name clash with a user topic | [Admin] Create `qakucinglucu` (a user topic already exists) | Yellow bar under the name "A user topic #… already exists (N posts)…" (post count is live); saving shows a native confirm with 3 lines; after confirming, it is upgraded to official, posts are kept, display form changes to what was entered in the admin console; toast "Upgraded to an official topic…" | F3 | |
| ST-ADM-07 | Name clash with an official topic | [Admin] Create the same name again | Reports an error and cannot be saved | F2 | |
| ST-ADM-08 | Name clash with hidden / disabled (R-5) | [Admin] First hide a user topic, then create the same name; then create the same name for a disabled user topic | Hidden one → upgraded as usual and stays hidden; disabled one → upgrade refused, prompt to restore first | F3 | |
| ST-ADM-09 | Edit an official topic | [Admin] Open the official topic drawer | Header summary (type / status / live post count / heat / Nth position in the home topic row / created at) as a single column of "label → value"; description is editable; toast "Topic updated" | F4 | |
| ST-ADM-10 | Edit a user topic | [Admin] Open the user topic drawer, change the display form: change case only; then try adding/removing characters | Changing case succeeds; adding / removing / replacing characters → error slot "Only letter case can be changed…"; the description area shows gray text "User topics cannot have a description…" | F5 | |
| ST-ADM-11 | Put into the home topic row | [Admin] Turn on the toggles of 5 topics in turn | Each is placed last, toast "Added to the home topic row (position N)" | F1 | |
| ST-ADM-12 | 6th reports an error | [Admin] Turn on the 6th | Below the table: "The home topic row holds at most 5 topics. Remove one before adding another." | F8 | |
| ST-ADM-13 | Changing position swaps | [Admin] Change position 5 to 2; then enter 0 / 6 | Swaps with the original position 2, toast "Home topic row order updated"; out-of-range shows an error under the row "Position must be 1–5" | F1 | |
| ST-ADM-14 | Turning off moves the rest forward | [Admin] Turn off position 2 | Toast "Removed from the home topic row"; the following ones move forward in turn | F1 | |
| ST-ADM-15 | Hide | [Admin] Hide a topic that is in the home topic row | Takes effect immediately (no confirmation); toast contains "…removed from the home topic row"; the following ones move forward; the toggle can no longer be turned on | F4 | |
| ST-ADM-16 | Disable | [Admin] Disable a topic that has an active topic page pin | Native confirm with 3 lines; after confirming: topic page inaccessible (App C10), removed from the home topic row, active pin ends (`terminated_at` written, admin console shows "Ended"), `disabled_at` recorded | F6 | |
| ST-ADM-17 | Restore hidden | [Admin] Restore a hidden topic | No confirmation, restored directly; toast "Topic restored" | F7 | |
| ST-ADM-18 | Restore disabled | [Admin] Restore a disabled topic | Native confirm with 3 lines (including post count); after restore, posts from before disabling come back, same-name posts during the disabled period are not backfilled, pins are not automatically restored | F7 | |
| ST-ADM-19 | Concurrent position changes | [Admin] Change positions simultaneously in two browser tabs | Consistent result, no duplicate positions ([DB] `featured_order` unique) | F1 | |
| ST-ADM-20 | Audit | [DB] Audit table | Create / edit / upgrade / hide / disable / restore / add / remove / change position all have records | — | |
| ST-ADM-21 | Post count lag acceptable | [API] Right after posting, check the admin-console list and drawer | List post count lags at most 1 hour (R-5); drawer and the upgrade yellow bar are live | F1 / F4 | |

#### 6.2 Topic page pinning (story 6-2)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-ADM-22 | Tabs | [Admin] Pin management | "Home Feed / Topic page" tabs; Home Feed is the existing list, unchanged; the page description now includes "and topic pages" | G1 | |
| ST-ADM-23 | Topic page tab | [Admin] Switch to "Topic page" | Columns normal; lists normal and hidden topics (topic name + type, status, currently active pin, schedule count, heat, "Manage pins →"), disabled not listed; whole row is clickable | G1a | |
| ST-ADM-24 | Schedule page | [Admin] Click a topic row | Breadcrumb "Pin management / Topic page / #topic"; structure same as the existing pin list; empty table "This slot has no pin schedule yet." | G1b | |
| ST-ADM-25 | Create a content pin | [Admin] "+ New pin" → pin published content → select content | Slot is read-only "Topic page · #topic"; the post picker only lists published public posts under that topic; with no candidates: "No public content under this topic can be pinned yet." | G2 | |
| ST-ADM-26 | Promo card | [Admin] Create a promo card (image + title + link) | Not restricted by topic, saved successfully "Pin schedule saved" | G2 | |
| ST-ADM-27 | Overlap validation | [Admin] Create another with an overlapping time range in the same topic slot | Reuses the existing overlap error | G2 | |
| ST-ADM-28 | Topic dropdown | [Admin] Switch the slot / topic dropdown when creating from the Home Feed side | Only lists normal and hidden, not disabled | G2 | |
| ST-ADM-29 | End-to-end online/offline | [Admin] Set a schedule to start in 5 minutes and end in 15 minutes → [Emulator] refresh the topic page before and after the time | Before start, no pin slot (takes no space); shown during the active period (post or promo card); automatically goes offline at the end time | C7 | |
| ST-ADM-30 | Pinned post taken down | [API] The author deletes the post newly created for this pin (or [Admin] moderation takes it down / bans the author) | The schedule ends in the same transaction, admin console shows "Ended"; the App no longer shows it | G1b | |
| ST-ADM-31 | Drawer pin block | [Admin] Official / user / hidden topic drawers | "Topic page pins" block: 1 active + up to 3 scheduled; "+ New pin" inside the drawer switches to G2 (top "← Back to topic details", slot and topic preselected); "View all schedules →" jumps to G1b; disabled topics have no such block | F4 / F5 / G2 | |
| ST-ADM-32 | Restore after disable does not revive pins | Following ST-ADM-16 → restore | Ended schedules remain "Ended" | G1b | |
| ST-ADM-33 | pinned API | [API] `GET /api/v1/content-posts/pinned?topic={name}` | Returns the active schedule if any, otherwise empty | — | |

### 7. Ops notification variables and audience (ST-ON)

> Send to test roles only: in step 2 use "by user ID list", with the list containing only U0 + test roles. For audience estimation cases, only verify the count; do not send to everyone.

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-ON-01 | Variable buttons and tags | [Admin] Configuration & Security → System notification sending → New → 1. Content | Under each of the four input boxes (title / body × EN / ID) there are "+ User nickname" and "+ Pet nickname"; clicking inserts a tinted whole tag at the cursor; the rule description sentence is present | I1 | |
| ST-ON-02 | Whole-tag deletion, no editing text | [Admin] Put the cursor inside a tag, type, press backspace | Cannot edit the text inside the tag; backspace deletes it as a whole | I1 | |
| ST-ON-03 | Same variable multiple times | [Admin] Insert "User nickname" twice | Allowed | I1 | |
| ST-ON-04 | Sample preview | [Admin] Type "Halo {User nickname}, {Pet nickname} …" | Live "Sample preview: Halo hex, tuotuo …" below | I1 | |
| ST-ON-05 | Hand-typed variables blocked | [Admin] Hand-type "{User nickname}" / "{Pet name}" / "{Nama hewan}" / `{{nickname}}`, then click Next | Error slot "Please insert variables using the buttons below", cannot proceed to the next step | I2 | |
| ST-ON-06 | Too long | [Admin] Fill the title with 115 characters, then insert one variable | "Title up to 120 characters, body up to 255 characters (each variable counts as 20)"; the character counter counts variables as 20; cannot submit (validated on both frontend and backend: [API] a direct POST bypassing the frontend is also rejected) | I3 | |
| ST-ON-07 | Step 2 net headcount | [Admin] Pet nickname used, audience "All users" (view the estimate only, do not send); description sentence is the new copy | Estimated reach is the net headcount; matches the same-definition SQL in [DB] (excluding banned / deactivated / virtual accounts, and with a pet) | I4 | |
| ST-ON-08 | Step 4 previews a real user | [Admin] List = U0, U1, U4 → step 4 confirm | Estimated reach 2 (net) + "1 user without a pet excluded"; preview "Preview user: {nickname} #{id} · Pet {name}" is a real user from the list; "Shuffle" switches it | I4 | |
| ST-ON-09 | 0-person block | [Admin] Audience "No pet profile" + pet nickname used → step 4 | Preview "— (no users left to send to after exclusion)"; sticky-bottom error "After excluding users without pets, 0 users remain; cannot send…", cannot submit; [API] direct submit returns 422 | I6 | |
| ST-ON-10 | Sending and substitution | [Admin] List U0 + U1 + U4, variables in both ID / EN, send → [Emulator] U0 notification center and system push; [DB] U1's notifications | U0 receives their own nickname and pet name (matching the App language locale); U1 gets U1's values; U4 receives nothing; push is consistent with the notification center | — | |
| ST-ON-11 | Send records | [Admin] Send records list | Reach cell shows "Skipped 1 (no pet)" (reads `skipped_no_pet_count`); the "Reach" column is widened; content summary shows variables as-is, with no real nicknames; the original `skipped_count` (invalid id) definition is unchanged | I5 | |
| ST-ON-12 | Empty nickname fallback | [DB] Temporarily set U1 `nickname` to empty (record it, restore after testing) → send a notification that only uses the user nickname variable | ID copy "Sobat", EN copy "there" | — | |
| ST-ON-13 | Pet selection rules | [DB] U1 has 3 pets (including read-only, different creation times) → send; then send filtered by species "cat" | With no species filter, take the earliest-created ACTIVE; filtered by cat, take the earliest-created cat (ACTIVE first); if all are read-only, take the earliest read-only | — | |
| ST-ON-14 | Scheduled send resolves at send time | [Admin] Schedule to send in 10 minutes → meanwhile [API] U1 changes nickname | U1 receives the new nickname | — | |
| ST-ON-15 | Nickname variable only | [Admin] Insert only the user nickname, list includes U4 | U4 still receives it, not excluded | — | |
| ST-ON-16 | Regression without variables | [Admin] Send a notification with no variables to U0 | Behavior is the same as 1.4.1 | — | |
| ST-ON-17 | Logs do not record body / recipients | [Server] `docker logs petgo-server-stag` searching for this title fragment and U0's nickname | No body, no substituted text, no recipient id | — | |
| ST-ON-18 | 10k threshold by net headcount | [Admin] All users + pet nickname (do not submit) | The validation value for manually entered headcount is the net headcount | I4 | |

### 8. Analytics spot checks (ST-AN)

> The stag build sends analytics to the production PostHog project as usual (emulator events carry `$is_emulator`); you can also use `adb logcat` to view the Analytics facade logs.

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-AN-01 | Pet follow | [Emulator] Follow / unfollow once each on the visitor page, the follow list, and the Hewan empty-state recommendation card | `pet_follow` / `pet_unfollow`: `source` = pet_visitor / follow_list / pet_recommendation; `pet_id`, `owner_id` (= ownerToken), `is_following_owner` | — | |
| ST-AN-02 | Following feed reason | [Emulator] Following feed impression / click | Carries `reason` | — | |
| ST-AN-03 | Topics | [Emulator] Post with a topic, enter the topic page from the home row / body / Feed card / pin, switch sort | `topic_inserted` (after publish succeeds, one per item from the response, `topic_id` = canonical name, `is_official`, `is_new`, `method`); `topic_page_viewed.source` = home_row / post_body / feed_card / pin; `topic_sort_switched`; `home_topic_row_tapped` (`position`, `is_pinned_by_ops`) | — | |
| ST-AN-04 | Milestone share to community | [Emulator] ST-MC-03 / 07 | `brag_post_tapped` source = milestone_celebration + `code` / `level`; successful post carries source; on failure `milestone_share_failed` (`code`, `stage`) | — | |
| ST-AN-05 | @ | [Emulator] Pick one person each from the quick list / search results | `mention_inserted` carries `relation`, `from_quick_list` | — | |

### 9. Trilingual copy spot checks (ST-L)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| ST-L-01 | App Indonesian | [Emulator] App language = Bahasa Indonesia, go through A5 / A8 / A11 / A13 / B1 / B2 / C3 / C4 / C9 / C10 / D1 / D4 / D5 / E6 | Matches PRD §7 Indonesian verbatim; no leftover English, no raw keys | Multiple screens | |
| ST-L-02 | App English | [Emulator] Switch to English, spot check the same screens | Matches PRD §7 English (e.g. "People / Pets", "No new posts yet", "Share to other apps", "Up to 5 topics per post", "Topic not found") | Multiple screens | |
| ST-L-03 | Notifications with backend-composed copy | [Emulator] View `PET_FOLLOWED` / `PET_DIARY_POSTED` in both languages | ID "… mulai mengikuti …" / "… punya cerita baru"; EN "… started following …" / "… has a new story" | A12 | |
| ST-L-04 | Admin console Chinese | [Admin] Go through F1–F9 / G1–G2 / I1–I6 in Chinese | Matches the Chinese in the PRD §7 admin-console copy table; on F screens "Disable / Restore" reuses existing terms (a known difference from the mockup in the previous round; follow the PRD reuse) | Multiple admin-console screens | |
| ST-L-05 | Admin console EN / ID | [Admin] Switch to EN and ID and scan the above screens, including toasts, native confirms, and error slots | No missing translations, no raw keys; terms Topic / Topik, Resmi, Baris topik Sosial | Multiple admin-console screens | |

### 10. UI fidelity (≥ 95 points)

> Method: score screen by screen against the two final UI mockups (full score 100, standard ≥ 95); App screenshots via `adb exec-out screencap -p`, mockup screens rendered as single-screen PNGs with `ui-mockup-shots` for comparison. Values follow the notes in the mockup, do not measure from screenshots; parts the mockup marks as "existing modules follow production" are not penalized. Scores from the previous local acceptance are for reference only; this round re-scores from stag screenshots.

#### App (43 screens)

| Screen | Title | How to reach on stag / seed data | Score | Notes |
|---|---|---|---|---|
| A1 | Pet visitor page · not followed | ST-F-20 | | |
| A2 | Pet visitor page · followed | ST-F-21 | | |
| A3 | Pet follow failure toast | ST-F-23 airplane mode | | |
| A4 | Owner Diary page · info card | ST-F-26 (U2 follows U0's pet first) | | |
| A5 | Follow list · users | ST-F-48 | | |
| A6 | Follow list · pets | ST-F-49 | | |
| A7 | Follow list · pets · just unfollowed | ST-F-50 | | |
| A8 | Follow list · pets · empty state | ST-F-51 | | |
| A9 | Someone else's follow list · pets | ST-F-53 | | |
| A10 | Following feed · Diary of followed pets | ST-F-56 | | |
| A11 | Following feed · no new updates | ST-F-60 (needs seed data) | | |
| A12 | Notification center · two new notifications | ST-F-32 + ST-F-37 | | |
| A13 | Push settings · new category | ST-F-45 | | |
| A14 | Pet visitor page · expired | ST-F-29 | | |
| A15 | Pet visitor page · load failure | ST-F-31 | | |
| B1 | Celebration · 1 pet | ST-M-11 (needs seed data) | | |
| B2 | Celebration · multiple pets | ST-M-07 / 08 (needs U0 flag reset) | | |
| B3 | Milestone list · two new entries | ST-M-03 | | |
| B4 | Notification center · account-level milestone | ST-M-06 | | |
| C1 | Discover page · topic row | ST-T-35 / 36 (no venue entry row on home) | | |
| C2 | Publish page · # suggestions | ST-T-12 / 13 | | |
| C3 | Publish page · new topic | ST-T-14 | | |
| C4 | Publish page · more than 5 | ST-T-16 / 17 | | |
| C5 | Post detail · topic highlighting | ST-T-21 | | |
| C6 | Topic detail · loading | ST-T-24 weak network (stag cross-border latency is higher than local, easier to capture) | | |
| C7 | Topic detail · official topic | ST-T-22 + ST-ADM-29 pin | | |
| C8 | Topic detail · user topic · popular sort | ST-T-23 | | |
| C9 | Topic detail · empty state | ST-T-25 (needs a 0-post topic seeded) | | |
| C10 | Topic detail · nonexistent | ST-T-28 | | |
| C11 | Topic detail · load failure | ST-T-29 | | |
| D1 | Celebration page · new button group | ST-MC-01 | | |
| D2 | Button loading | ST-MC-02 (screen-record and grab a frame) | | |
| D3 | Card image generation failure | ST-MC-07 (failure must be constructed, may not be triggerable) | | |
| D4 | Publish page · prefill | ST-MC-03 | | |
| D5 | Publish page · account-level milestone prefill | ST-MC-04 | | |
| D6 | Milestone card · single pet | ST-MC-12 take original image | | |
| D7 | Milestone card · multiple pets | ST-MC-12 take original image (1–5 pets) | | |
| E1 | @ quick list | ST-AT-01 | | |
| E2 | @ searching | ST-AT-03 (screen-record and grab a frame) | | |
| E3 | @ search results | ST-AT-03 / 04 | | |
| E4 | @ no results | ST-AT-05 | | |
| E5 | @ query failure | ST-AT-08 | | |
| E6 | @ no one at all | ST-AT-09 (needs seed data) | | |

#### Admin console (19 screens)

| Screen | Title | How to reach on stag / seed data | Score | Notes |
|---|---|---|---|---|
| F1 | Topic list | ST-ADM-02 | | |
| F2 | New official topic · right-side drawer | ST-ADM-04 | | |
| F3 | Name clashes with an existing user topic · upgrade confirmation | ST-ADM-06 | | |
| F4 | Edit drawer · official topic | ST-ADM-09 | | |
| F5 | Edit drawer · user topic (case changes only) | ST-ADM-10 | | |
| F6 | Disable · second confirmation | ST-ADM-16 | | |
| F7 | Restore disabled topic · second confirmation | ST-ADM-18 | | |
| F8 | Add a 6th · exceeds-5 prompt | ST-ADM-12 | | |
| F9 | Filter / search with no results | ST-ADM-03 | | |
| G1 | Pin management · "Home Feed" tab | ST-ADM-22 | | |
| G1a | Pin management · "Topic page" tab | ST-ADM-23 | | |
| G1b | Topic page · pin schedule of a topic | ST-ADM-24 | | |
| G2 | New pin · post picker lists only that topic | ST-ADM-25 | | |
| I1 | New · 1. Content · insert variable + sample preview | ST-ON-01 / 04 | | |
| I2 | Hand-typed variable · "Next" is blocked | ST-ON-05 | | |
| I3 | Too long after inserting a variable | ST-ON-06 | | |
| I4 | New · 4. Confirm · random real-user preview | ST-ON-08 | | |
| I5 | Send records · variables as-is + skipped count | ST-ON-11 | | |
| I6 | 4. Confirm · blocked at 0 users | ST-ON-09 | | |

**Total**: App 43 screens + admin console 19 screens = 62 screens.

### 11. Items requiring seed data / possibly not executable on stag

| Item | Related cases | Notes / workaround |
|---|---|---|
| U0's milestones were already marked complete by the post-launch backfill | ST-M-04 ~ 12, B1 / B2 / B4 | Delete U0's `user_milestone_flags` and the new-code completion records, then re-trigger; this adds one extra `MILESTONE_ACCT` +5 growth points, record it in the seed data |
| Single-pet celebration page B1 | ST-M-11 | Temporarily set U0's other pets to READ_ONLY, restore after testing |
| Actions by other users | Most F / T / AT cases | Sign a JWT on the server (U1–U4); pick virtual / internal accounts to avoid pushing to cloned real users |
| Daily quota of 500 | ST-F-10 | Requires seeding 500 follow / pet-follow rows; large volume, may be marked ⏭ with L1 as the reference |
| Owner account deletion cascade | ST-F-17 | stag cannot register new Google accounts, so create a disposable account in the DB; if it cannot be created, ⏭ |
| Deletion cases | ST-F-14 / 29 / 39, ST-ADM-30 | Only delete pets / posts newly created in this run, do not touch cloned data (OSS same bucket) |
| Next-day cases | ST-F-40, ST-T-44, ST-T-47 | Need a retest across a WIB day, or change DB dates to simulate (note this in the result) |
| Heat score / post count | ST-T-39, ST-ADM-21 | Runs hourly at `:20`, no manual trigger endpoint; wait for the job or write `heat_score` directly as seed data |
| Empty-state cases | ST-F-60 / 61, ST-T-27 / 37, ST-AT-09 | Need to temporarily clear U0's follows / pet follows / interaction candidates or topic heat; must back up first and restore after testing (official-account auto-follow has an optout record) |
| Card image generation failure D3 | ST-MC-07 | Requires constructing an avatar / badge load failure, which may not be triggerable; rely on the L0 widget test |
| Milestone card original image D6 / D7 | ST-MC-12 | The card image is generated in memory; after publishing, fetch the OSS original to verify; bow / `first_friend`, `first_save` badge assets use the fallback style until the artwork arrives |
| Panel docked to soft keyboard | ST-T-19, ST-AT-16 | The emulator usually has a hardware keyboard + floating Gboard; docked position may need a real device |
| Push delivery | ST-F-32 / 37 / 46 / 47, ST-ON-10 | stag sends real pushes; the emulator needs Google Play services and a logged-in IM push, otherwise substitute the notification center + server push logs |
| Manual-review reissue | ST-F-44 | Needs a Diary entry that enters manual review; the stag review switch follows the env at that time |
| Read-only permission account | ST-ADM-01 | Needs another admin console account with only `content.topic_view`; if none, verify the super admin only |
| Companionship reaching 100 / 365 days | ST-MC-10 / 11 | Change the pet's home date to trigger the L level, restore after testing |

### Seed data checklist (fill in during execution)

- Test roles: U0 = user ___; U1 = user ___ (pets: cat ___ / dog ___ / read-only ___); U2 = ___; U3 = ___; U4 = ___
- Newly created topics / posts:
- DB change log (table, row, original value → new value, restored or not):
- Admin console operations (new topic, home topic row, pinned schedule, notification campaign):

---

## B. E-commerce line (shop) test cases (stag · Android emulator)

> Scope: V1.4.2 "E-commerce" line App + backend (shipping prerequisites / one-page checkout / coupons / PawCoin custom amount / banner carousel / Toko home and search / product detail / cart / payment panel and result / order list and detail / cancellation and refund / refund method selection and offline payout / my coupons and redemption / address book / reorder removal / Biteship logistics). The e-commerce **admin console pages** belong to another line; this document only uses the admin console as a means of seeding data and triggering actions, and does not verify the admin console pages themselves.
> Basis: PRD "1-4-2-shop-prd.md", decision log - shop (S-0~S-10, C-1~C-13, **decision log > PRD > architecture > design mockup**), architecture-v1.4.2-shop-delta (D-1~D-24), the 25 stories in sprint-status-v1.4.2-shop.yaml, and the local integration record acceptance-v1.4.2-shop.md (S1~S8).
> Screen numbers: taken from `ui-1-4-2-shop-integrated-v1.html`, which contains **93 screens** in total (A1~A9, B1~B15, C1~C8, D1~D18, E1~E7, F1~F7 / F9~F23 / F25, G1 / G2 / G4~G9, H1~H5; the F8 confirm-receipt dialog was removed per the 11-05 decision, and F24 and G3 do not exist in the mockup).
> Result: ✅ Pass | ❌ Fail (attach issue number) | ⏭ Skipped (state reason) | ⚠️ Pass with notes. The result column is left blank and filled in during execution.

---

### 0. Execution environment and conventions

| Item | Notes |
|---|---|
| Backend | stag: `api-stag.tailtopia.id` (container `petgo-server-stag`, database `petgo_stag`); admin console `ops-stag` (needs a `SUPER_ADMIN` session; if only emergency login is available, an alert is sent to all super admins, so confirm before logging in) |
| App | The stag debug build on Android emulator `emulator-5554` (connected to api-stag), one Google test account (hereafter "the test account"). When a "guest" is needed, log out inside the App |
| Biteship | stag is in **stub mode**: two fixed quotes: **JNE Reguler Rp10.000 (insurance supported)**, **SiCepat REG Rp12.000 (insurance not supported)**; destination postal code `99999` → no courier reachable; `99998` → quote failure (500); no real orders are placed, waybills live in memory, and **stub waybills are lost after the stag container restarts**, so advancing status will fail and the waybill must be created again |
| QRIS | The stag gateway does not issue real codes (P04). Always use the admin console "payment simulation" (`POST /admin/payments/{intentToken}/simulate-paid`, usable only for non-terminal states) or change the DB to credit payment; to see the E1 panel with a code, follow the 1.4.1 method: change the failed intent to `PENDING` + fake gateway order number + fake QR code, and change it back after testing |
| Lark | Shop Lark notifications are not configured: shipping reminders, waybill retry alerts, and consecutive order-query failure alerts can only be seen in backend ERROR logs |
| stag test entry points | `/admin/stag/shop/**` (`@StagOnly` + `SUPER_ADMIN` session, CSRF-exempt, callable with curl carrying the admin console cookie): create / disable coupons, create waybills, advance stub logistics status, cancel by reason (WHOLE / LINE / COMPENSATION), confirm return-to-stock, mark payout done / payout failed, re-order / refund for late payments |
| API calls | Call App APIs as the test account: sign a JWT for the test account on the server using stag's `JWT_SECRET` |
| Execution tags | Each step starts with a tag: [Emulator] [Admin Browser] [API] [DB], which may be combined |
| Amount formula | Payable = item amount − amount-off coupon + shipping − shipping discount (free shipping by threshold or free-shipping coupon, take the larger; if equal, use free shipping by threshold; discount ≤ min(lowest quote of this order, cap, selected shipping)) + insurance fee (ceil(item amount paid × 0.5%)); PawCoin = min(payable, balance, per-order limit), cash = payable − PawCoin. The amount-off threshold is based on the applicable item amount before coupons; the free-shipping threshold / free-shipping coupon threshold is based on the item amount paid (item amount − amount-off) |
| Config changes must be restored | This document temporarily changes stag's free-shipping cap, per-order PawCoin limit, compensation ratio, and review switch; stag is shared, so restore them per the "seed data checklist" after testing |

#### 0.1 Seed data checklist (one-time preparation before execution, with IDs for cases to reference)

| ID | Content | Method |
|---|---|---|
| Z1 | Shipping settings: origin postal code (any 5 digits, e.g. 10110) + contact; free-shipping-by-threshold at **Rp200.000**; free-shipping cap **left blank** (no extra cap); insurance rate 50bp | [Admin Browser] `/admin/shop/shipping`; insurance rate / cap can also be set via [DB] `shipping_settings` |
| Z2 | Zones (`shipping_zones`): Z-OK enabled, `postal_code=12345`; Z-NC enabled, `postal_code=99999`; Z-FAIL enabled, `postal_code=99998`; Z-NP enabled, `postal_code` empty; Z-OFF not enabled (`active=false`) | [Admin Browser] `/admin/shop/shipping/zones` + [DB] fill in `postal_code` |
| Z3 | Products / SKUs (all must be **purchase-stocked in**, otherwise later cancel-refund returns 422):<br>P-A "QA Makanan A" category FOOD: A1 variant Rp100.000 stock 20, A2 variant Rp120.000 stock 0;<br>P-B "QA Mainan B" category TOY: B1 Rp50.000 stock 20;<br>P-C "QA Tanpa Paket" C1 Rp80.000 **package weight / length, width, height left blank**;<br>P-D "QA Stok Satu" D1 Rp30.000 stock 1;<br>P-E "QA Akan Ditarik" E1 Rp40.000 stock 5 (to be taken down later);<br>P-F "QA Tanpa Ulasan" F1 Rp60.000 (no reviews). A1/A2/B1/D1/E1/F1 have all package parameters filled (e.g. 1000g, 20×15×10cm) | [Admin Browser] products, SKUs, `/admin/shop/inventory/purchase` |
| Z4 | Coupons (`/admin/shop/coupons` or `POST /admin/stag/shop/coupons`):<br>**CP-20K** amount-off 20.000, threshold 150.000, ALL, claimable type, valid 7 days after claim, 1 per person;<br>**CP-5K** amount-off 5.000, threshold 50.000, ALL, claimable type, 3 per person;<br>**CP-B10K** amount-off 10.000, threshold 50.000, specific product P-B, claimable type;<br>**FS-10K** free shipping cap 10.000, threshold 100.000, ALL, claimable type, 3 per person;<br>**FS-FOOD** free shipping cap 10.000, threshold 0, specific category FOOD, claimable type;<br>**CP-B50K** amount-off 50.000, threshold 50.000, specific product P-B, redeem-code type (2 single-use codes);<br>**FS-0** free shipping cap 10.000, threshold 0, ALL, redeem-code type (2 single-use codes);<br>**NEWBIE** new-user coupon amount-off 15.000, threshold 100.000, issuance period today~+7 days;<br>**CODE-U** amount-off 8.000 threshold 50.000, universal code `TTQA142`, 1 per person, total quantity 2;<br>**CODE-S** amount-off 6.000, 3 single-use codes;<br>**CODE-FUT** amount-off 7.000, issuance period starts tomorrow, universal code `TTQAFUT`;<br>**LIMIT-1** amount-off 3.000, claimable type, total quantity 1;<br>**EXP-SOON** amount-off 4.000, claimable type, fixed validity end = now + 30 hours | [Admin Browser] / [API] stag coupon creation entry |
| Z5 | Banners (`/admin/shop/banners`): BN1 → product P-A; BN2 → claim coupon CP-20K; BN3 → external https link; BN4 → product P-E; BN5 → claim coupon LIMIT-1; BN6 → category (legacy data, [DB] change to `target_type=CATEGORY`); BN7 → schedule starts tomorrow. 7 active configs in total, to verify the maximum of 6 and the filtering | [Admin Browser] + [DB] |
| Z6 | Test account: has 1 pet profile (dog, adult, **weight filled in**); issue **Rp300.000** coin via the admin console (use the admin console coin issuance, which leaves a ledger entry; do not edit `pawcoin_wallets` directly); note the registration time (used for new-user coupon eligibility) | [Admin Browser] |
| Z7 | Platform-liability compensation: `pawcoin_config.compensation_premium_rate = 10`, `compensation_premium_cap = 20000` (note the original values and restore after testing) | [DB] |
| Z8 | Reviews: create ≥ 4 published reviews for P-A (different nicknames / variants); the review display switch `platform_config` key `shop.review.display-enabled` stays **false** for now | [DB] |
| Z9 | Seed data for payment panel with code: after submitting the order, change that order's failed intent to `PENDING` + fake gateway order number `STAG-QA142-FAKE-*` + fake QR code string | [DB] |
| Z10 | Time compression: change the order's `shop_orders.expires_at` to now()+2 minutes, and that order's intent `payment_intents.provider_expires_at` / `expires_at` to now()+3 minutes (the order-close scan runs every 60 seconds, the order-query job every 2 minutes) | [DB] |

#### 0.2 Amount baselines (stub quotes JNE 10.000 / SiCepat 12.000, threshold 200.000, cap empty, insurance 0.5% rounded up)

| Baseline | Order contents | Calculation | Expected |
|---|---|---|---|
| **P1** | A1×2 (200.000), holding CP-20K, CP-5K, FS-10K, JNE, no insurance | Auto-select CP-20K (largest deduction) → amount paid 180.000 (< 200.000, threshold not met); FS-10K threshold 100.000 is met, discount = min(10.000 selected, 10.000 lowest, 10.000 coupon cap) = 10.000 | Payable **180.000**; saved 30.000 |
| **P1i** | P1 + insurance checked | Insurance = ceil(180.000 × 0.5%) = 900, not affected by the shipping discount | Payable **180.900** |
| **P1s** | P1 switched to SiCepat | Shipping 12.000, discount min(12.000, lowest 10.000, 10.000) = 10.000, self-paid 2.000; SiCepat does not support insurance → insurance item disappears | Payable **182.000** |
| **P2** | A1×2 + B1×1 (250.000), holding CP-20K, CP-B10K, FS-10K | CP-20K (20.000 > 10.000) → amount paid 230.000 ≥ 200.000, free shipping by threshold = min(10.000, no cap) = 10.000; FS-10K is also 10.000 → when equal, use free shipping by threshold, FS-10K is not deducted | Payable **230.000**; the free-shipping row reads "free shipping by threshold"; FS-10K goes into the unusable group with the reason "free shipping by threshold saves more" |
| **P2c** | P2, but Z1 cap changed to **8.000** | Free shipping by threshold = min(10.000, 8.000) = 8.000 < FS-10K 10.000 → use FS-10K; manually switching to "do not use free-shipping coupon" → discount 8.000 | Default payable **230.000**; payable without the free-shipping coupon **232.000** |
| **P3 (S-10)** | A1×2 (200.000), **holding only** CP-5K, no free-shipping coupon | CP-5K selected by default → amount paid 195.000 < 200.000 → no free shipping by threshold → 195.000 + 10.000; manually "do not use coupon" → 200.000 meets the threshold, discount 10.000 | Default payable **205.000** (per PRD, take the largest deduction without checking whether it falls below the threshold); payable without coupon **200.000** |
| **P4** | P1, balance ≥ 180.000, per-order limit default 1.000.000 | PawCoin = min(180.000, balance, 1.000.000) = 180.000 | Pure PawCoin, no code issued, "Awaiting shipment" on submit |
| **P5** | P1, per-order limit temporarily changed to **50.000** (`shop_pawcoin_rules.max_coin_per_order`) | PawCoin 50.000, cash 130.000 | Bottom bar "Sisa bayar lewat QRIS: Rp 130.000"; the panel shows only 130.000 |
| **P6 (C-4)** | B1×1 (50.000), holding CP-B50K + FS-0 | Amount-off 50.000 → amount paid 0; shipping 10.000 − FS-0 10.000 = 0 | Payable **0** → payment succeeds on submit (ZERO_PAY), no code issued |
| **P7 (partial cancellation)** | A1×2 + B1×1, CP-20K, JNE, free shipping by threshold; per-order limit temporarily changed to **77.777** | Payable 230.000 = PawCoin 77.777 + cash 152.223 | For partial cancellation amounts see SH-RF-03~05 |
| **P8** | A1×1 (100.000), **holding only** CP-5K and FS-10K, JNE, insurance checked, PawCoin off | CP-5K → amount paid 95.000; FS-10K threshold 100.000 is not met against amount paid 95.000; shipping 10.000 paid in full; insurance ceil(95.000 × 0.5%) = 475 | Payable **105.475**, all via QRIS |

---

### A. Toko home, search, banner, new-user coupon (SH-HOME)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-HOME-01 | Logged-in home structure | [Emulator] Test account enters Toko | Top banner carousel, search box hint "Cari makanan, obat, camilan…", "Kupon buat kamu" horizontal scroll, "Untuk {pet}" recommendation with three columns up to 6 items, "Semua produk / {n} produk" product feed; **no category chips, no restock card, no go-create-profile guide bar** | A1 | ⚠️ A1 UI fidelity 90: coupon card is a compact card (full amount written out, button below), the mockup has a narrow card (Rp 20rb, button on the right); recommendation row "Untuk Mochi" (mockup has "Pilihan untuk", the case follows the PRD); stag has no active banner |
| SH-HOME-02 | Banner carousel and count cap | [Emulator] With the seven Z5 configs, view the banner, swipe left and right, and stay idle to wait for auto carousel | Auto carousel + manual swipe + page dots at the bottom; **at most 6**; BN7 (schedule not reached) does not appear; sorted by weight | A1 | |
| SH-HOME-03 | Banner to product / external link | [Emulator] Tap BN1, BN3 | BN1 opens P-A detail; BN3 opens the https external link | A1 / B1 | |
| SH-HOME-04 | Banner claim coupon | [Emulator] Tap BN2 (CP-20K); tap again | First time toast "Kupon diklaim. Cek di Kupon Saya", My Coupons gains one CP-20K; second time toast "Kamu sudah klaim kupon ini" (API 409 `already-claimed`) | A5 | |
| SH-HOME-05 | Banner category target lands on home | [Emulator] Tap BN6 (legacy category target) | Stays on / lands on the Toko home, no error | A1 | |
| SH-HOME-06 | Banner with invalid target is hidden automatically | [Admin Browser] Take down P-E; use another account or [DB] to exhaust LIMIT-1 (`issued_count = total_quantity`); [Emulator] pull to refresh Toko | BN4, BN5 are **no longer shown at all** (not turned unclickable), the rest are normal | A1 | |
| SH-HOME-07 | No active banner | [Admin Browser] Disable all banners; [Emulator] refresh | The banner area is not rendered at all, the top bar is still readable | A2 | |
| SH-HOME-08 | "Coupons for you" claim | [Emulator] In "Kupon buat kamu" tap CP-5K "Klaim" | Button becomes "Diklaim"; My Coupons gains one; analytics `toko_coupon_claimed{source: home}` | A1 | |
| SH-HOME-09 | "Coupons for you" empty state | [Admin Browser] Pause all claimable coupons (or use an account that has claimed them all); [Emulator] refresh | The whole block does not appear | A2 | |
| SH-HOME-10 | "Coupons for you" top-right entry | [Emulator] Tap "Kupon Saya" at the top right of "Kupon buat kamu" | Opens the My Coupons page | A1 / G1 | |
| SH-HOME-11 | Product card Lagi promo badge | [Emulator] View the P-A, P-B, P-F product cards | Products covered by a claimable / usable coupon (P-A, P-B) show a red-orange gradient "Lagi promo" + flame at the top left, no amount; P-C, P-F do not show it if no coupon covers them (FS-FOOD covers the FOOD category, judge by actual coverage) | A1 | |
| SH-HOME-12 | New-user coupon arrives (new user) | Precondition: test account registered ≤ 30 days ago with no paid e-commerce order (if not met, [DB] temporarily change `users.created_at` to 10 days ago, restore after testing); [Emulator] enter Toko | No popup; "Kupon buat kamu" shows a pinned purple coupon labeled "KHUSUS PENGGUNA BARU", button slot "Sudah masuk"; [DB] `user_coupons` gains one row with source=NEWBIE; analytics `toko_coupon_claimed{source: newbie}` | A3 | |
| SH-HOME-13 | New-user coupon idempotency | [Emulator] Leave and re-enter Toko several times | Only 1 issued, no repeat issuance | A3 | |
| SH-HOME-14 | Not issued to non-new users | [DB] Change the test account's `created_at` to 31 days ago (or it already has a paid e-commerce order) and delete the NEWBIE user coupon; enter Toko | No new-user coupon issued | A1 | |
| SH-HOME-15 | Unpaid orders do not affect new-user status | Precondition: new user with only one "awaiting payment / timed-out cancelled" e-commerce order; enter Toko | New-user coupon is still issued (only paid orders count) | A3 | |
| SH-HOME-16 | Guest new-user coupon popup | [Emulator] Log out and enter Toko (a NEWBIE campaign exists) | A4 pops up: "Daftar sekarang, dapat kupon Rp 15.000", "Khusus pengguna baru · min. belanja Rp 100.000", "Daftar & klaim"; background has "Masuk" at the top right, no recommendation block, no "Kupon buat kamu" | A4 | |
| SH-HOME-17 | Guest popup shows only once | [Emulator] Close A4, then leave and re-enter Toko, kill the process and re-enter | The same device does not see the same campaign again; analytics `toko_newbie_coupon_popup_tapped{action: close}` | A4 | |
| SH-HOME-18 | Guest popup sign-up and claim | [Emulator] After clearing App data, enter Toko as a guest and tap "Daftar & klaim" | Goes to the login page; after login the first entry into Toko issues the coupon per SH-HOME-12 (if the test account is not a new user, only verify the jump to login) | A4 | |
| SH-HOME-19 | No popup for guests without a new-user campaign | [Admin Browser] Disable NEWBIE; guest enters Toko | No popup (API 204) | A1 | |
| SH-HOME-20 | Recommendation for the pet | [Emulator] View the recommendation row | Row header "Untuk {pet}" + basis "Anjing {body size} · dewasa"; three columns up to 6 items; cards show only image, name, price, and the "+" quick add-to-cart, **no per-item recommendation reason shown** | A1 | |
| SH-HOME-21 | Recommendation, weight not filled | [Emulator] Edit the pet profile to clear the weight, return to Toko | Row header basis is followed by "berat belum diisi" + "Isi berat badan {pet} biar pilihannya lebih pas" | A9 | |
| SH-HOME-22 | Weight hint text | [Emulator] Edit pet profile → weight field | Hint reads "Opsional. Dipakai untuk merekomendasikan produk yang pas." (no longer mentions food amount estimate) | — | |
| SH-HOME-23 | Quick add-to-cart | [Emulator] Tap "+" on a recommendation card | The variant sheet pops up (even for a single SKU, used to choose quantity); after confirming, a toast says added and the badge +1 | B9 / B11 | |
| SH-HOME-24 | Search with results | [Emulator] Magnifier → enter "QA" | Two-column results, cards contain "+" and Lagi promo; footer "Sudah semua untuk saat ini" | A6 | |
| SH-HOME-25 | Search with no results, has profile | [Emulator] Search "zzzz" | No-result message with second sentence "Coba kata lain."; below it the "Pilihan untuk {pet}" recommendation cards (≤ 6) | A7 | |
| SH-HOME-26 | Search with no results, no profile / guest | [Emulator] Guest searches "zzzz" | Below it "Mungkin kamu suka", the top 6 by operator weight | A7 | |
| SH-HOME-27 | Home load failure | [Emulator] After `adb shell svc wifi disable; svc data disable`, cold start and enter Toko; then restore the network and tap retry | Shows the load failure page + retry; retry succeeds after recovery | A8 | |
| SH-HOME-28 | Legacy category route lands on home | [Emulator] Enter from the health record page category jump row / ops push category target (can fire a `/shop?category=FOOD` deep link with `adb shell am start -d`) | Lands on the Toko home, no error, no category filter | A1 | |

### B. Product detail (SH-PD)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-PD-01 | Logged in with default address | Precondition: default address is in Z-OK; [Emulator] open P-A detail | Order: image → title → price → claimable coupons → "Kirim ke {Kecamatan}" → "Ongkir · Mulai Rp 10.000" → free-shipping progress; bottom bar "Add to cart / Buy now" | B1 | |
| SH-PD-02 | Shipping quote by SKU and quantity | [Emulator] Switch to A1 / change quantity on the detail page; [DB] `shipping_quotes` | Shipping still takes the lowest courier (stub is always 10.000); the same SKU and quantity reuse the same quote within 30 minutes (no repeat quote) | B1 | |
| SH-PD-03 | Free-shipping progress | [Emulator] View A1×1 | "Belanja Rp 100.000 lagi untuk potongan ongkir" (200.000 − 100.000) | B1 | |
| SH-PD-04 | Shipping discount already earned | [Emulator] Select quantity 2 (200.000) in the variant sheet and go back to the detail (or pick a SKU priced ≥ 200.000) | Shows "Dapat potongan ongkir" | B4 | |
| SH-PD-05 | Threshold of 0 shows no progress | [Admin Browser] Change the free-shipping threshold to 0; view the detail; change back to 200.000 after testing | The free-shipping progress line does not appear at all (same in cart and checkout) | B1 | |
| SH-PD-06 | Guest selects delivery area | [Emulator] Guest opens P-A | Shows "Pilih wilayah pengiriman / Pilih wilayah untuk lihat ongkir"; tapping opens the three-level province / city / district drawer | B2 / B6 | |
| SH-PD-07 | Guest selects a deliverable area | [Emulator] Pick Z-OK in the drawer | Shows the selected district and "Mulai Rp 10.000"; remembered locally only, no address generated; analytics `toko_delivery_area_selected{serviceable: true}` | B1 / B6 | |
| SH-PD-08 | Area not deliverable (not enabled / no courier / no postal code) | [Emulator] Pick Z-NC (postal code 99999) and Z-NP (no representative postal code) respectively; Z-OFF should not appear in the tree | Shows "Wilayah ini belum bisa dikirim kurir"; analytics serviceable=false | B3 | |
| SH-PD-09 | Detail quote failure | [Emulator] Pick Z-FAIL (99998) | Shipping row "Ongkir belum bisa dimuat · Coba lagi", tapping re-requests the quote | B15 | |
| SH-PD-10 | Temporarily unsellable (no package parameters) | [Emulator] Open P-C | Bottom bar / variant shows "Belum bisa dijual", cannot add to cart or buy now; [API] forcing add-to-cart returns 422 `sku-not-sellable` | B5 | |
| SH-PD-11 | Coupon drawer | [Emulator] Tap the coupon row on the detail page | Drawer "Kupon untuk produk ini" lists coupons covering this product such as CP-20K / CP-5K / FS-10K / FS-FOOD; tapping "Klaim" turns into "Sudah diklaim", success toast | B7 / B8 | |
| SH-PD-12 | Variant sheet · add to cart | [Emulator] Tap add to cart → pick A1, quantity 2 → confirm | The sheet shows image, price, stock, "Dipilih: {variant}", "Varian", "Jumlah"; after confirming a toast "Ditambahkan ke keranjang", **stays on the detail page**, badge updates | B9 / B11 | |
| SH-PD-13 | Quantity cap = stock | [Emulator] In the P-D sheet, raise quantity to 2 | Stepper cap is 1 (stock) | B9 | |
| SH-PD-14 | Sold-out variant | [Emulator] Tap A2 (stock 0) in the sheet | Selectable but cannot confirm purchase / shows sold out (same as production) | B9 | |
| SH-PD-15 | Guest add to cart | [Emulator] Guest taps add to cart | Login prompt; after login returns to the detail and automatically completes this add-to-cart | B9 | |
| SH-PD-16 | Buy now goes straight to checkout | [Emulator] Note the cart contents and selection → detail "Buy now" pick A1×1 → enter checkout → go back | Goes straight to D1; going back returns to the detail; **cart contents and selection are unchanged**; [API] the preview request has source=BUY_NOW | B10 / D1 | |
| SH-PD-17 | Review switch off | Z8 switch false; [Emulator] open P-A | Shows only the average score and count, no review list | B1 | |
| SH-PD-18 | Review list | [DB] Change the switch to true; open P-A | "Ulasan ({n})" + 3 items (nickname, variant) + "Lihat semua" → all-reviews page with pagination | B1 / B12 | |
| SH-PD-19 | No reviews yet | Switch true; open P-F | "Belum ada ulasan" | B14 | |
| SH-PD-20 | Hidden review recalculation | [Admin Browser] Hide one P-A review; refresh the detail | That review is not shown, and the average score and count are recalculated from the remaining published, non-hidden reviews | B1 / B12 | |
| SH-PD-21 | Product taken down | [Admin Browser] Take down P-E; [Emulator] enter P-E from an order product row / favorites / old link | Shows "Produk ini sudah tidak dijual / Lihat produk lain di Toko. / Ke Toko", tapping the button returns to Toko; **not** the generic load failure + retry | B13 | |
| SH-PD-22 | Sold-out "see alternatives" lands on home | [Emulator] On the detail page when all variants are sold out, tap "see alternatives" (if present) | Lands on the Toko home | A1 | |
| SH-PD-23 | Analytics | [DB] / PostHog stag is off, so check the App log | `toko_product_detail_viewed` as usual; coupon claim `toko_coupon_claimed{source: detail}` | — | |

### C. Cart (SH-CART)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-CART-01 | Estimate with default address | Precondition: holding CP-20K, FS-10K, cart has A1×2 all checked; [Emulator] open the cart | Top "Dikirim ke Kec. {x}"; bottom bar "Estimasi total" = 200.000 + 10.000 − 20.000 − 10.000 = **Rp180.000** (same as P1), "Hemat Rp 30.000"; free-shipping progress "Belanja Rp 20.000 lagi…" (computed on amount paid 180.000) | C1 | |
| SH-CART-02 | Free-shipping-by-threshold estimate | Cart A1×2 + B1×1 all checked (same as P2) | Estimate **Rp230.000**, "Dapat potongan ongkir" | C1 | |
| SH-CART-03 | No address / guest without selected area | [Emulator] Delete all addresses (or guest has not selected an area on the detail page) and open the cart | "Pilih alamat untuk hitung ongkir"; bottom bar "Belum termasuk ongkir", the estimate excludes shipping | C2 | |
| SH-CART-04 | Guest uses the area selected on the detail page | Guest selects Z-OK on the detail page then opens the cart | Quote by that area, includes shipping 10.000 | C1 | |
| SH-CART-05 | Cart quote failure | Default address postal code 99998 | Handled the same as C2 (shipping excluded), no error | C2 | |
| SH-CART-06 | Invalid items group | Cart contains P-C (no package parameters), P-E (taken down), A2 (sold out) | The invalid group is listed separately, **not checkable, excluded from the estimate**, with a cleanup entry; [API] `/me/cart` invalid rows have `selected=false` | C3 | |
| SH-CART-07 | Nothing checked | Uncheck everything | Bottom bar "Pilih produk dulu", checkout cannot be tapped | C4 | |
| SH-CART-08 | Change quantity | A1 quantity +1 / −1 | Estimate and free-shipping progress are recalculated accordingly (quote reused or re-requested, no error) | C1 | |
| SH-CART-09 | Delete and undo | Swipe left / delete B1 → tap undo | An undo bar appears; after undo B1 comes back and its checked state is restored | C5 | |
| SH-CART-10 | Empty cart recommendations | Empty the cart | Empty state + "Mulai belanja" + recommendation cards below (with profile: "Pilihan untuk {pet}") that can be added directly | C6 | |
| SH-CART-11 | Empty cart, no profile | Use a state without a pet profile (guest or temporarily delete the profile) | Recommendation title "Mungkin kamu suka", 6 items by weight | C6 | |
| SH-CART-12 | Load failure | Open the cart offline, retry after recovery | Load failure + retry | C7 | |
| SH-CART-13 | Not logged in | Guest taps the cart icon | Logged-out state (guides to login) | C8 | |
| SH-CART-14 | Checkout carries only checked rows | Check A1, leave B1 unchecked → checkout → submit successfully | Checkout contains only A1; after ordering A1 is removed from the cart and B1 remains | D1 | |

### D. Checkout and Pricing (SH-CO)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-CO-01 | Default values | [Emulator] Cart A1×2, go to checkout (holding CP-20K, CP-5K, FS-10K, balance ≥ 180.000) | "Delivery" card: default address + cheapest courier JNE "Estimasi 2 - 3 hari" Rp10.000 + insurance unchecked; items collapsed into one row (thumbnail + quantity + amount); coupons: spend-threshold CP-20K, free-shipping FS-10K; the amount breakdown ends at payable **180.000** (P1), and the breakdown **does not show PawCoin**; in the payment method area the PawCoin switch is on by default; the "opened items non-returnable" checkbox is checked by default | D1 | |
| SH-CO-02 | Expand items | Tap the collapsed items row | Expands in place into a line-by-line breakdown; top of the discount area "Kamu hemat Rp 30.000 di pesanan ini"; bottom bar "saved" | D18 | |
| SH-CO-03 | Tracking entry | Enter once via the cart path and once via the buy-now path | `toko_checkout_page_viewed{entry: cart / buy_now}` | — | |
| SH-CO-04 | No address | Delete all addresses, then enter checkout | Address area "Tambah alamat baru"; courier area "Isi alamat dulu untuk melihat pilihan kurir", no rate quote; submit not tappable | D2 | |
| SH-CO-05 | Add address in-page | On D2 tap add → fill in a Z-OK address → "Simpan & pakai" | Does not leave the checkout page; address is saved to the address book; rate quote runs automatically and JNE is selected | D4 / D5 | |
| SH-CO-06 | Address not deliverable | In the address selection drawer pick a Z-NC (99999) address | "Tidak ada kurir untuk alamat ini" / "Wilayah ini belum bisa dikirim kurir" + "Wilayah yang sudah kami layani"; submit not tappable | D3 | |
| SH-CO-07 | Address in an unopened region | Pick a Z-OFF address ([DB] seed an address in that region) | Same as D3, cannot submit (no rate quote) | D3 | |
| SH-CO-08 | Quote in progress | Observe the moment the address is switched | Courier area "Menghitung ongkir…" | D6 | |
| SH-CO-09 | Quote failed | Pick an address with postal code 99998 | "Ongkir belum bisa dimuat." + retry; cannot submit; **does not fall back to the old fixed shipping fee** | D8 | |
| SH-CO-10 | Address change applies to this order only | Pick another deliverable address and submit or go back, then enter checkout again | Next entry still defaults to the address book default address | D1 / D4 | |
| SH-CO-11 | Courier drawer | Tap the courier row | Drawer "Pilih kurir" sorted by shipping fee ascending: JNE 10.000 "Termurah", SiCepat 12.000 "Tanpa asuransi"; each item shows name / service / ETA / fee; bottom note "Potongan ongkir maksimal sebesar ongkir termurah…" | D7 | |
| SH-CO-12 | Pick the pricier courier, pay the difference | Pick SiCepat | Shipping fee 12.000, free-shipping coupon −10.000, payable **182.000** (P1s); the insurance option disappears | D9 | |
| SH-CO-13 | Insurance | Go back to JNE, check "Asuransi pengiriman · 0,5% dari harga produk" | Breakdown adds "insurance fee 900", payable **180.900** (P1i); free-shipping reduction unchanged | D1 | |
| SH-CO-14 | Courier kept on address change | After selecting SiCepat, switch to another deliverable address | If SiCepat is still available it is kept; amounts are recalculated | D1 | |
| SH-CO-15 | Spend-threshold coupon drawer | Tap the spend-threshold coupon row | "Kupon diskon belanja": "Bisa dipakai (n)" CP-20K / CP-5K; "Belum bisa dipakai (n)" with reasons; "Tidak pakai kupon"; pick CP-5K → payable 200.000 − 5.000 = 195.000 below the threshold + 10.000 − 10.000 (the FS-10K threshold is judged on the actual 195.000 and is still met) = **195.000** | D10 | |
| SH-CO-16 | Unavailable reasons | Cart has only B1×1 (50.000), go to checkout | CP-20K "Belum capai minimum belanja"; if holding FS-FOOD: "Ada produk di luar cakupan kupon"; CP-B10K is usable; scoped spend-threshold coupons show "Produk tidak termasuk" for out-of-scope products | D10 / D11 | |
| SH-CO-17 | Scoped threshold counts in-scope items only | A1×1 + B1×1 (150.000), manually pick CP-B10K | CP-B10K threshold is judged on the B1 amount 50.000 → usable; deduction 10.000 ≤ B1 amount | D10 | |
| SH-CO-18 | Free-shipping coupon scope must cover all items | A1×2 + B1×1, holding FS-FOOD | FS-FOOD unavailable (B1 is not in FOOD) | D11 | |
| SH-CO-19 | Spend-threshold free shipping = use the threshold when a free-shipping coupon applies | Enter checkout with the P2 order contents | Free-shipping row "Potongan otomatis · Belanja ≥ Rp 200.000, lebih hemat dari kupon" reduces 10.000; payable **230.000**; FS-10K not deducted (after submit, [DB] still AVAILABLE) | D12 | |
| SH-CO-20 | Free-shipping coupon better after cap | Change the Z1 cap to 8.000, enter checkout with P2 contents; then manually choose not to use the free-shipping coupon | Defaults to FS-10K, payable 230.000; without the free-shipping coupon → threshold free shipping reduces 8.000, payable **232.000**; after testing, set the cap back to empty | D11 / D12 | |
| SH-CO-21 | S-10 below-threshold sample | P3 precondition (holding only CP-5K) | Defaults to CP-5K, payable **205.000**; manually "Tidak pakai kupon" → threshold free shipping, payable **200.000** | D10 / D12 | |
| SH-CO-22 | Coupon validity is judged at the moment of ordering | Place an order holding EXP-SOON (pending payment), [DB] change its `valid_until` to the past → pay (payment simulation) | Order can still be paid as normal, coupon becomes USED | — | |
| SH-CO-23 | PawCoin on by default | Enter checkout with balance 300.000 (P1) | Switch on by default, "Seluruh pesanan dibayar dengan PawCoin, tanpa QRIS."; bottom bar "Dibayar penuh dengan PawCoin" | D14 | |
| SH-CO-24 | Turn off PawCoin | Turn the switch off | "Bayar penuh lewat QRIS", bottom bar payable 180.000 all via QRIS; stays off after changing coupons / courier within this checkout; leaving and re-entering restores the default on | D15 | |
| SH-CO-25 | Mixed payment | Change the single-payment cap to 50.000 (P5) | Payment method area PawCoin 50.000, remaining QRIS 130.000; bottom bar "Sisa bayar lewat QRIS: Rp 130.000"; the D13 cap notice appears | D1 / D13 | |
| SH-CO-26 | Cap notice reads config | Same as above | The number in the copy is **50.000** (read from config), not hard-coded 1.000.000; after testing, set the cap back to 1.000.000 | D13 | |
| SH-CO-27 | Opened-items non-returnable checkbox | Uncheck the opened-items non-returnable box | Submit not tappable; copy follows the strictest return policy among the items in this order | D1 | |
| SH-CO-28 | Submit - some items cannot be bought | While staying on checkout, [Admin browser] set D1 stock to 0 / take P-E off shelf (cart contains D1, E1) → submit | Centered dialog "Sebagian produk tidak bisa dibeli" with per-row "Sisa 0, diminta 1" / "Sudah ditarik"; after "Hapus & lanjut" these rows are removed, stays on the checkout page and recalculates, address and other selections are kept | D16 | |
| SH-CO-29 | Submit - coupon no longer valid | While staying on checkout, invalidate the selected CP-20K ([Admin browser] disable and void it, or occupy it with another order) → submit | Toast "Kupon sudah tidak berlaku. Kami pilihkan kupon lain.", the page recalculates and reselects the best option, stays on the checkout page without auto-submitting; API 409 `coupon-invalid` | D17 | |
| SH-CO-30 | Submit - quote expired / mismatched | [API] submit with address A's quoteToken together with address B; or [DB] change the quote `expires_at` to the past and then submit in the App | 409 `quote-expired`, the App re-quotes and can then submit; does not place an order on a stale quote | D1 | |
| SH-CO-31 | Submit - order cannot be placed | [API] POST submit directly without an address / with a non-deliverable address | 422 `not-placeable`; on this the App toasts "Pesanan tidak bisa dibuat sekarang. Muat ulang halaman." | D1 | |
| SH-CO-32 | Submit - other failures | Lose network at the moment of submit | Toast "Gagal membuat pesanan. Coba lagi."; retrying after recovery does not create duplicate orders (Idempotency-Key) | D1 | |
| SH-CO-33 | Server recalculates, does not trust the client | [API] tamper with the amount field in the request on submit (e.g. add `payable=1`) | The server creates the order using its own pricing, amount = P1 | — | |
| SH-CO-34 | Submit locks the courier fee | After submit, [DB] `shop_orders` | `courier_code/service`, `shipping_fee=10000`, `shipping_discount` stored as a **negative −10000**, `discount_coupon_amount=20000`, `insurance_fee`, `checkout_source`, `coin_amount / cash_amount` match expectations; PawCoin is **already deducted at submit** (ledger `biz_ref` = the order) | — | |
| SH-CO-35 | Coupon lock | After submit (pending payment), [DB] `user_coupons` | The coupons used are `LOCKED`, `locked_order_id` = this order | — | |
| SH-CO-36 | Tracking order_submitted | Submit | `toko_order_submitted` carries `coupon_ids` (template token), `coin_used`, `courier=jne_reg`, `shipping_fee`, `insurance` | — | |
| SH-CO-37 | Buy now does not touch the cart | BUY_NOW submit succeeds | Cart rows and checked state unchanged; order `checkout_source=BUY_NOW` | D1 | |
| SH-CO-38 | Same-coupon concurrency | [API] submit two orders at the same time with the same CP-5K | Only one order succeeds, the other gets 409 `coupon-invalid` | — | |
| SH-CO-39 | Payable is 0 | P6 (after redeeming CP-B50K, FS-0, checkout B1×1) | Payable Rp0; submit counts as paid successfully, no QR code, order awaiting shipment; coupons USED | D1 / E6 | |

### E. Payment Panel and Payment Result (SH-PAY)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-PAY-01 | Panel (with code) | P5 submit; after seeding a code per Z9, open the panel (order detail "Pay now") | Title "Pembayaran", "Bayar via QRIS" **Rp130.000** (shows only the QRIS portion) + "+ PawCoin Rp 50.000 sudah ditahan", 60-minute countdown, QR code, "Simpan Kode QR", "Saya sudah bayar"; **no red "Cancel payment"**; "Cara bayar & aplikasi yang didukung" collapsible; height adapts to content | E1 | |
| SH-PAY-02 | QR code generation failed | Submit to the stag gateway as-is (no code seeded) | Shows "Kode QR gagal dibuat" + retry | E7 | |
| SH-PAY-03 | No new code after 10 minutes | [DB] change the order `created_at` to 11 minutes ago, with no usable code → tap pay now / retry to get a code | No new code is issued, prompts that the order can no longer be paid | E7 | |
| SH-PAY-04 | Pay now reuses the same code | With a usable code, close the panel → order detail "Pay now" | Opens the same code (same intent), countdown is continuous | E1 | |
| SH-PAY-05 | Save QR code | Tap "Simpan Kode QR" | Toast "Tersimpan ke galeri. Buka e-wallet atau m-banking, lalu pindai dari galeri."; the image is in the gallery; tracking `toko_payment_qr_saved` | E2 | |
| SH-PAY-06 | I have paid - not yet credited | Tap "Saya sudah bayar" | First "Mengecek pembayaran…", then "Pembayaran belum masuk. Kalau sudah bayar, tunggu sebentar lalu cek lagi." | E3 / E4 | |
| SH-PAY-07 | I-have-paid rate limiting | Tap 3 times within 10 seconds; [API] check the gateway query count in logs | For the same order only 1 real query within 10 seconds | E3 | |
| SH-PAY-08 | Payment credited (payment simulation) | [Admin browser] simulate-paid on that intent | Panel closes automatically → payment success page "Pesanan kamu sedang disiapkan untuk dikirim." + "Lihat pesanan / Lanjut belanja"; View order goes to detail, status "Dikemas" | E6 / F6 | |
| SH-PAY-09 | Coupons and growth points after payment | After payment, [DB] | Coupon LOCKED→USED (`used_order_id`); growth points credited; server-side `shop_payment_paid`; tracking `toko_order_payment_succeeded` | — | |
| SH-PAY-10 | Pure PawCoin success | P4 submit | No panel, goes straight to E6; order awaiting shipment; server additionally emits `shop_payment_paid` | E6 | |
| SH-PAY-11 | Close panel without paying | Close the panel while a code is present | Order goes to "Pending payment", countdown continues; list / detail show "Pay now" | F1 / F4 | |
| SH-PAY-12 | Panel changes do not affect gifting / slot monthly rental | [Emulator] go through gift payment and pet slot monthly rental payment up to the panel (no need to pay) | Still as before (original labels, no "I have paid", no e-commerce-specific copy) | — | |
| SH-PAY-13 | Payment timeout - panel | With the panel open, compress time per Z10 and wait for the countdown to hit zero | Shows a "Payment timed out · order cancelled" style notice (E5), can no longer pay | E5 | |

### F. Timeout Cancellation, Grace Period, Active Order Polling (SH-TO)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-TO-01 | Grace period shows cancelled | Compress the P5 order per Z10; after `expires_at` passes and before `provider_expires_at`, look at list and detail | List files it under the "Cancelled" group, detail shows cancelled, **cannot pay**; [DB] the order is still `PENDING_PAYMENT` (read-only lazy judgment, the order is not closed in place) | F12 / F1 | |
| SH-TO-02 | Payment credited within the grace period | In the SH-TO-01 state, simulate-paid | Order moves to "awaiting shipment", the user sees "Dikemas" after refresh; no late-payment record is issued | F6 | |
| SH-TO-03 | Close order after the code expires | Compress time on another pending-payment order, wait past `provider_expires_at` + one scan round | One last query before closing; if clearly unpaid → order CANCELLED (`cancel_reason=PAYMENT_TIMEOUT`); **PawCoin 50.000 returned, coupons returned to AVAILABLE, stock restored**; detail "Waktu pembayaran habis, pesanan dibatalkan otomatis." "PawCoin Rp 50.000 dan kupon sudah dikembalikan ke akunmu." | F12 | |
| SH-TO-04 | Query failure does not close the order | If the stag gateway order query returns an error / empty (fake-code order) | Order is **not closed**, queried again next round; after 10 consecutive failures the backend logs ERROR (Lark not configured, only check logs) | — | |
| SH-TO-05 | Coupon expired on timeout cancel is not returned as usable | Place an order with EXP-SOON and don't pay, [DB] change the coupon `valid_until` to the past, then let the order time out and close | The coupon no longer appears under "Usable" (expiry computed live) | G1 | |
| SH-TO-06 | Active scheduled order polling | A pending-payment order with a code; [DB] mark the intent as paid at the gateway (or simulate a lost callback: when stopping the callback before simulate is not feasible, only verify the job log) | `ShopPaymentReconcileJob` scans every 2 minutes for intents whose code was issued ≥ 3 minutes ago; if found paid, it is credited as normal | — | |
| SH-TO-07 | User cancels a pending-payment order | Pending-payment order detail "Cancel order" | Confirmation dialog contains "PawCoin dan kupon akan dikembalikan."; after confirming it is cancelled, coins and coupons are returned, stock restored, **no refund order** | F5 / F12 | |
| SH-TO-08 | Cannot self-cancel after awaiting shipment | Paid order detail | No "Cancel order" button | F6 | |

### G. Late Payments (SH-LATE)

> Seed data: stag payment simulation does not accept terminal-state intents, so a late payment is seeded as follows - after the order is closed via SH-TO-03, [DB] change that order's intent from `EXPIRED` back to `PENDING`, then [Admin browser] simulate-paid; the handler sees the order is already CANCELLED → writes `late_paid_amount / late_paid_at`, and the order status does not change. This method does not go through the post-close query path, so `paid_after_close` may be false; go by `late_paid_amount`.

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-LATE-01 | Late payment is recorded | Seed a late payment using the method above | [DB] the original order is still CANCELLED, `late_paid_amount` = gateway actual receipt (= the original order's cash portion 130.000); stock / coupons / coins untouched; admin console order detail shows "Paid after cancellation" | — | |
| SH-LATE-02 | Reorder | [API] `POST /admin/stag/shop/orders/{token}/reorder` (or admin console "Late payment → Reorder") | New order goes straight to "awaiting shipment", reusing the original order's lines / address / courier / shipping fee / insurance (no re-quote); the original order's coupons go LOCKED→USED again on the new order; coins are deducted again per the original order's portion; the new order follows the unified paid path (`paid_at`, growth points credited, `shop_payment_paid`); original order `late_payment_resolution=REORDERED` | F20 / F21 | |
| SH-LATE-03 | Original / new order display | [Emulator] open the original order and the new order | Original order "Dibatalkan · sudah dipesan ulang" + "Pembayaranmu masuk setelah waktu habis. Kami sudah membuat pesanan baru untukmu." + "Lihat pesanan baru" is tappable and navigates; new order "Pesanan ini dibuat ulang dari pesanan {no}." | F20 / F21 | |
| SH-LATE-04 | Reorder notification | After reordering, check the notification center / push | Notification "Pesananmu sudah dibuat ulang / Pembayaranmu sudah kami terima. Pesanan {no} sedang disiapkan.", tapping it goes straight to the new order | F22 | |
| SH-LATE-05 | Original coupon occupied - rejected | After seeding a late payment, first place another pending-payment order with the original order's coupon → then reorder | Rejected (409), prompts that the coupon is already occupied | — | |
| SH-LATE-06 | Original coupon expired still usable | [DB] set the original order's coupon to expired → reorder | Succeeds, the new order can use that coupon | — | |
| SH-LATE-07 | Insufficient coins to cover the difference - rejected | The original order was a mixed payment; before reordering, spend the test account's balance below the original coin portion | Reorder rejected (refund only) | — | |
| SH-LATE-08 | Can only be handled once | After reordering, call reorder / late-refund again | Rejected (409) | — | |
| SH-LATE-09 | Late payment refund | Seed another late payment → `POST /admin/stag/shop/orders/{token}/late-refund` | Generates a refund order: cash = the late payment, coins 0, **no compensation**; the App original order detail shows "Select refund method"; `late_payment_resolution=REFUNDED` | F13 / F23 | |
| SH-LATE-10 | Late payment refund has no compensation page | Enter select refund method | The PawCoin option does not show a bonus | F23 | |
| SH-LATE-11 | Late payment refund does not deduct growth points | After the refund, [DB] growth points ledger | No clawback (the original order never credited growth points) | — | |
| SH-LATE-12 | Overpay / underpay settlement (cannot be seeded) | — | The stag gateway's actual receipt always equals the cash portion, so overpay / underpay cannot be created; covered by `LatePaymentIntegrationTest`, record as ⏭ | — | |

### H. Order List and Detail (SH-ORD)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-ORD-01 | Secondary filter | [Emulator] My orders → choose "Shopping" | Shows "All / Belum bayar / Dikemas / Dikirim / Completed / Cancelled"; pending payment and awaiting receipt carry counts (= `/me/shop-orders/counts`); the "Kupon Saya · {n} bisa dipakai" entry appears only under Shopping | F1 | |
| SH-ORD-02 | Pending payment list | Have 1 pending-payment order | Card shows the "Bayar dalam {mm:ss}" countdown + "Pay now" | F1 | |
| SH-ORD-03 | Awaiting receipt list | Have one SHIPPED and one DELIVERED order | Both are in the "Dikirim" group; cards with a delivery exception show "Pengiriman bermasalah" | F2 | |
| SH-ORD-04 | Empty list | Choose a group with no orders | Empty state | F3 | |
| SH-ORD-05 | List load failed | Refresh while offline | Load failure + retry | F18 | |
| SH-ORD-06 | Cancelled group includes refunds | Orders that are refunding / refunded | Both are filed under the "Cancelled" group | F1 | |
| SH-ORD-07 | General order list consistent | [API] `GET /api/v1/orders?includeEcommerce=true`, look at a grace-period order | Likewise shown as cancelled by displayStatus (C-8) | — | |
| SH-ORD-08 | Detail - pending payment | Open a pending-payment order | Countdown, line-by-line amount breakdown (item amount / spend-threshold discount / shipping / shipping discount / insurance / payable) + payment method area (PawCoin / QRIS listed separately) + "Pay now" "Cancel order" | F4 | |
| SH-ORD-09 | Detail amount check | Open the paid P2 order | Items 250.000 / threshold discount −20.000 / shipping 10.000 / threshold free shipping −10.000 / payable 230.000; the free-shipping reduction is stored as a negative, **the App displays the reduction row normally, and the breakdown adds up to the payable** (integration defect 1 regression) | F6 | |
| SH-ORD-10 | Detail - awaiting shipment | Paid but not shipped | "Pesanan sedang disiapkan. Kami kabari saat sudah dikirim.", no action buttons | F6 | |
| SH-ORD-11 | Detail - delivered + confirm receipt | Tap "Confirm receipt" on a DELIVERED order | "Paket sudah diterima / … Otomatis selesai dalam 7 hari."; tapping **does not show a second confirmation**, toast "Terima kasih, pesanan selesai" → Completed | F9 / F11 | |
| SH-ORD-12 | Can also confirm receipt while awaiting receipt | Tap confirm receipt on SHIPPED (not delivered) | Completes directly | F7 / F11 | |
| SH-ORD-13 | Detail - completed | — | "Terima kasih sudah belanja di TailTopia." | F11 | |
| SH-ORD-14 | Item row in detail navigates to detail | Tap an item row | Goes to product detail; a product taken off shelf goes to B13 | B1 / B13 | |
| SH-ORD-15 | Legacy order degradation | Open an old e-commerce order from the 1.4.1 period | When new columns are empty it is shown the legacy way, no crash, no garbled 0 rows | F6 / F11 | |
| SH-ORD-16 | My coupons entry | On the Shopping group page tap "Kupon Saya" | Goes to G1 | G1 | |

### I. Logistics Biteship (SH-LOG)

> On stag, create waybills with `POST /admin/stag/shop/orders/{token}/waybill` or the admin console "Create waybill"; advance status with `POST /admin/stag/shop/orders/{token}/waybill/stub-status {"status": "<biteship status>"}` (only available in stub mode).

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-LOG-01 | Still awaiting shipment after creating waybill | Create a waybill for a paid order | Returns a waybill number (starts with `STUBWB`, 10 characters); order is still "awaiting shipment"; no re-quote, uses the courier and shipping fee locked at order time; with insurance checked it carries `insurance_amount` = actual amount paid for items | F6 | |
| SH-LOG-02 | Duplicate waybill creation | Create another for the same order | Rejected (409) | — | |
| SH-LOG-03 | Awaiting pickup | [Admin browser] mark as shipped (manual) → App detail | "Awaiting receipt", logistics block shows courier name · service, waybill number (Salin is copyable), "Lacak di situs kurir", the five-stage status stops at "Menunggu dijemput" | F7 | |
| SH-LOG-04 | picked auto-ships | On another order, create a waybill, don't ship manually, advance to `picked` | Order auto SHIPPED (source BITESHIP_PICKED, visible only in the admin console); user sees "Sudah dijemput" | F7 | |
| SH-LOG-05 | Advance stage by stage | Advance `in_transit` → `dropping_off` in order | "Dalam perjalanan" → "Sedang diantar" | F7 | |
| SH-LOG-06 | delivered auto-delivers | Advance to `delivered` | Order DELIVERED, "Diterima", F9 copy | F9 | |
| SH-LOG-07 | Manual waybill shows no status | [Admin browser] ship another order by manually entering a waybill | Shows only the courier, waybill number and "Check on the carrier's website", no five-stage status | F7 | |
| SH-LOG-08 | Pickup failure unnoticed by user | Advance `courier_not_found` | Order flagged `PICKUP_FAILED` ([DB]); the user side keeps showing the original status (awaiting shipment / awaiting receipt · awaiting pickup), no exception shown | F6 / F7 | |
| SH-LOG-09 | Pickup failure handling: recreate waybill / manual / cancel | [Admin browser] perform recreate waybill on the SH-LOG-08 order | Cancels the old waybill first, then creates a new one; detail shows the latest waybill number | F7 | |
| SH-LOG-10 | Returned - not shown if unconfirmed | Advance `returned` | Flagged RETURNED; until operations confirms, the user side does not show a delivery exception | F7 | |
| SH-LOG-11 | Delivery exception (after confirmation) | [Admin browser] confirm as exception (or [DB] `logistics_exception_confirmed=true`) | Detail "Pengiriman bermasalah" + "Silakan hubungi layanan pelanggan kami…" + "Hubungi CS" side by side with "Confirm receipt"; tracking `toko_delivery_exception_shown{exception_type: returned}`, tapping contact support `toko_delivery_exception_cs_tapped` | F10 | |
| SH-LOG-12 | Lost / damaged exception | [Admin browser] mark one order lost and one damaged | Same as F10; tracking exception_type = lost / damaged | F10 | |
| SH-LOG-13 | Confirm receipt under exception | Tap confirm receipt on F10 | Order completes, exception flag cleared, operations reminder written | F11 | |
| SH-LOG-14 | Resend for courier-caused reason | [Admin browser] on a returned order choose resend due to courier reason | A new waybill is created on the original order, the old waybill is kept; the user sees the latest waybill number and status; leaves "pending claim" | F7 | |
| SH-LOG-15 | Stall flag | [DB] change the waybill `provider_status_at` to 4 days ago, wait for LogisticsStallJob (hourly) | Flagged STALLED, no notice on the user side; advancing any new status afterwards → cleared automatically | F7 | |
| SH-LOG-16 | Exception orders are not auto-delivered / completed | [DB] seed a SHIPPED order with an exception, set shipped time to 15 days ago | Not auto-delivered by the 14-day rule; after removing the exception the timer restarts from the clearing moment | — | |
| SH-LOG-17 | Auto-delivery fallback at 14 days | [DB] set shipped time of a SHIPPED order without exception to 15 days ago, wait for the fulfillment scan (one round every 10 minutes) | Auto DELIVERED; one set to 8 days ago is untouched | — | |
| SH-LOG-18 | Webhook wrong secret | [API] `POST /api/v1/webhooks/biteship/wrong-secret` | 404, the log contains no plaintext secret | — | |
| SH-LOG-19 | Webhook forged payload | [API] send a payload with the correct secret that writes an order as delivered (the stub is actually in_transit) | Returns 200 first and persists to `biteship_webhook_events`; on processing it re-queries the stub's real status, and the order does **not** become delivered | — | |
| SH-LOG-20 | Webhook unknown order | Correct secret + a non-existent order_id | Returns 200, stays in the inbox table, alert log after the retry limit | — | |
| SH-LOG-21 | Request log redaction | Check the webhook path in the API log | The secret segment is redacted | — | |
| SH-LOG-22 | Waybill failure (cannot be seeded on stag) | — | The stub's "fail next order" hook has no stag entry point; the funds path for waybill failure → platform-caused cancellation is covered by SH-RF-07 (reason=WAYBILL_FAILED); the alert is covered by integration tests, record as ⏭ | — | |

### J. Cancellation and Refund, Select Refund Method, Offline Payout (SH-RF)

> Cancellation entry: `POST /admin/stag/shop/orders/{token}/cancel {"scope": "WHOLE|LINE|COMPENSATION", "reason": "...", "lineId", "qty", "negotiatedAmount", "note"}`, or the admin console "Exception orders" page. Payout: `/admin/stag/shop/refunds/{token}/paid-out`, `/payout-failed`. The compensation rate follows Z7 (10%, capped at 20.000).

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-RF-01 | Out-of-stock whole-order cancel | P5 paid order (coins 50.000 / cash 130.000) → WHOLE + OUT_OF_STOCK | Coins 50.000 returned immediately; cash 130.000 refund order `AWAITING_DESTINATION`; coupons CP-20K, FS-10K returned to AVAILABLE; stock restored; order REFUNDING; detail "Dibatalkan · stok habis" + "…PawCoin Rp 50.000 sudah dikembalikan. Pilih cara pengembalian untuk bagian QRIS Rp 130.000." + "Pilih cara pengembalian dana" | F13 | |
| SH-RF-02 | Choose refund as PawCoin (compensation) | F13 → select refund method → PawCoin → Kirim | Page "Bagian PawCoin · Rp 50.000 · sudah kembali", QRIS portion 130.000, "+ bonus Rp 13.000" (130.000 × 10%); after submit balance +143.000; refund order COMPLETED; order REFUNDED, detail "Refunded" | F14 / F19 | |
| SH-RF-03 | Partial cancel B1 | P7 paid order → LINE (B1 line, qty 1, OUT_OF_STOCK) | Refund 46.000 (line 50.000 − allocated threshold discount floor(20.000×50.000/250.000)=4.000); coin portion floor(46.000×77.777/230.000) = **15.555** returned immediately, cash **30.445** awaiting selection; shipping not refunded; **coupons not returned**; order status unchanged; detail marks that line cancelled, "1 produk dibatalkan karena stok habis. Produk lain tetap dikirim." "Dikembalikan (1 produk dibatalkan)" "PawCoin Rp 15.555 + QRIS Rp 30.445" | F17 | |
| SH-RF-04 | Partial cancel of some units | Same order → LINE (A1 line, qty 1) | Refund 92.000 (100.000 − 8.000); coins floor(92.000×77.777/230.000) = **31.110**, cash **60.890**; `refunded_qty` A1 = 1 | F17 | |
| SH-RF-05 | Last unit becomes whole-order | Same order → LINE (A1 remaining 1 unit) | Handled as a whole order: refund the remaining payable 230.000 − 46.000 − 92.000 = **92.000**, coins 77.777 − 15.555 − 31.110 = **31.112**, cash **60.888** (including shipping and insurance portions); order REFUNDING; check whether coupons are returned per the "whole order platform cause" rule (expected returned; if not, record ⚠️ and escalate for a decision) | F13 / F17 | |
| SH-RF-06 | Partial cancel cash total check | [DB] the three refund orders | Cash total 30.445 + 60.890 + 60.888 = 152.223 = original cash portion; coin total 77.777 | — | |
| SH-RF-07 | Waybill failure / pickup failure cancel | Paid order → WHOLE + WAYBILL_FAILED (another order PICKUP_FAILED) | Same as platform-caused: full refund, coupons returned, compensation available; if there is a cancellable stub waybill, cancel it first | F13 | |
| SH-RF-08 | Offline payout BCA | On the refund order choose offline payout → BCA → fill in account number, account name → Kirim | Refund order `PAYOUT_PENDING`, received amount = cash portion (BCA has no fee); detail "Pengembalian dana diproses / Estimasi masuk dalam 3 hari kerja ke BCA •••• {last 4 digits}." | F15 / F16 | |
| SH-RF-09 | Offline payout OVO / GoPay fee | On another refund order choose OVO | Fee 2.500, deducted from the refund amount (130.000 → 127.500); no bonus | F15 | |
| SH-RF-10 | Mark as paid out | `paid-out` | Refund order COMPLETED; detail "Rp {x} sudah dikirim ke {bank} •••• {last4} pada {date}."; growth points clawback is triggered at this point | F19 | |
| SH-RF-11 | Payout failed, re-enter | Another PAYOUT_PENDING → `payout-failed` | Detail "Transfer pengembalian dana gagal / Silakan isi ulang rekening penerima, atau ubah jadi PawCoin." + "Isi ulang rekening"; no push sent; can re-enter or switch to PawCoin | F25 / F14 | |
| SH-RF-12 | Payout failed, switch to PawCoin | On F25 choose PawCoin | Instant credit + compensation (platform cause) | F14 | |
| SH-RF-13 | Output does not leak account | [API] order detail `refunds[]` | Only `payoutAccountLast4`, `payoutChannel`, `payoutFee`, no full account number / account name | — | |
| SH-RF-14 | Log does not leak account | After submitting the payee account, check the API log | Account number and account name redacted (integration defect 2 regression) | — | |
| SH-RF-15 | Returned due to user fault | P8 order (payable 105.475) after shipping → WHOLE + USER_FAULT_RETURNED | Order REFUNDING, **no refund order generated for now**; after `/admin/stag/shop/orders/{token}/return-inbound` receives by line, a refund order is generated: refund 105.475 − 10.000 (shipping actually paid) − 475 (insurance) = **95.000**; **no compensation, coupons not returned**; stock added only once | F13 / F23 | |
| SH-RF-16 | Lost / damaged, insured | Another P8 same-structure order after shipping → WHOLE + LOST_DAMAGED | Refund **95.000** (actual amount paid for items); coupon CP-5K returned; choosing PawCoin gives bonus 9.500; stock not restored | F13 / F14 | |
| SH-RF-17 | Lost / damaged, uninsured negotiated amount | Uninsured order LOST_DAMAGED, negotiatedAmount 50.000 and > actual amount paid for items respectively | 50.000 passes; exceeding the actual amount paid for items is rejected | — | |
| SH-RF-18 | Damage reported after delivery / completion | Completed order → COMPENSATION (negotiatedAmount ≤ actual amount paid for items) | Order status **unchanged**; detail `refunds[]` shows a compensation refund order, refund method selectable | F11 / F13 | |
| SH-RF-19 | Compensation cap | P2 order with PawCoin off (cash 230.000) → OUT_OF_STOCK → choose PawCoin | bonus = min(23.000, 20.000) = **20.000** | F14 | |
| SH-RF-20 | Compensation config 0 not shown | After setting Z7 back to 0, choose a refund method on a new refund order | Bonus not shown (go-live check item: default 0 means no compensation) | F14 | |
| SH-RF-21 | Growth points clawback | After each refund portion completes, [DB] growth points ledger | Clawed back once each when the coin portion is returned, when cash is switched to PawCoin, and when offline payout is marked paid out; idempotency key `SHOPREF:{token}:{portion}`; proportional to the refund amount, cumulative total not exceeding what was originally credited; compensation amount not counted | — | |
| SH-RF-22 | Unselected for 7 days only reminds operations | [DB] change the `created_at` of an AWAITING_DESTINATION refund order to 8 days ago, wait for the 09:00 WIB job | Only writes an operations reminder, no push to the user | — | |
| SH-RF-23 | Cancel blocked without purchase receiving record | Order and pay for a SKU with no purchase receiving, then cancel | 422 (go-live check item, confirm the prompt is understandable) | — | |
| SH-RF-24 | Return entry still closed | Order detail | No return request entry; the refund method page can only be entered from "Select refund method" | F6 / F11 | |

### K. My Coupons, Redemption, Coupon Expiring Soon (SH-CPN)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-CPN-01 | Usable list | [Emulator] My coupons | "Bisa dipakai ({n})"; coupon face value, "Min. / Maks.", validity; EXP-SOON marked "Segera berakhir"; top redemption box "Masukkan kode kupon" "Tukar" | G1 | |
| SH-CPN-02 | Occupied coupon greyed out | While a pending-payment order occupies CP-5K, look at the usable list | That coupon is greyed out, marked "occupied by a pending-payment order", not tappable (C-3) | G1 | |
| SH-CPN-03 | Used | Switch to "Sudah dipakai" | Lists USED coupons "Dipakai {date} · {order number}", tapping navigates to the order | G2 | |
| SH-CPN-04 | Expired and voided not shown | [DB] set one coupon's `valid_until` to the past; [Admin browser] disable another coupon and choose void | Neither appears under usable / used | G1 | |
| SH-CPN-05 | Coupon occupied at void time | The coupon was already occupied by a pending-payment order before disabling and voiding → pay / cancel | Pay: becomes USED as normal; cancel: becomes VOIDED, not returned | — | |
| SH-CPN-06 | Usable is empty | After all coupons are used or voided | "Belum ada kupon / Tukar kode di atas, atau klaim kupon di halaman produk." | G4 | |
| SH-CPN-07 | Load failed | Enter My coupons while offline | "Gagal memuat kupon." + retry | G5 | |
| SH-CPN-08 | Redemption succeeds | Enter the single-use code CODE-S | "Kupon berhasil ditukar", the new coupon is marked "Baru"; tracking source = redeem code | G6 | |
| SH-CPN-09 | Code does not exist | Enter `NOPE999` | "Kode kupon tidak valid" | G7 | |
| SH-CPN-10 | Single-use code already used | Enter the code just used again | "Kode ini sudah dipakai" | G7 | |
| SH-CPN-11 | Outside issuing period | Enter `TTQAFUT` | "Kode ini tidak dalam masa berlaku" | G7 | |
| SH-CPN-12 | Already claimed | Redeem `TTQA142` twice | Second time "Kamu sudah klaim kupon ini" | G7 | |
| SH-CPN-13 | Fully claimed | With another account (or [DB] adjust `issued_count`) use up CODE-U's total of 2, then redeem | "Kupon sudah habis" | G7 | |
| SH-CPN-14 | Case normalization | Enter lowercase `ttqa142` (new account or delete the record first) | Equivalent to uppercase | G6 | |
| SH-CPN-15 | Brute-force lockout | Enter wrong codes (non-existent) 10 times within 1 hour | After the 10th: "Terlalu banyak percobaan. Coba lagi dalam 1 jam.", during the lockout even a correct code is rejected (429); after testing, [DB / Redis] clear `rl:coupon:redeem-lock:<uid>` | G8 | |
| SH-CPN-16 | Redeem while logged out | Guest enters My coupons (if there is an entry) | Log in first | — | |
| SH-CPN-17 | Coupon expiring soon push | Hold EXP-SOON (expires in 30 hours), wait for the 10:00 WIB job (or temporarily change the cron) | Push "{n} kupon segera berakhir / Pakai sebelum {date}. Jangan sampai hangus!", multiple coupons on the same day are merged into one; tapping opens My coupons; each coupon is reminded only once (`expiry_notified_at`); server-side `toko_coupon_expiry_push_sent`, on open `toko_coupon_expiry_push_opened` | G9 | |
| SH-CPN-18 | Not sent when promo switch is off | Turn off "Promotions and events" in settings and then seed another soon-to-expire coupon | No push | — | |
| SH-CPN-19 | Newcomer coupon unique | [Admin browser] create another newcomer coupon within the NEWBIE issuing period | Rejected (only one allowed at a time) | — | |

### L. Address Book (SH-ADDR)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-ADDR-01 | List and non-deliverable labels | [Emulator] Me → Address management (with Z-OK / Z-NC / Z-OFF addresses) | Z-NC, Z-OFF addresses labeled "Belum bisa dikirim kurir"; Z-OK not labeled | H1 | |
| SH-ADDR-02 | Quote failure not labeled | Address with postal code 99998 | `serviceable=null`, the App does not label it | H1 | |
| SH-ADDR-03 | Empty address book | Delete all addresses | "Tambah alamat untuk mulai belanja." | H2 | |
| SH-ADDR-04 | Add / edit | Add → three-level region selection → 5-digit postal code → label Rumah / Kantor / Lainnya → save; then edit | Form is the same set as in the checkout page; "Ubah alamat" "Wilayah" "Pilih wilayah"; labels localized | H3 / H4 | |
| SH-ADDR-05 | Delete confirmation | Delete an address | "Hapus alamat ini?" deleted after confirming | H5 | |
| SH-ADDR-06 | Probe quote cache | [DB] `shipping_quotes` | The same postal code reuses the probe quote within 24 hours, no repeated quoting | — | |

### M. Repurchase Taken Offline (SH-RP)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-RP-01 | No restock card / profile-creation prompt on home | [Emulator] look at the top of Toko once as logged-in and once as guest | Neither shows a restock card or the "Create profile" prompt bar | A1 / A4 | |
| SH-RP-02 | No repurchase card in Diary | Enter the Diary growth archive page | No repurchase card | — | |
| SH-RP-03 | Food quantity estimation stopped | [DB] the repurchase trigger table has no new rows after deployment; backend log has no food-quantity scan output | No new triggers are generated; historical records and `entry_source / trigger_type` are kept | — | |
| SH-RP-04 | Open an old repurchase push | [DB] seed a historical `REPURCHASE_FOOD_LOW` notification, open it in the App | Lands on the Toko home page, no error | A1 | |
| SH-RP-05 | Recommendation bar not deleted by mistake | — | `/me/shop/recommendations` works normally (same as SH-HOME-20) | A1 | |

### N. Network Failures and Exceptions (SH-NET)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-NET-01 | Checkout quote failed | Lose network when entering checkout | Error notice + retry, no wrong amounts shown | D8 | |
| SH-NET-02 | Network lost at I-have-paid | Lose network when tapping "Saya sudah bayar" on the panel | Notice that the query failed, can try again; does not misjudge as paid / unpaid | E4 | |
| SH-NET-03 | Network lost on submitting select refund method | Lose network at Kirim | Error notice; resubmitting after recovery does not produce two credits | F14 | |
| SH-NET-04 | Network lost on claiming a coupon | Lose network when claiming a coupon on detail | Notice of failure, the button remains claimable | B7 | |
| SH-NET-05 | Network lost on order detail | Open order detail while offline | Load failure + retry | F18 | |

### O. Legacy Version Compatibility (SH-OLD)

> Preferably install the **1.4.1 stag build** on the emulator; if no old build is available, use [API] calls that mimic the 1.4.1 request shape.

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-OLD-01 | Legacy place order | With the 1.4.1 build (or [API] `POST /me/shop-orders` without `quoteToken`), place an order for A1×2 | Uses legacy checkout mode: cheapest courier JNE 10.000 chosen automatically, **no coupon**, coins used automatically, no insurance; free-shipping threshold follows the new rules; order has `checkout_source=LEGACY` | — | |
| SH-OLD-02 | Legacy checkout shipping fee | [API] `GET /me/checkout` | Shipping fee follows the lowest Biteship quote (10.000), no longer reads the fixed regional fee | — | |
| SH-OLD-03 | Legacy unsellable / undeliverable | Legacy build places an order for P-C or a Z-NC address | Receives the 409 / 422 that the legacy build already recognizes, no crash | — | |
| SH-OLD-04 | Legacy banner | [API] `GET /shop/banner` | Still returns one (the first), legacy App home page works normally | — | |
| SH-OLD-05 | Legacy order detail | 1.4.1 build views orders created by the new version (with coupon, negative free-shipping amount, refund orders) | No crash, amount fields are readable | — | |
| SH-OLD-06 | Legacy payment | 1.4.1 build goes to pay from the pending-payment state | Original panel works (stag has no real code, only verify no crash) | — | |

### P. Analytics, Security, Go-Live Checks (SH-MISC)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SH-MISC-01 | Banner analytics | Swipe to the 3rd banner and tap it | `toko_banner_shown / tapped` has `position` = 3; the coupon-claim banner's `target_type` is the coupon-claim value | — | |
| SH-MISC-02 | Repurchase analytics disabled | Throughout | No `toko_repurchase_card_*` | — | |
| SH-MISC-03 | External identifiers not enumerable | [API] outputs of coupon, order, refund, quote | Use tokens, do not expose auto-increment ids (review id is a registered legacy exception) | — | |
| SH-MISC-04 | stag entry points not registered in production | Code / production profile check (do not call in production) | `ShopStagController` and `ShopStagCouponController` are both `@StagOnly` | — | |
| SH-MISC-05 | SKU parcel parameters complete (C-13) | [DB] stag on-sale SKUs | All have weight and length/width/height; origin postal code and regional postal codes are configured | — | |

---

### Q. UI Fidelity (per-screen scoring, standard >= 95 points)

**Method**: capture screenshots on the emulator with the stag build + test account (Indonesian language) -> compare side by side with the corresponding screen in `ui-1-4-2-shop-integrated-v1.html` (export each screen as PNG with ui-mockup-shots) -> score on layout, spacing, font size and weight, color, icons, copy, and state completeness; list the differences for screens scoring < 95. When the design mockup and the current build conflict, default to following the mockup, but whatever the decision log / PRD §6 has overturned takes precedence (e.g. no second confirmation on confirm receipt, recommendation header "Untuk {pet}", detail page order "title -> price"). **93 screens** in total, in 6 batches:

| Batch | Group | Screens | Count | Required seed data / state |
|---|---|---|---|---|
| 1 | A Home and search | A1, A2, A3, A4, A5, A6, A7, A8, A9 | 9 | Z5 banner, Z4 new-user coupon, pet profile (weight filled / not filled), guest state, offline |
| 2 | B Product detail | B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13, B14, B15 | 15 | Z2 region (Z-OK / Z-NC / Z-FAIL), P-C unsellable, P-E delisted, P-F no reviews, review switch on |
| 3 | C Cart | C1, C2, C3, C4, C5, C6, C7, C8 | 8 | Invalid items, empty cart, guest, offline |
| 4 | D Checkout | D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11, D12, D13, D14, D15, D16, D17, D18 | 18 | Baseline P1~P5, addresses 99999 / 99998, stock cleared and coupon invalidated (D16 / D17 must be staged at the moment of submission) |
| 5 | E Payment | E1, E2, E3, E4, E5, E6, E7 | 7 | Z9 fake code (E1~E4), Z10 timeout (E5), payment simulation (E6), submit as-is (E7) |
| 6a | F Orders (status) | F1, F2, F3, F4, F5, F6, F7, F9, F10, F11, F12, F18 | 12 | Orders in each status, stub logistics progression, exception confirmation, offline |
| 6b | F Orders (refund / reorder) | F13, F14, F15, F16, F17, F19, F20, F21, F22, F23, F25 | 11 | Refund orders and late payments produced by SH-RF / SH-LATE |
| 7 | G Coupons + H Address | G1, G2, G4, G5, G6, G7, G8, G9, H1, H2, H3, H4, H5 | 13 | Redeem code, locked state, expiring-soon push (G9 requires waiting for the push or checking the notification center) |

Total 9 + 15 + 8 + 18 + 7 + 12 + 11 + 13 = **93 screens**. Scoring results are attached in a separate table (screen / score / differences / screenshot path).

---

### R. Items That Cannot Be Executed on stag or Need Special Seed Data

| Item | Situation | Handling |
|---|---|---|
| Real QRIS payment / gateway callback | stag gateway P04 does not issue codes | Payment simulation + DB-edited fake code; real callbacks and GemPay expired-code order query (C-12) can only be tested separately before production |
| Last order query before closing an order | The query hits an unavailable gateway and may return failure -> order is not closed | Record actual behavior; if stuck, advance with simulate-expired and then check order closing |
| Late payment | Payment simulation does not accept terminal-state intents | Reset the intent to PENDING and then simulate (see the note in section G); `paid_after_close` follows actual behavior |
| Overpayment / underpayment adjustment | Gateway actual receipt always equals the cash portion | Covered by integration tests, ⏭ |
| Real Biteship quote / order placement / insurance field / remote postal code accuracy | Stub mode, no test key | Test separately with a sandbox key before production |
| Waybill creation failure, retry alert | The stub's failure hook has no stag entry point | Money path covered by WAYBILL_FAILED cancellation; alerts rely on integration tests |
| Stub waybill | In memory, lost when the container restarts | Waybills must be recreated after a restart |
| Lark notifications (shipping reminders, alerts) | Not configured on stag | Only check backend logs |
| Push delivery (coupon expiring soon, reorder) | Emulator push channel is not guaranteed | Rely on notification center records + backend send logs; test on real devices separately |
| New-user coupon / new-user determination | The test account may no longer be a new user | Temporarily change `created_at`, restore after testing |
| Coupon expiring-soon push schedule | Only runs at 10:00 WIB daily | Wait for the schedule or temporarily change the cron (changing env requires a restart, confirm first) |
| Legacy compatibility | Requires the 1.4.1 stag build | Without the build, simulate via API and mark ⚠️ |
| Global config changes | Free-shipping cap, per-order limit, compensation ratio, and review switch are shared on stag | Restore after testing |

---

## C. Shop Admin (shop-admin) Acceptance Test Cases (stag admin console)

> Scope: the 20 stories of the shop-admin line (Epic 1~8; Epic 9 integration is this very execution) - AB-24 coupons / AB-25 banner / AB-27 share rewards / AB-28 exception orders and refund payout, late payment after cancellation / AB-29 repurchase decommissioned / AB-38 shipping fee, SKU parcel, fulfillment, logistics exceptions, reconciliation / AB-39 review management.
> Basis: admin PRD `shop/1-4-2-shop-admin-prd.md` (e-commerce folder), design mockup `ui-admin-1-4-2-shop-integrated-v1.html` (69 screens), epics / decision log (O-1~O-32) / architecture delta (AD-1~AD-18), the Completion Notes of the 20 stories, `shop-admin` three-language copy table. Order of authority: decision log > PRD > architecture > design mockup.
> Result: ✅ Pass | ❌ Fail (attach issue number) | ⏭ Skipped (state reason) | ⚠️ Pass with notes.

### 0. Execution Environment and Conventions

**Environment**

- Admin console: `https://ops-stag.tailtopia.id` (stag container `petgo-server-stag`, database `petgo_stag`, Redis DB3), branch stag (includes `98adc0d5f` full V1.4.2).
- App-side data: sign JWTs for test users with the stag JWT_SECRET and call `api-stag` endpoints to seed data; you can also edit `petgo_stag` directly. Record all seed data in the "Seed data list" at the end of the document.
- **Biteship = stub** (`BITESHIP_MODE=stub`): waybill creation always succeeds; advance status with the stag tool `POST /admin/stag/shop/orders/{token}/waybill/stub-status {"status":"picked|in_transit|delivered|courier_not_found|returned"}` (super admin session). The stub has no HTTP entry to "make the next order fail", so **waybill failures can only be produced by editing the DB**.
- **Lark shop reminders are not configured** (`LARK_SHOP_{CS,OPS,TECH}_WEBHOOK_URL` are empty): for reminder-type cases, check container log WARN + check `ops_alert_log` instead, and mark as "restricted"; actual delivery to the three groups is left for L2.
- **QRIS gateway unavailable**: always credit payments with the stag simulation button `simulate-paid` in the admin "Payment intent" drawer (`POST /admin/payments/{intentToken}/simulate-paid`).
- Screenshot upload (payout screenshots) goes to the OSS private bucket: if stag OSS is not configured, mark related cases as restricted.

**Admin accounts (Lark login; roles can be changed in "Account management" or by editing `admin_accounts` in the DB, restore after testing)**

| Code | Role / permissions | Purpose |
|---|---|---|
| S1 | Super admin | Main operator |
| S2 | Super admin (second account) | Separation of duties: payout by someone other than the initiator |
| M | OPS_MANAGER | Has `shop.order_view/fulfill`, `shop.product_*`, `config.view/edit`, `refund.view`, `user.view`; **does not have** `refund.payout`, `shop.coupon_*`, `shop.finance_view`, `config.share_reward_edit` |
| N | CUSTOM, given only one code unrelated to the shop (e.g. `content.view`) | No permission: menu not visible + direct access returns 403 |
| CV | CUSTOM, given only `shop.coupon_view` | Coupon page read-only |
| F | CUSTOM, `refund.view` + `refund.payout` | Finance payout (non-initiator) |

**Execution step tags**: steps begin with [Browser] [API] [DB] [Log]. To check logs: `docker logs --since 10m petgo-server-stag 2>&1 | grep -E 'OpsAlert|LARK_SHOP'`. To check audit: the admin "⚙️ Operation audit" page (`/admin/audit-logs`) or `SELECT action_type,target_type,target_id,summary,actor_account_id,created_at FROM admin_audit_logs ORDER BY id DESC LIMIT 20;`.

**Error code conventions**: no permission -> 403; separation of duties (payer = initiator) -> 403 (explicit in AC); **all other admin business rejections (validation failure, state already changed, wrong category, etc.) are expected to be 422**, with the error message shown inside the modal / drawer, no blank page, no stack trace. ⚠️ The implementation notes of story 5-1 / 6-2 / 7-2 say some state conflicts return **409**; if 409 is observed, mark ⚠️ and attach the endpoint, then submit the summary for a decision (the App-side confirm-receipt `return-inbound-confirmed` is 409 by design per O-16, and is not in this group).

**Amount conventions (shared throughout the document, Rp, dot as thousands separator)**

- Amount due `T = total_amount`; coin portion `C = coin_amount`; cash portion `= cash_amount`.
- Shipping fee actually paid by the user `= shipping_fee + shipping_discount` (`shipping_discount ≤ 0`); insurance fee `= insurance_fee`; goods actually paid `= T − user-paid shipping − insurance fee`.
- Two-portion split: coin portion `= ⌊this refund × C / T⌋`, the remainder goes to the cash portion.
- Platform reason: refund `T − already refunded`; user reason: refund `goods actually paid − already refunded`, no compensation and no coupon returned; lost/damaged parcel: insured = goods actually paid, uninsured = negotiated amount (≤ goods actually paid), with compensation and coupon returned.
- UI reference example (D3~D7): T=146.500, C=50.000, cash 96.500, shipping actually paid 9.000, insurance fee 700, goods actually paid 136.800 -> user reason 136.800 splits into 46.689 / 90.111 (the UI's 46.690 is a rounded illustration; follow the calculator's round-down); negotiated 100.000 splits into 34.129 / 65.871; partial cancellation of one line 38.000 splits into 12.969 / 25.031; last line converted to whole-order remainder 108.500 splits into 37.031 / 71.469 (= coin portion − coin already refunded).
- Expected-value SQL template: `SELECT display_no,total_amount T,coin_amount C,cash_amount,shipping_fee,shipping_discount,insurance_fee,discount_coupon_amount,refunded_total,refunded_coin FROM shop_orders WHERE display_no='…';`

### 1. Prerequisites and Seed Data

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-PRE-01 | Version and migrations | [DB] `SELECT version,success FROM flyway_schema_history WHERE version LIKE '20261106%' ORDER BY 1;` | `20261106.1000 / 1005 / 1010 / 1015 / 1020 / 1025 / 1030 / 1035 / 1040` all nine rows have success=true | — | |
| SA-PRE-02 | Accounts in place | [Browser] S1 confirms the status and role of the six accounts S2 / M / N / CV / F in Account management; N, CV, F have permission codes configured with custom roles | Six accounts can log in, roles match the table above; new codes `shop.coupon_view/edit` are not granted to any position by default | — | |
| SA-PRE-03 | Shipping origin and balance | [Browser] S1 configures the shipping origin (with postal code) in "Service area and shipping fee", enters Biteship balance 2.000.000, threshold 500.000, free-shipping threshold 150.000 / max discount 20.000; confirm the test Kecamatan is enabled and has a representative postal code | App checkout can get quotes (stub returns a courier list) | G1 | |
| SA-PRE-04 | Test products | [Browser] Pick 3 on-sale products (A, B, C, 1 SKU each), fill in weight / length / width / height; note C's stock | All three SKUs have complete parcel data and are sellable | G6 | |
| SA-PRE-05 | App test users | [DB] Pick two stag users U1 (normal) and U2 (to be deactivated later); [API] call `GET /api/v1/me/coupons` with a JWT to verify they work; give U1 enough PawCoin (admin coin grant or DB edit) | Both users' endpoints work, U1 PawCoin ≥ 200.000 | — | |
| SA-PRE-06 | Order seeding method | [API] U1: `POST /api/v1/me/checkout/preview` -> `POST /api/v1/me/shop-orders` (PawCoin + QRIS mixed) -> `POST …/{token}/pay` to get `payment_intent_token`; [Browser] S1 opens that payment intent drawer and clicks "Simulate payment received" | Order becomes pending shipment, `coin_amount>0 and cash_amount>0`; record the order number | — | |
| SA-PRE-07 | Waybill and status progression | [Browser] Fulfillment drawer "Biteship order" or [API] `POST /admin/stag/shop/orders/{token}/waybill`; then `…/waybill/stub-status` to advance `picked` / `delivered` / `courier_not_found` / `returned` | Waybill is back-filled, order status / exception flag changes with status | H3 | |
| SA-PRE-08 | Waybill failure seeding | [DB] For a pending-shipment order with no active waybill: `UPDATE shop_orders SET waybill_failed_at=now(), waybill_fail_reason='REJECTED:40000001 courier unavailable' WHERE display_no='…';` (for the unknown-result variant use reason `UNKNOWN_RESULT:timeout`) | Order carries the "Waybill failed" flag and enters the exception order queue | H5 | |
| SA-PRE-09 | Late payment seeding | [API] U1 places an order without paying; [DB] set `expires_at` to 1 minute ago, wait for the order-close job to set the order to cancelled (or call `POST …/payment-check`); [Browser] "Simulate payment received" again on the original payment intent | Order `late_paid_amount` is non-null, `late_payment_resolution` is empty, flagged "Paid after cancellation" | F2 | |

### 2. Navigation, Permission Codes, and Repurchase Decommissioning (Story 1.1 · AB-29)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-NAV-01 | New menu order in the shop group | [Browser] S1 expands 🛍 Shop | In order: "Orders" before "Order fulfillment", "Refund payout" after "Return review", "Coupons" after "Top banner"; "Repurchase engine effect" no longer exists | D3 sidebar | |
| SA-NAV-02 | Three new pages open | [Browser] S1 visits `/admin/shop/order-list`, `/admin/shop/refund-payouts`, `/admin/shop/coupons` in turn | All 200, render real pages (not placeholders) | F1 / E1 / A1 | |
| SA-NAV-03 | Repurchase dashboard 404 | [Browser] S1 directly visits `/admin/shop/repurchase-dashboard` | 404, no redirect | — | |
| SA-NAV-04 | Feeding warning removed | [Browser] Open the edit page of a product with no daily recommended feeding amount filled | No "Daily recommended feeding amount not filled" warning; no "This field is the food-running-out estimate…" hint at the top of the feeding card; feeding field is still editable and saveable | — | |
| SA-NAV-05 | Role page footnote | [Browser] Accounts and roles -> edit any role, look at the matrix footnote | The footnote no longer contains the sentence "Repurchase engine effect is ops-config view or order view" | — | |
| SA-NAV-06 | New permission codes appear on the role page | [Browser] Find the shop group on the role edit page | Has two items "Coupon view / Coupon edit" (`shop.coupon_view` / `shop.coupon_edit`), with copy in all three languages (switch zh / EN / ID and check each once) | — | |
| SA-NAV-07 | New codes not pre-granted | [DB] `SELECT r.code,p.permission_code FROM admin_role_permissions p JOIN admin_roles r ON r.id=p.role_id WHERE p.permission_code LIKE 'shop.coupon%';` | 0 rows (not pre-granted to any position) | — | |
| SA-NAV-08 | M's visibility | [Browser] M logs in and looks at the shop group | Visible: Orders, Order fulfillment, Exception orders, Refund payout, Top banner, Service area and shipping fee; **not visible**: Coupons, Reconciliation | — | |
| SA-NAV-09 | M direct access to unauthorized pages 403 | [Browser] M directly visits `/admin/shop/coupons`, `/admin/shop/reconciliation` | Both 403 page (no blank page, no data leak) | — | |
| SA-NAV-10 | N sees nothing | [Browser] N logs in | Shop group not shown (or no pages above in the group); the to-do center has no "Shop to-dos" | — | |
| SA-NAV-11 | N direct access 403 | [Browser] N directly visits `/admin/shop/order-list`, `/admin/shop/refund-payouts`, `/admin/shop/coupons`, `/admin/shop/order-exceptions` | All 403 | — | |

### 3. confirm-modal Extension and Shop Page Replacement (Story 1.2)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-CM-01 | No native confirm on shop pages | [Browser] Trigger in turn: disable a shipping region, delete a banner, delist a product, stock write-off / stocktake, fulfillment drawer "Mark whole order as delivered", exception order partial cancel / whole-order cancel, return review execute refund, store purchase order mark as delivered | All show the in-site confirm-modal (no native browser confirm dialog), title / warning copy semantics are the same as before | D3 / D6 | |
| SA-CM-02 | Cancel does not write to DB | [Browser] Click "Cancel" in any modal from SA-CM-01; [DB] check the corresponding tables and audit | No data change, no new audit rows | — | |
| SA-CM-03 | Behavior unchanged after confirm | [Browser] Confirm once each for banner delist and region disable | Business result is the same as 1.4.1 (delist / disable takes effect, toast, list refresh), audit keeps the original action code | — | |
| SA-CM-04 | Amount breakdown block | [Browser] Exception order whole-order cancel modal | Amount block: label left, amount right, total row bold; amounts do not wrap, tabular numerals | D3 | |
| SA-CM-05 | Radio / dropdown | [Browser] Coupon disable (radio), review hide (dropdown) | Radio group / dropdown renders inside the modal; when nothing is selected the confirm button cannot submit or submitting reports 422 | A9 / J3 | |
| SA-CM-06 | Error inside modal | [Browser] In the partial-cancel modal change the quantity to more than the remaining cancellable quantity and confirm | 422, the error lands in the modal's error slot (message like "Quantity exceeds cancellable range"), modal does not close | D6 | |
| SA-CM-07 | State already changed | [Browser] Open the same banner delete confirmation in two tabs; confirm on tab A first, then on tab B | Tab B shows an error inside the modal (already deleted / does not exist), no 500 | — | |

### 4. Ops Alert Channel (Story 1.3, Lark not configured -> restricted)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-AL-01 | Only WARN written when not configured | [DB] Confirm the three `LARK_SHOP_*_WEBHOOK_URL` groups in the stag env are empty; trigger any reminder (e.g. SA-LP-01 late payment), then [Log] check | One WARN in the log, containing the channel (CS / OPS / TECH) and the event, business is not affected | — | |
| SA-AL-02 | Copy sanitization | [Log] Look at the copy of SA-AL-01 and every following reminder | Contains no phone number, shipping address, or payout account; orders appear by display number | — | |
| SA-AL-03 | Channel routing | [Log] Summarize the channel of these events: late payment, payout failure, unselected for over 7 days (CS); logistics stalled, confirm receipt clears exception, estimated balance below threshold, claim pending created (OPS); second late payment / duplicate payment (TECH) | Channels match the AD-5 table | — | |
| SA-AL-04 | Deduplication | [DB] `SELECT * FROM ops_alert_log ORDER BY sent_at DESC LIMIT 20;`; trigger the same dedup-key event repeatedly (e.g. the same snapshot checked twice below the threshold) | Only one row per key (whether a row is written when no URL is configured follows the implementation; if no row is written, instead check that only one WARN appears in the log) | — | |
| SA-AL-05 | No alert on rollback | [Browser] Trigger an operation that will fail and roll back (e.g. click reorder on a cancelled order -> rejected) | No corresponding alert in the log | — | |
| SA-AL-06 | Real delivery to the three groups | Execute after ops provides the webhooks | ⏭ stag has no Lark configured, left for L2 | — | |

### 5. Coupon Page (Story 2.1 / 2.2 · AB-24)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-CP-01 | Empty state | [DB] Confirm stag has no coupons (or first pick a non-existent combination in the filter); [Browser] S1 opens the coupon page | Empty state (emoji + copy); filtered-no-result shows the filter empty state | A2 | |
| SA-CP-02 | Create a spend-and-save coupon | [Browser] Create: spend-and-save, discount 20.000 / threshold 100.000, distribution period today ~ +7 days, validity fixed until +14 days, total 100, 1 per person, storewide, user claim -> save | Returns to the list and locates the new row; status "Active"; columns: type, value / threshold, distribution method, distribution period (validity in small text below), issued / total 0/100, used 0 | A1 / A3 | |
| SA-CP-03 | Create a free-shipping coupon | [Browser] Free shipping, max discount 15.000, threshold 0, specified category | "Max discount amount" appears as required; with a scope set it shows "Usable only if all products in the order are within the scope"; threshold 0 triggers the misconfiguration-guard modal (see SA-CP-08) | A4 | |
| SA-CP-04 | Redeem code · universal code | [Browser] Choose distribution method redeem code -> universal code `QA142`, total 50 | Save succeeds; [DB] `shop_coupon_codes` has one universal code; audit `SHOP_COUPON_CODES_CREATED` records the count, not the plaintext | A5 | |
| SA-CP-05 | Redeem code · batch codes | [Browser] Batch single-use codes, total 30 | [DB] 30 codes generated (= total); audit records only the count | A5 | |
| SA-CP-06 | Required-field validation | [Browser] Save without a distribution period; save a free-shipping coupon without a max discount | 422, the reason is listed in `.banner.err` at the top of the drawer; ⚠️ red field borders are not implemented (already noted in story 2-2), record as a UI fidelity deviation | A6 | |
| SA-CP-07 | Value ≥ threshold hard rejection (O-14) | [Browser] Save a spend-and-save coupon with discount 100.000 / threshold 100.000 | 422 error, cannot save, **no** misconfiguration-guard second confirmation | A6 | |
| SA-CP-08 | Misconfiguration-guard second confirmation | [Browser] A spend-and-save coupon with threshold 0; or a claimable coupon with total left blank (unlimited) -> save | confirm-modal pops up listing the risk items (threshold is 0 / total unlimited); cancel does not write to DB; after confirm it is written (the server carries `acknowledgeRisk`) | A7 | |
| SA-CP-09 | Validity earlier than distribution period end | [Browser] Fixed validity end date < distribution period end date | 422 error | A6 | |
| SA-CP-10 | New-user coupon distribution period overlap | [Browser] Create two new-user coupons with overlapping distribution periods | The second gets 422 "New-user coupon distribution period conflict" | A6 | |
| SA-CP-11 | Not-yet-started coupon can be fully edited | [Browser] Create a coupon whose distribution period starts tomorrow -> edit and change value, threshold, scope | Save succeeds; audit `SHOP_COUPON_UPDATED` summary lists only changed fields (`field: old->new`) | — | |
| SA-CP-12 | Started coupon only allows changing the total | [Browser] Edit SA-CP-02 | Value / threshold / distribution period / scope are shown read-only; only the total can be changed | A8 | |
| SA-CP-13 | Total can only increase | [Browser] In SA-CP-12 change the total from 100 to 80; then to 120 | 80 -> 422; 120 succeeds, audit `SHOP_COUPON_QUOTA_INCREASED` records 100->120 | A8 | |
| SA-CP-14 | Bypass read-only to change the value | [API] Use the browser's "Copy as cURL" to replay SA-CP-12's save request with a value field added | 422 rejection (server refuses the change), value in the DB is unchanged | — | |
| SA-CP-15 | Batch codes top-up on total increase | [Browser] SA-CP-05 total 30 -> 40 | [DB] Code count becomes 40 (10 more codes topped up in the same transaction) | — | |
| SA-CP-16 | Detail four-grid | [API] U1 claims one of SA-CP-02 and places an order that holds it without paying; U2 claims one, places an order, and pays; [Browser] click the coupon name | Claimed 2 / On hold 1 / Used 1 / order amount brought in = U2's order `total_amount` | A10 | |
| SA-CP-17 | Export redeem codes | [Browser] SA-CP-05 detail drawer "Export redeem codes" | Downloads a CSV with 30 codes (or 40 after top-up); audit `SHOP_COUPON_CODES_EXPORTED` records only the count; [Log] no plaintext codes | A10 | |
| SA-CP-18 | Disable · keep usable | [Browser] Disable a coupon that has claimed-but-unused ones, choose "Keep usable" | Status "Disabled"; App `GET /me/coupons` claimed coupons are still usable; can no longer be claimed / redeemed ([API] claim -> fails) | A9 | |
| SA-CP-19 | Disable · void | [Browser] Disable SA-CP-02, choose "Void"; the modal restates the counts of claimed-but-unused and on-hold coupons | Claimed-but-unused -> invalidated (not shown in the App's My coupons or the checkout page); the on-hold one of U1 is still usable for that order ([Browser] payment intent simulated credit succeeds); audit records the number of voided coupons | A9 | |
| SA-CP-20 | Cancel an order holding a coupon after voiding | [API] U1 places another order holding a coupon claimed before voiding, then cancels that order | The coupon becomes invalidated and is not returned ([DB] `user_coupons.status`) | — | |
| SA-CP-21 | List filters and status | [Browser] Filter once each by status (not started / active / ended / disabled), type, distribution method; [DB] change a coupon's distribution period to end yesterday | Filter results are correct; status is computed from the distribution period as "Ended"; summary bar "On hold" = number of coupons held by pending-payment orders | A1 | |
| SA-CP-22 | Concurrent total increase | [Browser] In two tabs, change the same coupon's total from 120 to 130 / 140 and submit almost simultaneously | Both succeed or the later one gets 422; final total = the last successful value; top-up codes / audit are not duplicated or garbled | — | |
| SA-CP-23 | Read-only account | [Browser] CV logs in to the coupon page | No "Create", "Edit", "Disable", "Export"; universal code plaintext is not shown; direct access to `?create` / `?edit=` 403; POST save 403 | A1 | |
| SA-CP-24 | No permission | [Browser] M and N directly visit `/admin/shop/coupons` | 403 | — | |
| SA-CP-25 | State event log | [DB] `SELECT e.event,e.order_id,e.created_at FROM user_coupon_events e JOIN user_coupons u ON u.id=e.user_coupon_id WHERE u.user_id=<U1> ORDER BY e.id;` | Claim ISSUED, order LOCKED, cancel RELEASED (or void VOIDED), payment USED, one row each, matching the status changes one to one | — | |
| SA-CP-26 | Full audit coverage | [Browser] Filter the audit page by action | Create, edit, increase total, disable, void, generate codes, export each have a row, operator is correct, summary has no plaintext codes | — | |

### 6. User Detail · Coupons Tab (Story 2.3)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-UC-01 | Tab position | [Browser] S1 User management -> open U1's drawer | The original 9 tabs are all there, with the 10th "Coupons" at the end | A11 | |
| SA-UC-02 | Columns and status | [Browser] Look at U1's Coupons tab | For each coupon: name, type, value and threshold, how obtained (new user / claim / redeem code / banner), claim time, validity, status (usable / held by pending-payment order / used / expired / invalidated) | A11 | |
| SA-UC-03 | Expand status history | [Browser] Click a row | Below it expands the hold / return / use timeline, consistent with the SA-CP-25 event log; the order number of a returned coupon appears only in the history | A11 | |
| SA-UC-04 | Order number is clickable | [Browser] Click the order number of a held / used coupon | Jumps to the corresponding order drawer; URL uses a token, no auto-increment id | A11 | |
| SA-UC-05 | Empty state | [Browser] Open a user who has never claimed a coupon | Empty-state copy | A12 | |
| SA-UC-06 | Redemption lock banner | [API] U1 enters wrong redeem codes consecutively until locked; [Browser] refresh the tab | `.banner.warn` at the top "Redemption locked, unlocks in X minutes" (rounded up); no unlock button; [DB/Redis] TTL matches the minutes | A13 | |
| SA-UC-07 | Read-only | [Browser] Look in the tab for any edit / reissue / unlock entry | None | A11 | |
| SA-UC-08 | Permissions | [Browser] M (has `user.view`) can view; N opens user detail | M sees the tab; N has no user-page permission -> 403 | — | |

### 7. Banner Management Extension (Story 3.1 · AB-25)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-BN-01 | List structure | [Browser] S1 opens Top banner | Drag handle at the start of each row, no "Weight" column, new "Schedule" column, "Original size" column kept; page header description rewritten | B1 | |
| SA-BN-02 | Six states | [Browser] Seed: 1 not listed; 1 listed but before go-live time; 6 active; a 7th active; 1 past its offline time; 1 whose linked product is delisted | Statuses are respectively Not listed / Pending go-live / Active x6 / Over display limit / Expired / Target invalid | B1 | |
| SA-BN-03 | Admin and App consistent | [API] `GET /api/v1/shop/banners` | Returns exactly the 6 "Active" ones from the admin console, in the same order as the admin list; target-invalid, over-limit, pending go-live, and expired ones are not delivered | B1 | |
| SA-BN-04 | Drag to reorder | [Browser] Drag the 3rd to the 1st position | Save success toast; [API] App order changes accordingly; [DB] `sort_weight` follows the new order N..1; audit `SHOP_BANNER_REORDERED` records the before and after order | B1 | |
| SA-BN-05 | No submit when drag is unchanged | [Browser] Drag and drop back to the original position | No request, no audit | — | |
| SA-BN-06 | Concurrent reorder (state already changed) | [Browser] After tab A deletes a banner, tab B (not refreshed) drags to reorder | Tab B gets 422 (list has changed, please refresh), rows revert to the original order | — | |
| SA-BN-07 | New one goes to the end | [Browser] Add a banner | The new row is at the end of the list and does not displace active ones | — | |
| SA-BN-08 | Drawer fields | [Browser] Edit any banner | Title "Edit Banner" ("Add Banner" when adding); no weight field; link dropdown has no "Category" and has "Coupon (claim)"; has go-live / offline times; permanent size note under the upload box (1056 × 444, ≤10MB, etc.) | B3 | |
| SA-BN-09 | Edit button text | [Browser] Look at the row buttons in the list | Shows "Edit" (no longer shows the wrong-key title copy) | B1 | |
| SA-BN-10 | Claim-coupon link lists only claimable coupons | [Browser] Choose coupon as link type, expand the dropdown | Lists only ACTIVE, user-claim coupons whose distribution period has not ended; new-user coupons and redeem-code coupons are not listed | B3 | |
| SA-BN-11 | Claim-coupon banner landing | [Browser] Save a claim-coupon banner; [API] App banners returns that entry with the coupon link; U1 taps claim (`POST /api/v1/me/coupons/claim`) | Claim succeeds; the coupon's "Issued" in the admin console +1 | B3 | |
| SA-BN-12 | Existing category link must be re-selected | [DB] Change a banner to `target_type='CATEGORY'`; [Browser] open edit | Hint at the top that it must be re-selected; link type left blank; save is greyed out; [API] replaying save (type empty or CATEGORY) -> 422 | B4 | |
| SA-BN-13 | Invalid target | [Browser] Link to a delisted product / enter http:// for an external link | 422, `.banner.err` at the top of the drawer, form and ratio hint are kept | B5 | |
| SA-BN-14 | Aspect ratio reminder | [Browser] Upload one 1056×444 and one 1000×1000 | The former shows "1056 × 444 · about 2.4:1" beside it; the latter shows `.banner.warn` it will be cropped, **still saveable** | B6 | |
| SA-BN-15 | Auto-marking of invalid target | [Browser] Disable the coupon bound to the claim-coupon banner (or let it run out) | That banner's status becomes "Target invalid", the App no longer delivers it | B1 | |
| SA-BN-16 | Schedule audit | [Browser] Change go-live time, change link target | Audit summary lists only changed fields (schedule old->new, target old->new) | — | |
| SA-BN-17 | Empty state | [DB] Temporarily clear within a transaction / or use a database without banners (may be skipped) | Empty state `.banner.warn` + copy | B2 | |
| SA-BN-18 | Permissions | [Browser] M (`shop.product_edit`) can edit; N directly visits `/admin/shop/banners` | M normal; N 403 | — | |

### 8. Share-Reward Funding Reminder (Story 3.2 · AB-27)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-SR-01 | Migration only changes the default | [DB] `SELECT column_default FROM information_schema.columns WHERE table_name='pawcoin_config' AND column_name='share_reward_enabled';` and `SELECT share_reward_enabled FROM pawcoin_config;` | Default is false; the value in existing rows is the same as before release (O-1, not overwritten) | — | |
| SA-SR-02 | Persistent warning | [Browser] S1 Ops Config → share-reward card | The top of the card permanently shows "The PawCoin granted by share rewards is real money; set amounts with caution." | C1 | |
| SA-SR-03 | Estimate modal | [Browser] Monthly cap 0→2.000, ID card per-time 0→100, daily cap 0→1, save | confirm-modal lists old/new values of the three items + real-money warning + "Each account can earn at most 2.000 PawCoin per month" (min(2.000, 100×1×days in the month); November has 30 days → 3.000, take 2.000) | C2 | |
| SA-SR-04 | Estimate uses days in the current month | [Browser] Change monthly cap to 10.000, then view the modal | Estimate = 100×1×30 = 3.000 (November); the note reads "× 30 days in the month" (⚠️ the trilingual table originally says "× 30 days"; per AD-8 it is the days in the current month) | C2 | |
| SA-SR-05 | Multi-card sum | [Browser] Four card types, 50 per time and 2 per day each, monthly cap 100.000 | Estimate = (50×2×4)×30 = 12.000 | C2 | |
| SA-SR-06 | Cancel does not persist | [Browser] Click cancel in the modal | Config unchanged, no new row in `config_change_logs` | — | |
| SA-SR-07 | Confirm persists and logs | [Browser] Confirm save; [DB] latest row of `config_change_logs` | Before and after values complete; overview numbers refresh | — | |
| SA-SR-08 | Validation failure | [Browser] Enter a negative number and save | 422, error slot at the bottom of the card, no estimate modal | — | |
| SA-SR-09 | Estimate when master switch is off | [Browser] Save with master switch off and non-zero amounts | Estimate shows 0 (⚠️ implementation rule is "nothing is paid while off", to be confirmed) | C2 | |
| SA-SR-10 | Permissions | [Browser] M (without `config.share_reward_edit`) views the share-reward card; [API] replay the S1 save request with the M session | Card is read-only or hidden; POST 403 | — | |
| SA-SR-11 | Cleanup | [Browser] Restore all values to pre-test (master switch to its pre-test state) | Restore succeeds, avoiding real coin payouts on stag | — | |

### 9. Product Review Management (Story 3.3 · AB-39)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-RV-01 | Tabs | [Browser] S1 product edit page | Under the page header, two tabs "Product info / Reviews"; the original form is unchanged inside "Product info" | J1 | |
| SA-RV-02 | Review list | [DB] Find a product with ≥21 published reviews (otherwise seed data by inserting into `shop_reviews`); [Browser] Reviews tab | Lists rating, content (original text), images, user (community nickname), time; includes hidden rows; 20 per page pagination; summary of total / shown / hidden / average of shown | J1 | |
| SA-RV-03 | Empty state | [Browser] A product with no reviews | Empty-state copy | J2 | |
| SA-RV-04 | Hiding requires a reason | [Browser] Hide one review, confirm without choosing a reason; then choose "Advertisement" and confirm | No reason → 422 / button cannot submit; after choosing, success, row shows hidden and the reason | J3 | |
| SA-RV-05 | App-side recalculation | [API] `GET /api/v1/shop/products/{token}/reviews` (with the display switch on) and the product detail average / count | Hidden reviews are not returned; average and count are recalculated from the remainder | — | |
| SA-RV-06 | Restore display | [Browser] On a hidden row, "Restore display" → confirm | Restore succeeds, reappears in the App; [DB] `hidden_reason` / `hidden_by_admin_id` cleared | J4 | |
| SA-RV-07 | No delete entry | [Browser] Look for delete within the tab | None | J1 | |
| SA-RV-08 | Cross-product tampering | [API] Replace the review token in the hide request with a review of another product | 422 / 404 rejected | — | |
| SA-RV-09 | Repeated hide | [Browser] Hide the same review one after another in two tabs | Second time is idempotent, no duplicate audit written | — | |
| SA-RV-10 | Display switch off by default | [DB] `SELECT review_display_enabled FROM shipping_settings;`; [Browser] Ops Config "Product review display" card | Default false; card shows off | J5 | |
| SA-RV-11 | Turning on requires confirmation | [Browser] Turn on the switch → modal restates → confirm | Takes effect; App product detail shows the review list; audit `SHOP_REVIEW_DISPLAY_TOGGLED` records before and after | J6 | |
| SA-RV-12 | Turning off falls back | [Browser] Turn off the switch | App shows only the average and count | — | |
| SA-RV-13 | User is not notified | [DB] After hiding, check the review author's notification table | No new notification | — | |
| SA-RV-14 | Permissions | [Browser] M (`shop.product_edit` + `config.edit`) can hide and toggle the switch; N visiting product edit directly gets 403 | As described | — | |
| SA-RV-15 | Audit | [Browser] Operation audit | One row each for hide (with reason), restore, and switch | — | |

### 10. Service Area and Shipping Fee (Story 4.1 · AB-38)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-SH-01 | Page structure | [Browser] S1 opens Service Area and Shipping Fee | New "Ship-from address" and "Biteship account" cards; the area table has no shipping-fee column; "Deliverable areas", "Free-shipping threshold", and "Return receiving address" are kept; page header description rewritten | G1 | |
| SA-SH-02 | Ship-from address not configured | [DB] After a backup, clear `shipping_settings.origin_postal_code` (and related origin_*); [Browser] refresh | `.banner.err` at the top of the page; ship-from address card has an empty form; [API] App checkout rate quote fails / prompts not deliverable | G2 | |
| SA-SH-03 | Save ship-from address | [Browser] Fill everything and save → confirm-modal restates → confirm | Page-top error disappears; audit records before and after; App can get rate quotes | G1 | |
| SA-SH-04 | Max discount required (O-27) | [Browser] Threshold 150.000, max discount left blank / 0 → save | 422 "Required when threshold is greater than 0", field outlined in red, not persisted | G4 | |
| SA-SH-05 | Threshold 0 skips validation | [Browser] Threshold 0, max discount empty → save | Success | — | |
| SA-SH-06 | Bypassing the frontend | [API] Replay `/threshold` with threshold>0 and no `freeShippingCap` | 422 | — | |
| SA-SH-07 | Area postal code | [Browser] Add an area with a representative postal code; edit an existing area's postal code | Save succeeds; [DB] `postal_code` updated, original `fee` column kept unchanged | G1 | |
| SA-SH-08 | Estimated balance calculation | [Browser] Enter balance 2.000.000; then place 3 waybills ([DB] each `carrier_cost + insurance_cost`), and cancel 1 of them | Estimate = 2.000.000 − the two uncancelled (shipping + insurance fee); page shows "N waybills deducted since, excluding cancelled"; notes it is an estimate | G1 | |
| SA-SH-09 | Not entered | [DB] After a backup, clear `biteship_balance_snapshots` (or use a fresh environment; may be skipped) | Shows "Not entered", no warning | — | |
| SA-SH-10 | Below threshold | [Browser] Set the threshold above the current estimate (e.g. 3.000.000) | `.banner.warn` at the top of the page + estimated balance in red; [Log] one OPS WARN, dedup key `biteship-balance:{snapshotId}` (restricted) | G3 | |
| SA-SH-11 | One reminder per entry | [Browser] Place another waybill (triggers the check again) | No new reminder | — | |
| SA-SH-12 | Re-entry restarts deduction | [Browser] Enter 5.000.000 | Estimate is deducted afresh from 5.000.000; [DB] snapshot records `amount`, `estimate_before` (estimate at entry time), entering user, time; page-top warning disappears | G1 | |
| SA-SH-13 | Audit | [Browser] Operation audit | One row each for ship-from address, max discount, balance entry (records entered value and estimate at the time), threshold | — | |
| SA-SH-14 | Permissions | [Browser] M (`config.edit`) can edit; create an account with only `config.view` or use N to visit directly | `config.view` read-only, POST 403; N 403 | — | |

### 11. SKU Package Data (Story 4.2)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-SKU-01 | List filter and badge | [DB] Set one SKU's `package_weight_g` to null; [Browser] Product management, choose "Package data: missing weight/dimensions" | Only products containing missing SKUs are listed; a missing badge beside the product name; summary "SKUs missing weight/dimensions" = DB count, >0 shown in red; filter kept when paging | G5 | |
| SA-SKU-02 | Edit page columns and banner | [Browser] Open that product's edit page | Spec table has "Weight (g)" and "L×W×H (cm)" columns, missing shown as "Not filled" in red; red banner at the top of the card "n specs are missing…"; return rules and purchase-price columns kept | G6 | |
| SA-SKU-03 | All four fields required | [Browser] Edit a SKU, fill only weight without L/W/H and save; fill 0 and save | 422, not persisted | G6 | |
| SA-SKU-04 | Save after filling in | [Browser] Fill four positive integers and save | Success; badge disappears; audit `SHOP_SKU_UPSERTED` + one package row (with before and after) | G6 | |
| SA-SKU-05 | App sellable linkage | [API] App checkout of this SKU when package data is missing; checkout again after filling in | When missing, not sellable / checkout rejected; after filling in, can order | — | |
| SA-SKU-06 | Existing flow unaffected | [Browser] Save changes to price and stock-related fields | Normal | — | |

### 12. Exception Orders (Story 5.1 · AB-28)

> Seed data: this section needs at least 1 order for each of the 5 types (out of stock / waybill failed / pickup failed / returned / lost or damaged); create them using the methods in SA-PRE-06~08 and the SA-LG section; record T, C, shipping fee paid, insurance fee, and coupon for each.

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-EX-01 | Empty state | [Browser] Open Exception Orders after clearing the queue (or before seeding data) | "Queue cleared 🎉" + a link to other queues; on the right "Pick one from the queue on the left to start" | D2 | |
| SA-EX-02 | Auto-enqueue and source | [Browser] Refresh after creating all 5 types | Queue has 5 items, the first segment of meta being out of stock / waybill failed / pickup failed / delivery exception·returned / delivery exception·lost or damaged respectively; "Pending" count = 5; page header description rewritten | D1 | |
| SA-EX-03 | Source priority | [DB] Additionally set `waybill_failed_at` on the pickup-failed order; additionally set the shortage flag on the returned order | Source shows "Pickup failed" and "Returned" respectively (returned > lost or damaged > pickup failed > waybill failed > out of stock) | D1 | |
| SA-EX-04 | Delivered lost item enqueued | [Browser] Mark a delivered order as lost (SA-LG-09) | Enqueued, source lost or damaged | D1 | |
| SA-EX-05 | Continue after contacting user only for out of stock | [Browser] Check the resolution area item by item | Only the out-of-stock source shows "Continue after contacting user"; [API] replay that request on a waybill-failed order → 422 (⚠️ implemented as 409) | D1 | |
| SA-EX-06 | Platform-fault full-order cancel (out of stock) | [Browser] On the out-of-stock order choose "Platform fault", view the dry-run → full-order cancel → confirm | Dry-run: coin portion refunded immediately, cash portion awaits user's choice, compensation note, coupon returned, stock restored, total = T; the modal restates the same numbers; after submit: [DB] refund order `total_refund=T`, `coin_refund=C`, `cash_refund=T−C`; coupon back to AVAILABLE; stock restored; order leaves the queue | D3 / D8 | |
| SA-EX-07 | Dry-run = persisted | [DB] Compare the SA-EX-06 modal numbers with `shop_order_refunds` | Exactly identical | D3 | |
| SA-EX-08 | Success toast | [Browser] Check after SA-EX-06 submit | Toast includes where the cash portion goes (awaits user's choice in the App); left queue and counts refresh; right side jumps to the next order | D8 | |
| SA-EX-09 | Waybill-failed cancel | [Browser] Platform-fault full-order cancel on the waybill-failed order | Full refund; [DB] `waybill_failed_at` cleared; leaves the queue | D3 | |
| SA-EX-10 | Pickup-failed cancel | [Browser] Platform-fault full-order cancel on the pickup-failed order | Cancel the old waybill first ([DB] waybill `cancelled`) then refund; restore stock if the goods were not picked up | D3 | |
| SA-EX-11 | Pickup-failed old waybill cancel fails | [DB] Set this order's waybill `provider_status` to a non-cancellable state (e.g. `picked`) then cancel | Aborted, no refund order, no stock restored, the modal shows an error (restricted: cancel generally succeeds under the stub, simulate by editing the DB) | — | |
| SA-EX-12 | User fault · not inbound, greyed out | [Browser] On a returned order already judged as user fault (SA-LG-04) and not inbound, choose "User fault" | Dry-run: T − shipping fee paid − insurance fee, no compensation, no coupon refund, stock not auto-restored; `.banner.err` not-inbound notice; "Cancel full order and refund" greyed out; [API] replay → 422 (⚠️ implemented as 409) | D4 | |
| SA-EX-13 | User fault · cancel after inbound | [Browser] Cancel the full order after receiving all items line by line (SA-LG-05) | Refund total = amount paid for goods; two portions pro rata (sample 46.689 / 90.111); coupon not returned; **stock is not restored again** (it was added once at inbound); leaves the queue | D4 | |
| SA-EX-14 | Lost or damaged · insured | [Browser] On an insured lost order choose "Lost or damaged" | Refund amount fixed = amount paid for goods; compensation given, coupon returned, stock not restored | D5 | |
| SA-EX-15 | Lost or damaged · uninsured negotiated amount | [Browser] Uninsured lost order, negotiated amount 100.000 → dry-run → confirm | A "Negotiated refund amount" input appears; split into two portions (sample 34.129 / 65.871); exceeding amount paid for goods → 422 | D5 | |
| SA-EX-16 | Delivered lost item goes to compensation | [Browser] Handle the SA-EX-04 order | Goes through compensation (`compensate`), **order status unchanged** (still delivered / completed), a compensation refund order is created; leaves the queue | D5 | |
| SA-EX-17 | Category does not match source | [API] Replay full-order cancel on an out-of-stock order with `category=USER_FAULT` / `LOST_DAMAGED` | 422 (⚠️ implemented as 409); compensation and coupon refund cannot be toggled separately | — | |
| SA-EX-18 | Returned pending judgement cannot be cancelled | [Browser] View the resolution area of a returned order with no cause judged / [API] replay cancel | Cannot be cancelled, 422 | — | |
| SA-EX-19 | Partial-cancel split | [Browser] On an out-of-stock order (≥3 lines) cancel 1 unit of one line | Modal: amount paid for that line, coin portion rounded down and refunded immediately, remainder goes to cash, shipping/insurance/coupon not refunded; [DB] refund order scope=LINE amounts consistent; order stays in the queue | D6 | |
| SA-EX-20 | Partial-cancel quantity out of range | [Browser] Enter quantity 0 / more than remaining | 422 error shown inside the modal | D6 | |
| SA-EX-21 | Last line converts to full order | [Browser] Cancel the remaining lines one by one down to the last line | Inline form becomes full-order cancel, category can only be platform fault; refund = remaining unrefunded payable (including shipping and insurance, minus already refunded), sample 108.500 → 37.031 / 71.469 | D7 | |
| SA-EX-22 | Active waybill blocks partial cancel (O-21) | [Browser] Partial cancel on a to-ship order that already has a waybill | 422 (must cancel the waybill first) | — | |
| SA-EX-23 | Status already changed | [Browser] Open the same out-of-stock order in two tabs; A full-order cancel succeeds, then B submits | B gets 422 (order already handled), no duplicate refund order | — | |
| SA-EX-24 | Concurrent double-click | [Browser] Double-click the confirm button quickly / two people submit at the same time | Only one refund order, coins refunded only once | — | |
| SA-EX-25 | Continue after contacting user | [Browser] "Continue after contacting user" on an out-of-stock order | Out-of-stock flag cleared, leaves the queue, waybill can be placed normally | D1 | |
| SA-EX-26 | Deactivated user not enqueued | [DB] Mark the user of one U2 exception order as deactivated (`users.deleted_at`); refresh | Not in the queue; [API] replay the resolution → 422 (⚠️ implemented as 409) (O-26: goes to refund payout "Deactivated user · offline handling", but **generating an offline order for an exception order without a refund order is not implemented**, see §24) | — | |
| SA-EX-27 | App-side result | [API] U1 `GET /api/v1/me/shop-orders/{token}` | Cancelled, "Choose refund method" appears (cash portion pending selection) | — | |
| SA-EX-28 | Audit | [Browser] Operation audit | One row per resolution: category, reason, refund total, coin / cash split, coupon refund, stock restore, refund order number (excluding store rebate, O-23) | — | |
| SA-EX-29 | Permissions | [Browser] M can handle; N visiting directly gets 403; [API] replay cancel with the N session → 403 | As described | — | |

### 13. Refund Payout (Story 5.2 · AB-28)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-PO-01 | Empty state | [Browser] Open when there are no cash refunds (or switch to an empty tab) | Empty-state copy | E2 | |
| SA-PO-02 | Tabs and columns | [Browser] Open after SA-EX generates refunds | Tabs: Awaiting user choice / Awaiting payout / Payout failed / Paid out / Refunded as PawCoin / Deactivated user · offline handling; columns: order number, user, refund amount, source, channel and account (last 4 digits only), account holder, submit time, status; excludes return orders from return review | E1 | |
| SA-PO-03 | Summary | [DB] Sum `cash_refund` by status; [Browser] view the summary | Awaiting-choice amount, of which count over 7 days unchosen, awaiting-payout amount, and payout-failed count match the DB | E1 | |
| SA-PO-04 | User chooses refund as PawCoin | [API] U1 `POST /api/v1/me/shop-orders/{t}/refunds/{rt}/destination` choose PawCoin | The row moves to "Refunded as PawCoin" and no longer appears in the default tab; for platform fault, compensation is paid per the platform-fault compensation config ([DB] `compensation_amount`); growth points are deducted back immediately | E1 | |
| SA-PO-05 | User chooses offline payout | [API] U1 chooses BCA on another order and enters an account | Goes into "Awaiting payout"; GoPay / OVO show a channel fee | E1 | |
| SA-PO-06 | Unchosen over 7 days | [DB] Change a pending-choice refund order's `created_at` to 8 days ago; wait for the reminder job to run (or confirm the cron before restart) | Row marked "Over 7 days unchosen"; [Log] CS reminder once (dedup key `refund-overdue:{token}`, restricted); user is not pushed; running again does not repeat | E1 | |
| SA-PO-07 | Drawer full account | [Browser] S2 clicks an awaiting-payout row | Drawer shows full account, account holder, initiator, source, amount; [Log] the response does not print the account | E3 | |
| SA-PO-08 | Segregation-of-duties greyed out | [Browser] S1 (initiator of that order) opens the same row | Both forms greyed out with an explanation; [API] S1 replays mark-as-paid → 403; [DB] audit `SHOP_REFUND_DUTY_VIOLATION_BLOCKED` one row (independent transaction, present even if the request fails); super admin not exempt | E5 | |
| SA-PO-09 | Mark as paid | [Browser] S2 enters the reference number, uploads a jpg screenshot → confirm-modal (payee / account / reference number / payout amount) → confirm | Goes into "Paid out"; [API] App order detail "Refunded"; growth points are deducted back at this point ([DB] growth points ledger); audit records only the last 4 digits of the account, the reference number, and that a screenshot exists | E3 | |
| SA-PO-10 | Reference number required and duplicate | [Browser] Submit with the reference number blank; on another order enter the same reference number as SA-PO-09 | Blank → 422; duplicate → yellow notice that **does not block**, can submit | E3 | |
| SA-PO-11 | Screenshot limits | [Browser] Upload a PDF / an 11MB image | 422 rejected; images only, ≤10MB (restricted: if the stag OSS private bucket is not configured, all uploads fail, record ⏭) | E3 | |
| SA-PO-12 | Screenshot signed URL | [Browser] S2 views the screenshot; M (only `refund.view`) tries to view; copy the signed URL and open it again after it expires | S2 can view; M has no entry / 403; expired URL cannot be opened | — | |
| SA-PO-13 | Payout failed | [Browser] S2 on an awaiting-payout order "Payout failed" choose "Account information incorrect" → confirm | Goes into "Payout failed"; App prompts to re-enter the payout account; [Log] CS reminder (restricted); user is not pushed; audit records the reason | E4 | |
| SA-PO-14 | Failure requires a reason | [Browser] Submit without choosing a reason | 422 | E4 | |
| SA-PO-15 | User resubmits, back to awaiting payout | [API] U1 resubmits the payout account (or switches to PawCoin) | Back to "Awaiting payout" (or goes into "Refunded as PawCoin") | E1 | |
| SA-PO-16 | Status already changed | [Browser] S2 and F open the same awaiting-payout order at the same time; S2 marks paid first, then F marks failed | F gets 422, status stays paid out | — | |
| SA-PO-17 | Duplicate mark is idempotent | [Browser] Submit mark again on a paid-out order | Idempotent (growth points not deducted again, no duplicate audit) | — | |
| SA-PO-18 | Read-only role | [Browser] M opens Refund Payout | Can see the list (account masked), no action forms, cannot see the full account; [API] M replays mark → 403 | E1 | |
| SA-PO-19 | Finance role | [Browser] F (not the initiator) completes one mark-as-paid | Success | E3 | |
| SA-PO-20 | Deactivated-user tab | [API] U2 has a pending-choice / awaiting-payout cash refund order, then deactivate U2 (or edit the DB `users.deleted_at`) | The order appears only in the "Deactivated user · offline handling" tab, **not counted** in the badge | — (no mockup) | |
| SA-PO-21 | Offline close-out | [Browser] S2 enters a note (≤200 characters) and closes out; S1 (initiator) tries to close out | S2 succeeds, refund order COMPLETED, audit `SHOP_ORDER_REFUND_OFFLINE_CLOSED` (the note does not enter the audit); S1 → 403 + violation audit; empty note → 422 | — (no mockup) | |
| SA-PO-22 | No permission | [Browser] N visits `/admin/shop/refund-payouts` directly | 403 | — | |

### 14. To-Do Center · Shop To-Dos (Story 5.3)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-TD-01 | Entry | [Browser] S1 expands 📥 To-Do Center | A "Shop to-dos" subheading at the end of the group + "Exception orders ↗" and "Refund payout ↗", each with a count | D0 | |
| SA-TD-02 | Count definition | [DB] Exception orders = queue length; refund payout = awaiting payout + payout failed + awaiting choice over 7 days | Both numbers match the page and the `GET /admin/nav/badges` response; group badge = original items + these two | D0 | |
| SA-TD-03 | Deactivated users not counted | [Browser] Compare before and after SA-PO-20 | Refund payout count excludes the deactivated-user tab | D0 | |
| SA-TD-04 | Navigation | [Browser] Click the two items | Jump to `/admin/shop/order-exceptions` and `/admin/shop/refund-payouts` respectively, sidebar expands the shop group | D0 | |
| SA-TD-05 | Counts change with handling | [Browser] Refresh after handling one exception order / marking one payout as paid | Each count decreases by 1 | — | |
| SA-TD-06 | Permission filtering | [Browser] M (without `refund.payout` but with `refund.view`), N | M sees both items (refund payout is viewable); N does not render "Shop to-dos", and the group badge excludes these two items | — | |

### 15. Fulfillment Drawer · Biteship Ordering and Failure Handling (Story 6.1 · AB-38)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-FL-01 | List exception filter | [Browser] Filter each option of the Order Fulfillment "Exception flag" dropdown | Options: Has exception flag / Out of stock / Waybill failed / Pickup failed / Returned / Lost or damaged / Logistics stalled / Pending claim; badge beside the order number; summary "Has exception flag" = DB count; filter kept when paging | H1 | |
| SA-FL-02 | Biteship ordering entry | [Browser] Open a to-ship order without a waybill | Sticky-bottom grey-outline "Biteship order": courier chosen by the user, shipping fee paid by the user (= `shipping_fee + shipping_discount`, floor 0), whether insured; red-outline "Mark out of stock" kept; order section has courier and insurance rows | H2 | |
| SA-FL-03 | Order placed successfully | [Browser] Click Biteship order → confirm-modal → confirm | Package table backfills carrier, waybill number, actual shipping fee (carrier cost column), status awaiting pickup; **order is still to-ship**; sticky bottom changes to "Mark as shipped"; [DB] `shipments.insurance_cost` written (when the stub has insurance), audit `SHOP_WAYBILL_CREATED`; ⚠️ H3 success banner (with pickup time window) is not implemented, toast only | H3 | |
| SA-FL-04 | Mark as shipped | [Browser] Click "Mark as shipped" | Order is shipped; order section shows source "Marked by ops", time, operator; [DB] `shipped_by_admin_id`; [API] App shows awaiting receipt | H4 | |
| SA-FL-05 | Pickup auto-ships | [Browser] On another order, place a waybill and do not mark shipped; [API] stub-status `picked` | Order auto-shipped, source "Courier pickup", no operator | H4 | |
| SA-FL-06 | Shipped sticky bar | [Browser] Drawer of a shipped order | Sticky bottom: "Mark lost or damaged", "Mark full order delivered"; four-node timeline kept | H4 | |
| SA-FL-07 | Waybill failed, three options | [Browser] The waybill-failed order created in SA-PRE-08 | `.banner.err` shows the original Biteship reason; red-outline three options: Resubmit waybill / Enter waybill manually / Cancel after contacting user | H5 | |
| SA-FL-08 | Resubmit waybill | [Browser] Click resubmit → confirm | Ordering with the same courier succeeds, `waybill_failed_at` cleared, status unchanged; audit | H5 | |
| SA-FL-09 | Unknown result must be verified | [DB] Set reason to `UNKNOWN_RESULT:timeout`; [Browser] resubmit | Drawer shows a verification warning; the confirm-modal's yellow warning requires verifying on Biteship first, and submits only after confirming | H5 | |
| SA-FL-10 | Cancel after contacting user | [Browser] Click "Cancel after contacting user" | Jumps to `/admin/shop/order-exceptions?open=<token>`, the right side opens that order directly | H5 → D1 | |
| SA-FL-11 | Pickup failed, three options | [API] stub-status `courier_not_found`; [Browser] open | "Pickup failed" three options; the order also enters the exception order queue; [API] no exception notice on the App side | H6 | |
| SA-FL-12 | Pickup-failed resubmit cancels old order first | [Browser] Resubmit waybill | [DB] old waybill cancelled, new waybill created, only one active waybill; status unchanged | H6 | |
| SA-FL-13 | Shipped pickup failed (O-18) | [Browser] Push `courier_not_found` on an order already marked shipped, then resubmit | Allowed, order remains shipped | H6 | |
| SA-FL-14 | Waybill-failed resubmit bypass | [API] Replay the waybill-failed `/waybill/retry` on a pickup-failed order | Rejected (422, ⚠️ or 409), must go through the pickup-failed flow | — | |
| SA-FL-15 | Enter waybill manually | [Browser] On a waybill-failed order "Enter waybill manually" → carrier, number, carrier cost → "Save and ship" | Saving means shipped (O-20), failure flag cleared; audit records carrier cost | H7 | |
| SA-FL-16 | Manual orders can only be delivered manually | [Browser] View the SA-FL-15 drawer; [API] App order detail | No logistics status, only "Mark delivered"; App shows only the courier name and number, check on the official site | H13 | |
| SA-FL-17 | Cannot ship before continuing after contact (stock_unlocked) | See SA-LP-10 | — | — | |
| SA-FL-18 | Status already changed | [Browser] Biteship order on the same order in two tabs | Second time 422 (active waybill already exists), no two waybills | — | |
| SA-FL-19 | Insurance display | [Browser] View the order section of an insured and an uninsured order | "Insured: Yes · Insurance fee Rp x / No", "Shipping fee paid by user", "Actual shipping fee" | H2 | |
| SA-FL-20 | Audit | [Browser] Operation audit | One row each for order placed, resubmit, cancel waybill, manual waybill (with carrier cost), mark shipped | — | |
| SA-FL-21 | Permissions | [Browser] M can operate; an account with only `shop.order_view` (custom) opens the drawer | The latter has no sticky-bottom actions, POST 403 | — | |

### 16. Logistics Exception Handling (Story 6.2 · AB-38)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-LG-01 | Return judgement | [API] Shipped order stub-status `returned`; [Browser] drawer | Returned flag + cause radio (courier fault / user fault) + note; [API] App "Delivery exception, please contact platform support" | H8 | |
| SA-LG-02 | Courier fault → pending claim | [Browser] Judge as courier fault | Order gets "Pending claim"; [Log] OPS pending-claim reminder (restricted); [DB] `return_cause=COURIER_FAULT` | H9 | |
| SA-LG-03 | Create new waybill to reship | [Browser] "Create new waybill to reship" → confirm | New waybill `is_reship=true`, original waybill kept in the package table; order shipped, returned flag cleared; after reshipping succeeds `return_cause` is cleared | H9 | |
| SA-LG-04 | Judgement cannot be changed | [Browser] Submit another cause on an already-judged order | 422 (⚠️ implemented as 409); resubmitting the same cause is idempotent | — | |
| SA-LG-05 | User fault · line-by-line inbound | [Browser] On another returned order judged as user fault → drawer "Confirm return inbound", enter received quantities line by line, receive part first, then the rest | Can be done in batches; cumulative over expected → 422; once all received, a completion banner is shown and `return_inbound_at` is written; stock increases by the received quantity each time in the same transaction; the inbound form has no UI mockup (self-designed) | — (no mockup) | |
| SA-LG-06 | Stock added only once | [DB] Record stock before inbound S0; after inbound of n units S1; after full-order cancel (SA-EX-13) S2 | S1 = S0 + n; S2 = S1 | — | |
| SA-LG-07 | Confirm receipt refused after inbound (O-16) | [API] After inbound starts, U1 `POST /api/v1/me/shop-orders/{t}/confirm-receipt` | 409 `return-inbound-confirmed`; App prompts to contact support | — | |
| SA-LG-08 | Other exceptions allow confirm receipt | [API] Confirm receipt on an order with logistics stalled / pending claim (not inbound) | Success, completed, exception flag cleared; [Log] OPS reminder (the copy for orders already marked pending claim says "Please withdraw the claim", restricted) | — | |
| SA-LG-09 | Mark lost or damaged | [Browser] On one shipped, one delivered, and one completed order "Mark lost or damaged", choose lost / damaged + note → confirm | All three orders get "Lost or damaged" and "Pending claim" and enter the exception order queue; order status unchanged; [Log] OPS pending-claim reminder | H10 | |
| SA-LG-10 | Cannot mark lost during pickup failure | [Browser] Look for "Mark lost or damaged" on a pickup-failed order | Not shown; replay → 422 | — | |
| SA-LG-11 | Claim close-out | [Browser] On a pending-claim order "Close claim": paid + received 50.000; on another order "Rejected" | Pending claim cleared; [DB] `claim_result/claim_amount/claim_closed_at/claim_closed_by_admin_id`; rejected amount recorded as 0; paid amount ≤0 → 422 | H11 | |
| SA-LG-12 | Can close out in any status | [Browser] Close out a cancelled pending-claim order | Success | H11 | |
| SA-LG-13 | Duplicate close-out | [Browser] Close out simultaneously in two tabs | Second time 422, does not overwrite the first result | — | |
| SA-LG-14 | Logistics stalled | [DB] `UPDATE shop_orders SET logistics_exception='STALLED', logistics_exception_at=now() WHERE …` (or change the waybill's latest status time to 4 days ago and wait for the job to run; the job sends an OPS summary reminder) | Drawer shows logistics stalled; "Clear" button | H12 | |
| SA-LG-15 | Clear stalled | [Browser] Click clear | Clear succeeds, audit; replaying clear on a non-STALLED exception → 422 | H12 | |
| SA-LG-16 | Auto-delivery skips exceptions | [DB] Change `shipped_at` of an order with an exception flag to 15 days ago and wait for the auto-delivery job | Not auto-delivered; timing resumes as normal after the flag is cleared | — | |
| SA-LG-17 | Audit | [Browser] Operation audit | One row each for judgement (with cause and note), reship, inbound, mark lost or damaged, close claim, clear stalled | — | |
| SA-LG-18 | O-25 short-receipt relabel | [Browser] Try to mark lost or damaged on a user-fault returned order that is partially inbound | Currently rejected with 422 / 409 (🔴 O-25 not implemented, rule pending decision, verify as currently behaves) | — | |

### 17. Order Master List and Refund Growth-Points Section (Story 7.1)

| ID | Case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-OL-01 | List | [Browser] S1 opens "Orders" | 7 columns same as the fulfillment page; includes cancelled and refunding; page header description rewritten | F1 | |
| SA-OL-02 | Five summary tiles | [DB] Count by status | Awaiting payment / To ship / To receive (shipped + delivered) / Refunding / Paid after cancellation pending handling match the DB; summary and list contain no phone numbers or addresses | F1 | |
| SA-OL-03 | Exception filter includes paid after cancellation | [Browser] Choose "Paid after cancellation" in the exception flag | Lists orders with non-empty `late_paid_amount` (including handled); badge beside the order number | F1 | |
| SA-OL-04 | Drawer sticky bar determined by page | [Browser] Open the same to-ship order from "Orders" and from "Order Fulfillment" | Orders page: no fulfillment buttons, only the "Handle in Order Fulfillment" entry, no "Mark this package delivered"; fulfillment page behavior unchanged | F1 | |
| SA-OL-05 | Refund and growth-points section | [Browser] Open the orders of SA-EX-06 / SA-PO-09 | Each refund: source, coin / cash two portions, destination, status; growth points: credited, deducted back, pending deduction (labeled "Expected", = pro-rata estimate for the unfinished cash portion); consistent with the [DB] growth points ledger | F7 | |
| SA-OL-06 | Legacy order degradation | [Browser] Open an old order from the 1.4.1 era | Missing values shown as "—", no error | F7 | |
| SA-OL-07 | Direct-access 404 | [Browser] Visit `/admin/shop/order-list/<random token>` directly | 404 | — | |
| SA-OL-08 | Permissions | [Browser] M can view; N 403 | As described | — | |

### 18. Late-payment handling after cancellation (Story 7.2 · AB-28)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-LP-01 | Marker and reminder | [Browser] On the "Orders" page, open the order from SA-PRE-09 | Top `.banner.err`: payment arrived after cancellation · amount received · time (order was cancelled by timeout at xx); sticky bottom "Re-order" and "Refund received payment"; [Log] CS reminder, dedupe key `late-paid:{token}` (restricted) | F2 | |
| SA-LP-02 | Checklist | [Browser] Click "Re-order" | Sticky bottom switches to a 5-item checklist: coupon, PawCoin (needs x · current balance y), stock (n lines in stock) are computed by the system; "User agreed" and "Address valid" are ticked manually; cannot submit unless all are satisfied | F3 | |
| SA-LP-03 | Server re-checks manual items | [API] Replay the submit without the manually ticked items | 422, no reservation made | — | |
| SA-LP-04 | Expired coupon still OK | [DB] Change the `valid_until` of the coupon used by the original order to yesterday | Coupon item is still satisfied, re-order allowed | F3 | |
| SA-LP-05 | Used-up coupon not OK | [API] U1 first uses the original coupon on another paid order | Coupon item not satisfied, cannot submit; refund is the only option | F3 | |
| SA-LP-06 | Insufficient PawCoin | [DB] Change U1's balance to below the amount to deduct | That item is not satisfied; [API] bypass submit → 422 / 409 | F3 | |
| SA-LP-07 | Re-order succeeds | [Browser] Submit once all are satisfied | Original order shows `.banner.ok` with the new order number (clickable), operator, time; toast; new order is awaiting shipment, amount locked to the original order; coupon and PawCoin deducted normally; [DB] new order has `reorder_of_order_id`, `checkout_source='REORDER'`; [API] App original order shows "Already re-ordered for you" | F5 | |
| SA-LP-08 | Partial out of stock | [DB] Create another late-payment order and set the sellable quantity of one line's SKU to 0; [Browser] Re-order | Item 4 warns of partial out-of-stock but can be submitted; that line of the new order has `stock_unlocked=true`, stock is not deducted, and the new order is automatically flagged out of stock (note lists the out-of-stock lines) | F4 | |
| SA-LP-09 | All out of stock | [DB] Set sellable quantity of all lines to 0 | Item 4 cannot be ticked, refund is the only option; bypass submit → 422 / 409 | F4 | |
| SA-LP-10 | Shipping forbidden with unhandled out-of-stock lines | [Browser] In the fulfilment drawer, place a Biteship order / manually ship the new order from SA-LP-08 | Rejected (422 / 409, prompts to handle the out-of-stock lines first) | — | |
| SA-LP-11 | Continue locking stock after contacting user | [DB] Restore stock of that SKU; [Browser] On the exception order, click "Continue after contacting user" for the new order | lock + commit succeeds, flag cleared, can ship; when stock is insufficient → rejected with a prompt to use partial cancellation, whole order rolled back | — | |
| SA-LP-12 | stock_unlocked is not restocked | [Browser] Partially cancel the out-of-stock line of the new order | That line is refunded, stock is **not** restocked | — | |
| SA-LP-13 | Original courier unavailable, switch to cheapest | [DB] Change the new order's `courier_code` to a courier that does not exist in the stub rates; [Browser] Place Biteship order | Waybill uses the cheapest courier, drawer shows that it was switched; order snapshot unchanged; the difference is borne by the platform (restricted: stub rate table is fixed, simulated by editing the DB) | — | |
| SA-LP-14 | Refund received payment | [Browser] On another late-payment order click "Refund received payment" → confirm-modal (restates the total received) → confirm | Enters refund payout "Awaiting user choice"; no compensation; [API] App original order shows "Choose refund method" | F6 | |
| SA-LP-15 | Duplicate payment order | [DB] For an order already paid and not cancelled, simulate another incoming payment (simulate-paid twice on the same intent, or edit `late_paid_amount` in the DB) | Drawer only shows "Refund received payment", no re-order (O-19) | F2 | |
| SA-LP-16 | Same payment handled only once | [Browser] In two tabs, click "Re-order" and "Refund received payment" at the same time | Only one succeeds, the other gets 422; no two new orders / two refund orders | — | |
| SA-LP-17 | No restore of original order | [Browser] Look for a restore option in the drawer | None | F2 | |
| SA-LP-18 | Late payment of a deactivated user | [DB] U2 has a late-payment order, U2 deactivates the account; [Browser] Open it | No checklist; refunding the received payment works, the refund order goes to "Deactivated users · offline handling"; re-order → rejected | — | |
| SA-LP-19 | Permissions | [Browser] M (`shop.order_fulfill`) can operate; an account with only `shop.order_view` does not see the two buttons, replay → 403 | As described | — | |
| SA-LP-20 | Audit | [Browser] Operation audit | Re-order: amount received, new order number, checklist results, number of out-of-stock lines; refund: amount received, destination | — | |

### 19. Reconciliation · Discount and logistics costs (Story 8.1 · AB-38)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-RC-01 | New card position | [Browser] S1 reconciliation page, select today's test date for the time range | The original four cards and the tax notice are unchanged; a new full-width "Discount and logistics costs" card is added, noting "Order metrics by payment time" | I1 | |
| SA-RC-02 | Verify all 12 rows | [DB] Write SQL per the AD-15 definition: `SUM(discount_coupon_amount)`, `SUM(abs(shipping_discount))` split by `shipping_discount_source`, `SUM(shipping_fee+shipping_discount)`, waybill `carrier_cost` (non-resend / resend), `insurance_fee` / `insurance_cost`, compensation, number of pending claims / `claim_amount`, four refund payout states, three late-payment sub-items | Every row matches the SQL | I1 | |
| SA-RC-03 | Net shipping cost | [DB] Compute actual + resend − shipping paid by users | Equals the page value; free-shipping-over-threshold / free-shipping coupon discounts are not double-counted | I1 | |
| SA-RC-04 | Late payments not double-counted | [Browser] Compare the cash income on the original four cards before and after SA-LP-07 | A payment already converted into a new order is counted only once, under the new order | I1 | |
| SA-RC-05 | Empty state | [Browser] Select a historical range with no data | Only the new card shows "No data for the selected period.", the original four cards behave as usual | I2 | |
| SA-RC-06 | WIB time boundary | [DB] Create an order with `paid_at` = 23:30 WIB of the day (16:30 UTC) | Counted in that day, does not fall into the next day | — | |
| SA-RC-07 | Permissions | [Browser] M (without `shop.finance_view`) visits directly | 403 | — | |

### 20. Permission matrix summary (spot-check direct visits and POST 403)

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-PM-01 | Super admin has full access | [Browser] S1 walks through all new pages | All visible and operable | — | |
| SA-PM-02 | OPS_MANAGER matrix | [Browser] M goes page by page | Can operate: orders / fulfilment / exception orders / banner / products and reviews / shipping / review display toggle / late payment after cancellation; read-only: refund payouts, share rewards; invisible, 403: coupons, reconciliation | — | |
| SA-PM-03 | No permission | [API] N session replays: coupon save, banner reorder, exception cancel, mark as paid out, Biteship order, claim close, balance entry, review hide | All 403, no data change, no business audit | — | |
| SA-PM-04 | Menu consistent with gating | [Browser] M, CV, F each visit every sidebar-visible item directly | Everything visible in the sidebar opens; everything not visible gives 403 | — | |

### 21. Audit trail summary

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-AU-01 | All action codes present | [DB] `SELECT action_type,count(*) FROM admin_audit_logs WHERE created_at>'<test start>' GROUP BY 1;` | Contains at least: `SHOP_COUPON_CREATED/UPDATED/QUOTA_INCREASED/STOPPED/CODES_CREATED/CODES_EXPORTED`, `SHOP_BANNER_REORDERED`, `SHOP_ORDER_EXCEPTION_HANDLED`, `SHOP_ORDER_REFUND_PAYOUT_MARKED`, `SHOP_REFUND_DUTY_VIOLATION_BLOCKED`, `SHOP_ORDER_REFUND_OFFLINE_CLOSED`, `SHOP_ORDER_LATE_PAYMENT_REORDERED`, `SHOP_ORDER_RETURN_INBOUND_CONFIRMED`, `SHOP_WAYBILL_*`, `SHOP_ORDER_MARKED_SHIPPED`, `SHOP_LOGISTICS_*` (including `CLAIM_CLOSED`), `SHOP_BITESHIP_BALANCE_RECORDED`, `SHOP_SHIPPING_SETTINGS_UPDATED`, `SHOP_SKU_UPSERTED`, `SHOP_REVIEW_HIDDEN/UNHIDDEN/DISPLAY_TOGGLED` | — | |
| SA-AU-02 | Sensitive information | [DB] `SELECT summary FROM admin_audit_logs WHERE action_type LIKE 'SHOP_%' AND created_at>'<test start>';` scan manually | No plaintext redeem codes, no full payout account (last 4 digits only), no phone numbers, no addresses, no signed URLs | — | |
| SA-AU-03 | Before and after | [Browser] On the operation audit page, look at coupon edits, banner scheduling, shipping settings | Summary is `field: old→new`, listing only changed fields; money-related writes include full amounts | — | |
| SA-AU-04 | System actions not recorded | [DB] Biteship callback changing status, auto-delivered, automatic growth points reversal | No operation audit produced (recorded in their own ledgers / status records) | — | |
| SA-AU-05 | Hash chain intact | [Browser] Chain verification on the operation audit page (if any) or [DB] spot-check that `prev_hash` is continuous | Chain is unbroken | — | |

### 22. Three languages

| ID | Test case | Steps | Expected | Screen | Result |
|---|---|---|---|---|---|
| SA-I18N-01 | No missing keys | [Browser] Switch language to EN and ID at the top right, go through every page, drawer, overlay, and toast of this line | No raw keys such as `admin.v142.*` exposed; in Indonesian "payout" is consistently Pencairan; UI wording: the three terms Re-order / Resubmit waybill / Create waybill for resend are distinguished, as are "Delivery exception", "Convert to PawCoin compensation", "Awaiting payment" | All | |
| SA-I18N-02 | Review self-drafted copy | [Browser] Focus on self-drafted copy that the story marks "pending design confirmation" (deactivated-user tab, per-line inbound form, duplicate payment banner, all 422 messages) | Record a list for design confirmation, do not judge as failure | — | |

### 23. UI fidelity (standard ≥ 95, scored screen by screen)

Method: compare against each screen of the design mockup `ui-admin-1-4-2-shop-integrated-v1.html`, reproduce the same state on ops-stag and take screenshots (Chinese UI, 1440 wide), score screen by screen (layout, copy, components, state colors, number formats); list the differences for any score < 95. Parts of the design mockup marked "omitted on this screen" or "two-column layout only to fit the frame" are not penalized.

| Batch | Page | Screens | Count |
|---|---|---|---|
| Batch 1 | Coupons page | A1 list, A2 empty state, A3 create fixed-amount, A4 create free-shipping, A5 redeem codes, A6 save failed, A7 misconfiguration confirm, A8 started, only total editable, A9 stop with two options, A10 coupon detail | 10 |
| Batch 2 | User detail · Coupons tab | A11 list, A12 empty state, A13 redemption locked | 3 |
| Batch 3 | Top Banner | B1 list, B2 empty state, B3 edit drawer (claim coupon), B4 legacy category must be re-selected, B5 invalid target, B6 wrong ratio | 6 |
| Batch 4 | Operations config | C1 share reward warning, C2 save confirm estimate, J5 review display toggle, J6 turn-on confirm | 4 |
| Batch 5 | To-do center + exception orders | D0 shop to-dos, D1 list with source label, D2 empty state, D3 platform reason, D4 user reason, D5 lost/damaged, D6 partial cancel split, D7 last line converts to whole order, D8 handled successfully | 9 |
| Batch 6 | Refund payouts | E1 list, E2 empty state, E3 mark as paid out, E4 payout failed, E5 separation of duties | 5 |
| Batch 7 | Orders (overview + late payment after cancellation) | F1 list filters, F2 late payment after cancellation, F3 checklist, F4 partial out of stock, F5 already re-ordered, F6 refund confirm, F7 refund and growth points | 7 |
| Batch 8 | Service area and shipping | G1 configured, G2 shipping origin address not configured, G3 balance below threshold, G4 max discount required | 4 |
| Batch 9 | Product management / edit / reviews | G5 list missing weight and volume, G6 SKU required, J1 reviews tab, J2 empty state, J3 hide with reason, J4 restore confirm | 6 |
| Batch 10 | Order fulfilment drawer | H1 list exception filter, H2 Biteship order, H3 waybill created, H4 shipped, H5 waybill failed, H6 pickup failed, H7 manual waybill entry, H8 return judgment, H9 courier-caused resend, H10 mark lost/damaged, H11 claim close, H12 logistics stalled, H13 manual waybill | 13 |
| Batch 11 | Reconciliation | I1 discount and logistics costs, I2 section empty state | 2 |
| **Total** | | | **69** |

**No mockup, not scored but must match the style of the same page**: per-line return inbound confirmation form (O-15), the refund payout "Deactivated users · offline handling" tab and the offline close form / overlay (O-17).

**Known deviations already recorded by the story (deduct points directly when scoring and list them separately)**: A6 red field outline not done; G1 shipping address card lacks the "read-only + edit" two states; H3 success banner (pickup window) not done; H5 reason shows the raw text (e.g. `REJECTED:xxx`); parcel status copy is still "Waybill created (awaiting pickup)"; H11 "Already re-shipped" banner and H12 "View in Biteship" button not done; F5 banner lacks the "pay QRIS with received payment" section; D6 split details appear only in the overlay, not below the in-row form; D4 inbound hint copy still points to the "return review" entry (inconsistent with O-15); coupon name is split into Indonesian / English two columns (mockup has one column).

### 24. Items that cannot be run on stag / need seed data / restricted

- The three Lark groups (CS / OPS / TECH) are not configured: all reminders can only be checked via WARN logs and `ops_alert_log`; real delivery and in-group layout are left to L2.
- Biteship is a stub: waybill failure (`waybill_failed_at`) can only be created by editing the DB, the stub has no "next order fails" HTTP entry; pickup failure / return / delivery are advanced via stag's `stub-status`.
- The Biteship sandbox insurance fee field (`courier.insurance.fee`) and whether `price` includes insurance cannot be verified under the stub; the estimated balance and the reconciliation "actual insurance cost" are verified for arithmetic only.
- Original courier unavailable, switch to the cheapest: stub rates are fixed, can only be simulated by changing the order's courier code.
- QRIS gateway unavailable: all incoming payments (including late payments and duplicate payments) are created via the payment intent "simulate paid".
- A late payment requires changing `expires_at` so the order times out and closes, then simulating the payment; a duplicate payment requires creating another incoming payment on an already paid order (may need DB edits).
- Not chosen for over 7 days, logistics stalled, auto-delivered skipping exceptions: need to change `created_at` / exception flag / `shipped_at` and wait for scheduled jobs, or set the flags directly in the DB.
- Payout screenshot upload depends on the stag OSS private bucket; if not configured, upload and signed URL cases are ⏭.
- Redemption lock requires U1 to enter wrong redeem codes consecutively (read the Redis TTL).
- Deactivated-user cases (U2) require changing `users.deleted_at` or going through real deactivation, which cascades to invalidate U2's assets; use a disposable test account.
- Review list pagination needs a product with ≥21 published reviews; insert `shop_reviews` if there are not enough.
- The two empty states, shipping address not configured and Biteship balance not entered, require clearing the existing stag configuration; restore it when done.
- The no-permission / read-only / finance roles need loginable Lark accounts; super admin emergency login sends an alert to all super admins, avoid using it.
- Known unimplemented, can only be verified as-is: O-25 (user-caused short receipt re-marked as lost/damaged); in O-26, "when closing an exception order of a deactivated user with no refund order, generate an offline refund order".
- Error codes: the story records some state conflicts returning 409, while this document expects 422; if 409 is observed, record ⚠️ and summarize for a decision.

### Seed data list

| # | Time | Object (order number / coupon / user / config) | How created (API / DB statement) | Used for | Restored? |
|---|---|---|---|---|---|
| 1 | | | | | |

---

## E. Growth points manual reversal (exp-reverse)

| ID | Test case | Steps | Expected | Screen | Execution | Result |
|---|---|---|---|---|---|---|
| ER-01 | New suspended refund writes marker | Create a consultation QRIS paid order with growth points credited → trigger a suspended refund (DB edit / API) | `consult_orders.suspension_refund_at` has a value; PawCoin payments, regular refunds, and legacy orders do not write it | — | API + DB | |
| ER-02 | Drawer growth points section · reversible | Admin consultation orders → open the ER-01 order drawer | Shows credited growth points, "Reverse" button is available | No mockup | Admin console | |
| ER-03 | Reverse overlay | Click "Reverse" | Overlay shows credited value, previously reversed, this reversal, description; three-language copy taken from PRD §5 | No mockup | Admin console | |
| ER-04 | Confirm reversal | Confirm in the overlay | Drawer and list row refresh to "Reversed"; `growth_ledger` has a REFUND reverse entry; level recalculated; user **receives no notification**; one audit log entry | — | Admin console + DB | |
| ER-05 | Repeat reversal | Call the reversal API again on the same order | 422 "Already reversed", no extra ledger entry | — | API | |
| ER-06 | No credit / legacy / regular refund in progress | Open the drawers of the three kinds of orders respectively | Shows a description per status, no reverse button | No mockup | Admin console | |
| ER-07 | Permissions | Open the drawer and call the API directly with an account without `order.reverse_exp` | Button not rendered; API 403. OPS_MANAGER has it, super admin has it | — | Admin console + API | |
| ER-08 | Pending reversal filter | Select "Growth points pending reversal" in the list | Only reversible orders listed, summary follows; after reversal the order disappears from the results; export still full | No mockup | Admin console | |
| ER-09 | Order status changed | After opening the overlay, reverse in another window first, then confirm in the original overlay | 422 prompting that the status has changed, no double reversal | — | Admin console | |

## F. Five small requirements (1108)

### F1 PostHog money removal

| ID | Test case | Steps | Expected | Screen | Execution | Result |
|---|---|---|---|---|---|---|
| PH-01 | Backend egress whitelist | On stag trigger one shop payment event / KTP unlock failure event, check stag logs and L0 tests | Properties sent to PostHog do not contain `order_amount` / `price` / `price_idr`; the event itself is still sent | — | Log check + L0 | |
| PH-02 | App egress | L0 case `posthog_money_keys_test` | PostHog properties drop price / price_idr / total_amount / coin_used / shipping_fee / af_revenue / af_price; the AppsFlyer path still carries them | — | L0 | |
| PH-03 | Stag build does not report | PostHog is off by default in the stag build (stag-only switch) | Emulator operations produce no PostHog reports (does not conflict with this requirement) | — | Code check | |

### F2 Notify on replies to your reply

| ID | Test case | Steps | Expected | Screen | Execution | Result |
|---|---|---|---|---|---|---|
| RN-01 | Main flow | A posts, B writes a top-level comment, C replies to B, D replies to C (C = emulator test account, the others call the API with signed JWTs), wait for moderation to pass | C's notification center shows "replied to your comment"; A and B get one each; D gets none | Notification center | API + Emulator | ✅ Post 542: comment 264 (B=89) → 265 (C=84) → 266 (D=93); notifications 115/89/84 one each, 93 none; emulator notification center "QA-Claude-05 replied to your comment" |
| RN-02 | Tap notification to locate | Emulator: tap the notification from RN-01 | Opens post detail and locates D's reply | — | Emulator | ⚠️ Jumps to post detail and scrolls to the top of the comment section (existing mechanism only reaches the comment section, does not locate the reply one by one; the reply notification received by B behaves the same) |
| RN-03 | Replying to self | C replies to their own second-level comment | C receives nothing | — | API + DB | ✅ 267 (84 replies to their own 265) 84 has no notification |
| RN-04 | C is the post owner / top-level author | When C=A or C=B, D replies to C | That person receives only one | — | API + DB | ✅ 269 (93 replies to 89's own 268) 89 has only one |
| RN-05 | @ dedupe | D replies to C and also @ C | C receives only the "replied to you" one, no "mentioned you" | — | API + DB | ✅ 271 (94 replies to 265 and @84) 84 has only one REPLY, no CONTENT_MENTIONED |
| RN-06 | Block suppression | After C blocks D, D replies to C | C receives nothing | — | API + DB | ⏭ Covered by integration test v142_replyTargetWhoHidTheReplierGetsNothing; observation: on stag 93 blocked 84, and when 93 replied to 84, 84 still got a notification — comment notifications only check "recipient blocked the actor", while @ notifications are bidirectional, the two rules are inconsistent (legacy rule) |
| RN-07 | Not sent if moderation fails | D's reply goes to manual review (not passed) | C receives nothing; receives only after it passes | — | API + DB | ⏭ Covered by integration test (event is sent only after passing moderation) |
| RN-08 | Push toggle | C turns off "Interactions" push | Only enters the notification center, no push | — | Emulator + log check | |

### F3 Admin console pagination

| ID | Test case | Steps | Expected | Screen | Execution | Result |
|---|---|---|---|---|---|---|
| PG-01 | User list | ops-stag users page | Bottom: total N · page numbers in groups of 10 · previous/next page · page x / y · jump to | Reference image | Admin console | ✅ ops-stag users page "116 total · 1–6 · Next · Page 1 / 6 · Jump to" |
| PG-02 | Switch group | Click » / « | Jumps to the first page of the next group / last page of the previous group | — | Admin console | ✅ Comments page » → page 11 (group 11–14); « → page 10 (group 1–10) |
| PG-03 | Jump to page | Enter a number beyond the total pages | Clamped to the last page; entering 0 clamps to page 1 | — | Admin console | ✅ Jump entering 99 → last page, 0 → page 1, 3 → page 3 |
| PG-04 | Filters kept | Paginate / jump with a status filter | Filter conditions are not lost | — | Admin console | ✅ With the status=active filter, paging / jumping links all carry status |
| PG-05 | htmx partial refresh | Paginate on the content / comments / shop orders pages | Partial refresh, address bar in sync; after reload stays on that page | — | Admin console | ✅ Clicking a page number on the comments page refreshes partially, address bar syncs page |
| PG-06 | Unknown total basis | On the content page switch to "by like time" or filter by species | Shows only page x + previous/next, no total pages | — | Admin console | ✅ Content page default basis "578 total / 29 pages"; the by-like-time basis has only 1 page so the pagination bar is not shown |
| PG-07 | Other pages | Gifts / payments / audit log / order overview | Same as PG-01 | — | Admin console | ✅ Gifts 49 / payments 162 / audit 1,000 (exactly 1000 in the DB) / shop orders two pages 33 |
### F4 KTP anti-bypass

| ID | Test case | Steps | Expected | Screen | Execution | Result |
|---|---|---|---|---|---|---|
| KT-01 | New watermark | Detail page of a locked card | Dark purple diagonal tiling covering avatar / name / ID number, text still readable | — | Emulator | |
| KT-02 | Low-res preview | Zoom into a system screenshot of the detail page | Clearly blurry, information still discernible | — | Emulator | |
| KT-03 | Card creation preview | On the create-card page change info / swap photo | Low-res + watermark, preview refreshes after changes | — | Emulator | |
| KT-04 | Free share | Tap Share | With watermark, 663 wide (passport about 663 wide); share reward as usual | — | Emulator + fetch cached image | |
| KT-05 | Paid HD | On stag set hd_unlocked on the card (or unlock with PawCoin), then download the HD image | 1988 wide (passport 1990), no watermark, sharp original | — | Emulator + fetch image | |
| KT-06 | Unlocked preview | Detail page after KT-05 | Sharp (not low-res), watermark as currently | — | Emulator | |
| KT-07 | 8 card types | KTP / passport / residence permit / SIM / four-color student cards | All work normally | — | Emulator | |

### F5 Profile avatar enlarged

| ID | Test case | Steps | Expected | Screen | Execution | Result |
|---|---|---|---|---|---|---|
| AV-01 | Tap avatar | Tap the avatar on someone else's profile | Full-screen original image, can be closed | Reference image | Emulator | |
| AV-02 | No avatar | Tap the placeholder on a profile of a user with no avatar | No response | — | Emulator | ✅ Tapping the placeholder of a user with no avatar (115) does nothing |
## Seed data list (stag, 2026-11-08)

- Comments 264–271 under post 542 (RN group); 94 follows 84 (RN-05, unfollowed after testing).


## Execution record (2026-11-08, stag 98adc0d5f)

| Item | Result |
|---|---|
| RN-01 | ✅ Post 542: comment 264 (B=89) → 265 (C=84) → 266 (D=93); notifications 115/89/84 one each, 93 none; visible in the emulator notification center |
| RN-02 | ⚠️ Jumps to post detail and scrolls to the comment section (existing mechanism does not locate the reply one by one) |
| RN-03 | ✅ 267 self-reply has no notification |
| RN-04 | ✅ 269 top-level author receives only one |
| RN-05 | ✅ 271 @84 + reply to 84: only one REPLY |
| ST-T-topic write association | ✅ Posting #QaTopik142 #qa_kucing → topics created=true; stored in lowercase, body keeps original case; C5 post topic highlight 97 |
| ST-T-topic page | ✅ Tap #QaTopik142 → topic page Terbaru/Populer, both posts included; C8 UI fidelity 96 |
| ST-T-official topic | ✅ Set QaTopik142=OFFICIAL + description via DB → topic page "Resmi" badge + description; C7 UI fidelity 97 (pinned post pending admin console) |
| ST-T-topic row | ✅ Discover page row of topics below the category tags, official ones first with Resmi; C1 UI fidelity 96; observation: the pet recommendation strip did not appear this time (1.4.0 feature, suspected frequency capping) |
| ST-T-load failure | ✅ Airplane mode into topic page → cloud-off icon + Gagal memuat + Coba lagi, retry recovers; C11 UI fidelity 98 |
| ST-F-follow list | ✅ Pengguna 4 / Hewan 1 pills; pet row "Anjing · milik @QA-Claude-01"; A5 97, A6 96 |
| ST-F-list unfollow | ✅ Tap Mengikuti → row stays, button light purple Ikuti, count unchanged for now; A7 97 |
| ST-F-list empty state | ✅ Re-enter Hewan 0 + paw print "Kamu belum mengikuti hewan apa pun"; A8 97 |
| ST-F-push categories | ✅ New "Hewan yang kamu ikuti" + "Pengikut baru" description extended; A13 98 |
| ST-F-A9 | ⏳ Emulator overloaded (full test run in parallel), postponed |
| Automation | ✅ App full run 4932 passed + analyze 0; ⚠️ backend full run 9056 tests 21 failures (11 classes, same count as the dev_1.4.2 baseline), under triage |
| SA-coupon-empty state | ⚠️ A2 UI fidelity 91: filter bar label on the left, button "Filter" (mockup: label on top, button "Query"); empty state still shows 4 zero-value summary cards, the empty hint has no outlined card |
| SA-coupon-create drawer | ⚠️ A3 UI fidelity 90: name split into Indonesian/English two columns (deviation already recorded by the story); validity period is a dropdown (mockup: radio); claim period in two stacked boxes (mockup: same row); save button is not full-width |
| SA-coupon-validation | ✅ Missing Indonesian name → 422 at the top of the drawer "Indonesian coupon name cannot be empty"; A6 92 (field red outline not done, already recorded by the story) |
| SA-coupon-create | ✅ Fixed-amount claim coupon / redeem-code coupon QA142KODE / new-user free-shipping coupon created, list fields correct |
| SA-coupon-misconfiguration | ✅ Threshold 0 → dialog "Save this coupon? Threshold is 0" → Save anyway; face value < threshold does not prompt (O-14) |
| SA-coupon-stop | ✅ Two-option dialog (keep usable / void) → void and stop, list shows "Stopped · Details", DB STOPPED + void_on_stop; A9 97 |
| SA-admin login | ✅ Emergency login only writes WARN logs and audit, no push; temporary accounts 12/13 enabled (disabled and password cleared after testing) |
| SA-shipping-not configured | ⚠️ G2 UI fidelity 85: mockup has two columns (left form / right area and return address) + required star with label on the left; actual is single column, label on top |
| SA-shipping-origin address | ✅ Fill in origin address → "Shipping origin address saved", unconfigured warning disappears, DB origin_postal_code=10110 |
| SA-shipping-threshold | ✅ Threshold>0 with empty max discount → "Required when threshold is greater than 0" (G4); after filling 50k the confirm dialog lists old and new values → saved 200000/50000 |
| SH-PD-not sellable | ✅ Product missing parcel parameters → bottom bar "Belum bisa dijual" (all 269 SKUs on stag lack them, parameters filled for 7 test products); B1 layout: name above price (mockup has price on top) |
| SH-PD-shipping | ✅ Select DKI→Jakarta Selatan→Kebayoran Baru: "Mulai Rp 10.000", "Belanja Rp 80.000 lagi untuk potongan ongkir" (200k−120k) |
| SH-PD-claim coupon | ✅ Detail "Kupon → Klaim" overlay claims QA Diskon 20rb → "Sudah diklaim" cannot be tapped again, 1 in DB |
| SH-CO-no address | ✅ Checkout without address "Tambah alamat pengiriman / Isi alamat dulu…", Buat Pesanan greyed out; D2 UI fidelity 93 (missing "Kamu hemat" strip because there is no coupon; top bar purple, mockup white) |
| SH-CO-add address | ⚠️ D5 UI fidelity 88: actual is a full page (with Label & utama, Jadikan alamat utama), mockup is a bottom sheet with only 4 items; on the emulator the sheet closes when the keyboard is dismissed and the filled content is lost (suspected issue, to be reproduced) |
| SH-CO-default | ✅ Address + JNE Reguler 2–3 Termurah Rp 10.000 + insurance switch; without a coupon, full PawCoin 130.000; D1 UI fidelity 92 (PawCoin amount shown left of the switch, mockup has it in the subtitle; top bar purple) |
| SH-CO-coupon auto-select | ✅ After claiming a coupon the fixed-amount one is auto-selected −20.000, "Kamu hemat Rp 20.000", payable 110.000, free-shipping gap synced to 100.000 |
| SH-CO-insurance | ✅ Insurance 0,5% → Rp 500, payable 110.500 (ceil 100.000×0.5%) |
| SH-CO-pure coin submit | ✅ TOKO-20261108-7CJ8M6 PENDING_SHIPMENT / PAWCOIN 110.500; coupon USED; balance 317.000→206.500; growth points SPEND_SHOP +55; E6 UI fidelity 95 (pure coin has no "PawCoin + QRIS" row, by design) |
| SH-OD-awaiting shipment | ✅ Order detail "Menunggu dikirim" + amount breakdown + PawCoin payment + shipping address + order number copy + WhatsApp support; F6 UI fidelity 96 |
| SA-fulfilment-place order | ✅ Fulfilment drawer "Biteship order" confirm dialog restates courier/shipping/insurance → waybill STUBWB7558055441, actual shipping 10.000, waybill created (awaiting pickup); H2 92, H3 85 (missing the green "Waybill created · pickup scheduled" strip and toast) |
| SA-fulfilment-order number | ⚠️ Admin console order number column/drawer shows the internal token (32LrsRe4…), mockup and App show a readable number (legacy since 1.3.0, not introduced in this version) |
| SH-LOG-callback | ✅ stub pushes picked → SHIPPED, dropping_off → out_for_delivery, delivered → DELIVERED |
| SH-OD-delivered | ✅ Five-node logistics timeline + waybill number copy + "Konfirmasi terima"; F9 93 (button dark, mockup purple; stub has no "Lacak di situs kurir") |
| SH-OD-confirm receipt | ✅ No second confirmation (F8 removed per the 11-05 decision) → COMPLETED; F11 90 (mockup "Selesai" card green background with green text, actual white background) |
| Surroundings-order center | ✅ Semua/Belanja shows this order, Belanja has the Kupon Saya entry and status sub-tabs; failed/expired payments not listed (per existing rule) |
| SH-CART-list | ✅ Selected rows priced: 2×32.000+shipping 10.000=estimate 74.000; "Belanja Rp 136.000 lagi"; invalid group Tidak Tersedia(2) + Cari mirip/Hapus; C1/C3 UI fidelity 92 |
| ❌ BUG-142-01 | Checkout page shows unsellable rows (missing parcel parameters) as "Sisa 2, diminta 1" (stock copy): App _unavailableText only distinguishes delisted/stock and does not handle the new backend reason NOT_SELLABLE (checkout_page_v3.dart:527-529). Suggest showing "Tidak tersedia"-style copy |
| ⚠️ DEC-142-01 | Pending decision: the cart shows invalid rows as unchecked and excludes them from Checkout/estimate (8-3 AC4.3, 3-1 §50 "invalid rows do not take part in checkout"), but the checkout backend still pulls in "rows ticked in the DB that are invalid" and blocks the whole order (CheckoutLineResolver.java:99-101, kept for the old App 409 behavior). New-version users get blocked by an item they did not tick. Suggest: new checkout mode excludes invalid rows, old mode unchanged |
| SH-CO-QRIS submit | ✅ TOKO-20261108-RZ78GW PENDING_PAYMENT / QRIS 74.000, enters the awaiting-payment detail (60-minute countdown + Batalkan / Bayar Sekarang) |
| ❌ BUG-142-02 | The shop payment API POST /me/shop-orders/{token}/pay returns 500 "Service temporarily unavailable" (traceId a348e336…) when the gateway rejects the payment (GemPay P04); should be a business failure code; the gift line fixed the same issue in 10-19, the new shop API did not follow |
| ⚠️ BUG-142-03 | On rejection the E7 "Kode QR gagal dibuat · Coba lagi" panel closes automatically after about 3 seconds (polling judges declined), the toast "Pembayaran ditolak…" shows for only about 1 second; afterwards the payment method on the detail page still shows "Menunggu", with no persistent failure message. The E7 mockup requires the panel to stay and be retryable; E7 UI fidelity 90 (panel content itself matches) |

## Fix and retest record (2026-11-08 ~ 11-09)

> The ❌ / ⚠️ and UI fidelity scores below 95 in the "Execution record" above are the results at that time; below is the state after fixes.

| Item | Result |
|---|---|
| Retest-DEC-142-01 | ✅ After the fix (stag a23a59e1c): with the invalid row Wanpy ticked in the DB + valid row Prama → checkout carries only Prama (Produk (1)), order placed successfully 39.000 |
| Retest-BUG-142-02 | ✅ /pay gateway rejection → 502 payment-gateway "The payment gateway cannot generate a QR code for now, please try again later", no Unhandled exception |
| Retest-BUG-142-03 | ✅ After rejection the payment card on the order detail permanently shows red text "Pembayaran ditolak bank atau e-wallet kamu…" |
| Retest-BUG-142-01 | ✅ Covered by an L0 case (NOT_SELLABLE → Tidak tersedia); the emulator path no longer triggers it because of DEC-142-01 |
| SH-HOME-22 | ✅ Pet profile weight note "Opsional. Dipakai untuk merekomendasikan produk yang pas." |
| Automation-after fix | ✅ App 4933 passed; backend full run on a fresh DB 9048 tests, 2 assertions that changed with the fix were corrected, all green |
| Full ocr review | ✅ main..dev_1.4.2 709 files in total, no high-risk issues; M1 cart undo, M2 rate-limit IP, M3 topic suggestions, L1 cancelled order code output, L2 duplicate address creation, L3 address book set default fixed (8278c4910) |
| UI fidelity A1 / D5 / H3 / F11 / admin A2 / A3 / G2 | ✅ Self-rated 95~97 after fixes (a1b2c6d72) |
| Shop top bar | ✅ Whole shop changed to white background with dark title, D1 about 95, D2 about 96 (bfb7bf384) |
| UI fidelity C1 / C3 / E7 | ✅ Self-rated 96 / 95 after fixes; the QR-failure panel no longer closes automatically (6b6bb195c) |
| Not executed | ⚠️ UI fidelity of the other ~200 screens not scored; cases for shop cancellation / refund, coupons and redemption, admin console pages, milestones, KTP, growth points reversal, etc. were not finished on stag and are covered by manual testing |
