# Premium plan (proposal, needs the owner's decisions)

Nothing here is built yet: charging money needs a Google Play merchant account, prices and tax
details that only the owner can set. This is the plan to agree on first.

## Principles

- **No ads, ever.** Ads would need the internet permission and tracking SDKs, and would break
  the app's main promise (private by design) and its Data safety answers.
- **The free app is complete**, not a demo: people must love it before paying.
- **Pay once for the look, not for basics.** Applying, folding animations and the user's own
  photos and videos stay free.

## Free

- All 20 drawn scenes with their built-in motion, 3 of the 8 liquid glass wallpapers (Daybreak,
  Lagoon, Midnight glass).
- Add your own: photos (every motion, 3D) and videos.

## Fold Motion Pro (one-time purchase)

Suggested price: **US$3.99** (local prices set by Play). Optionally a launch price of $2.99.

- All liquid glass wallpapers, and every new glass set added later.
- A glass wallpaper in the colours of your own photo (planned).
- Future premium packs (seasonal sets, new effects).
- A small badge in Settings and the warm feeling of supporting a small developer.

Why one-time and not a subscription: a wallpaper app is used for months without opening it; a
subscription invites cancellations and bad reviews. A later "Pro Plus" subscription only makes
sense if cloud AI generation is added (it costs money per generation).

## Later: AI wallpapers (needs a server)

Typing "golden desert at night" and getting a moving wallpaper needs a cloud model (for example
through Higgsfield or another video model), a small backend, abuse filtering and a privacy
policy update (the prompt leaves the phone). Cost is per generation, so it would be credits or a
subscription (for example 20 generations a month for $4.99). Only worth it after the Play launch
shows demand.

## What the owner decides

1. Free vs Pro split above: agree or change.
2. Price: $3.99 one-time (or another).
3. Set up a Google Play payments profile (merchant account) in Play Console.
4. When to start: suggested after the closed test, for the first production release.

## Engineering when approved (about one session)

- Google Play Billing Library: one non-consumable product `pro`, purchase flow, restore on
  reinstall, acknowledgement; works offline after purchase (Play caches entitlement).
- Lock icon on Pro items in Discover, a clean paywall sheet in English and Arabic.
- Data safety stays "no data collected": Play Billing is handled by Google Play itself.
- Tests with Play Billing's test cards on the closed track.
