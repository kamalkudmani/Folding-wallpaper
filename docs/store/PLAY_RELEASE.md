# Publishing Fold Motion on Google Play

Status at 0.8.0: **the app is technically ready for a closed test, not yet for production.** The
code meets Play's technical rules (target API 36, App Bundle, no risky permissions, no data
collection). What is left needs the owner: a developer account, an upload key, a real-device test
on the Fold7, and Google's mandatory closed test.

## 1. What is done in the repository

| Requirement | Where |
|---|---|
| Target API level 36 (required for updates from 31 Aug 2026) | `app/build.gradle.kts` |
| Android App Bundle (.aab) built and signed by CI | `.github/workflows/android.yml`, artifact `fold-motion-aab` |
| Release build minified and shrunk (R8) | `app/build.gradle.kts` |
| Privacy policy (English + Arabic) | `docs/PRIVACY.md` |
| No INTERNET, storage, location, camera or microphone permissions | `app/src/main/AndroidManifest.xml` |
| App icon 512 x 512 | `docs/store/play-icon-512.png` |
| Feature graphic 1024 x 500 | `docs/store/feature-graphic-1024x500.png` |
| Adaptive and themed launcher icon | `app/src/main/res/drawable/ic_launcher_*.xml` |
| Store texts, data safety and content rating answers | this file, sections 4 to 6 |
| Phone screenshots (1080 x 1920, English and Arabic) and large-screen ones (1920 x 1080) | `docs/store/screenshots/`, made by `tools/store/screenshots.py` from the app's UI tour |
| Promo video for YouTube, Shorts and TikTok (vertical, English and Arabic) | `docs/promo/`, made by `PromoRender` + `tools/promo/promo.py` |
| Tester recruitment kit | `docs/store/TESTERS.md` |
| Premium (Pro) proposal | `docs/store/PREMIUM_PLAN.md` |

## 2. What the owner has to do (in order)

1. **Create a Google Play developer account** (one-time fee of US$25, paid by the owner) and
   complete Google's identity verification.
2. **Create an upload key** on any computer with Java (or ask Claude to do it in a session and
   store it as a secret):
   ```
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 upload.jks > upload.jks.b64
   ```
   Keep `upload.jks` and its passwords safe and **never commit them**.
3. **Add four repository secrets** (GitHub > Settings > Secrets and variables > Actions):
   `FOLDMOTION_KEYSTORE_BASE64` (contents of `upload.jks.b64`), `FOLDMOTION_KEYSTORE_PASSWORD`,
   `FOLDMOTION_KEY_ALIAS` (`upload`), `FOLDMOTION_KEY_PASSWORD`. CI then signs the bundle with
   the upload key. Without them it signs with the public dev key, which **must never be uploaded**
   (the build prints a warning).
4. **Test on the Fold7**: install the APK, apply a glass wallpaper, fold and unfold with the
   screen on and off, record a trace in *Settings > Fold Lab* and check smoothness. Fix anything
   found before testers see it.
5. **Create the app in Play Console**: name *Fold Motion Wallpapers*, default language English,
   app, free. Enrol in **Play App Signing** (Google keeps the app signing key; you upload with yours).
6. **Fill in the policy forms** with the answers in sections 5 and 6, add the privacy policy URL:
   `https://github.com/kamalkudmani/Folding-wallpaper/blob/main/docs/PRIVACY.md`
   (merge this branch into `main` first, or host the same text on GitHub Pages or Google Sites).
7. **Closed test (required for new personal accounts):** upload the `.aab` from the latest green
   CI run to a closed testing track and keep **at least 12 testers opted in for 14 days in a row**.
   Then apply for production access in Play Console.
8. **Production**: after approval, promote the tested release. Start with a staged rollout (20%).

## 3. Screenshots

Ready-made sets are in `docs/store/screenshots/` (`phone_en_*`, `phone_ar_*`, `large_*`): the app's
real screens, rendered by its UI tests, framed with a caption. Upload `phone_*` as phone
screenshots and `large_*` as 7- and 10-inch tablet screenshots. Real captures from the Fold7 can
replace them later and look even better:
- Cover screen: Discover wall, a glass wallpaper's detail page, the Add screen.
- Main screen (open): Discover wall in 4 columns, the detail page with the 3D fold demo.
- Optional: a 30-second video of folding and unfolding with a glass wallpaper (YouTube link);
  `docs/promo/fold-motion-promo-0.9-*.mp4` is a ready one rendered by the app itself.

## 4. Store listing

**App name** (30 max): `Fold Motion Wallpapers`

**Short description** (80 max)
- EN: `Live wallpapers that move when you fold and unfold your phone.`
- AR: `خلفيات حيّة تتحرك عندما تطوي هاتفك وتفتحه.`

**Full description, English**
```
Fold Motion makes live wallpapers for foldable phones where the motion is in the picture itself.
Close the phone and the scene gathers on the cover screen; open it and it flows out across the
inner screen. No effects laid on top: the wallpaper itself moves.

• Liquid glass: clear glass drops over soft colours, live on your phone. They gather when the
  phone is closed, flow apart as you open it and merge again when you close it. Tilt to move the
  light, tap to make them ripple.
• Scenes in depth: mountains, oceans, deserts, cities and space whose own layers glide into place
  when you fold and unfold, and keep living between folds (stars, glints, fireflies).
• Add your own: turn your photo into a moving wallpaper and choose how it moves, or let Auto lift
  a person or pet out of it in 3D. Or use your own video: it loops on both screens.
• Preview every wallpaper closed and open, with a 3D fold demo, before you apply it.
• Like the phone's own wallpapers, the motion waits for you to unlock.
• Private by design: everything is made on your phone. No internet permission, no account, no ads,
  no tracking.
• English and Arabic, light on battery: motion pauses when hidden and in power saving mode.
```

**Full description, Arabic**
```
يصنع Fold Motion خلفيات حيّة للهواتف القابلة للطي تكون فيها الحركة داخل الصورة نفسها. أغلق
الهاتف فيتجمع المشهد على الشاشة الخارجية، وافتحه فينساب على الشاشة الداخلية. لا مؤثرات فوق
الصورة: الخلفية نفسها تتحرك.

• زجاج سائل: قطرات زجاج شفاف فوق ألوان ناعمة، حيّة على هاتفك. تتجمع عندما يكون الهاتف
  مغلقاً، وتنساب متفرقة عند فتحه، وتندمج من جديد عند إغلاقه. أمِل الهاتف لتحريك الضوء، والمسها
  لتتموّج.
• مشاهد بعمق: جبال وبحار وصحارى ومدن وفضاء تنزلق طبقاتها إلى مكانها عند الطيّ والفتح، وتبقى
  حيّة بين الطيّات (نجوم وبريق ويراعات).
• أضف خلفيتك: حوّل صورتك إلى خلفية متحركة واختر طريقة حركتها، أو دع الوضع التلقائي يرفع الشخص
  أو الحيوان منها بشكل ثلاثي الأبعاد. أو استخدم الفيديو الخاص بك فيتكرر على الشاشتين.
• عاين كل خلفية مطوية ومفتوحة مع عرض طيّ ثلاثي الأبعاد قبل تطبيقها.
• مثل خلفيات الهاتف نفسه، تنتظر الحركة حتى تفتح القفل.
• خصوصية تامة: كل شيء يُصنع على هاتفك. لا صلاحية إنترنت، لا حساب، لا إعلانات، لا تتبّع.
• بالعربية والإنجليزية، وخفيف على البطارية: تتوقف الحركة عند الإخفاء وفي وضع توفير الطاقة.
```

**Category**: Personalization. **Tags**: Live wallpapers, Wallpapers.
**Contact email**: required by Play, use an address the owner reads.

Avoid other companies' trademarks in the name, icon and screenshots (for example "Galaxy" or
"Samsung"). Saying "for foldable phones" is safe.

## 5. Data safety form

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (nothing is collected) |
| Do you provide a way for users to request that their data is deleted? | Not applicable; data stays on the device and is removed with the app |

Why "No" is correct: the app has no INTERNET permission, so nothing can leave the phone except a
file the user chooses to share through Android's share sheet, which Play does not count as
collection or sharing. The 3D-photo model is bundled and run by LiteRT inside the app: no ML Kit,
no Google Play services, no usage metrics.

**App access**: all features are available without login. **Ads**: the app contains no ads.

## 6. Content rating (IARC questionnaire)

Category: *All other app types*. Answer **No** to violence, sexuality, language, controlled
substances, gambling, user-generated content shared with others, user interaction, location
sharing and digital purchases. Expected rating: **Everyone / PEGI 3**.

**Target audience**: 13 and over is the simplest choice (choosing under-13 brings the Families
policy requirements, which the app would meet but which add review steps).

## 7. Before each release

- CI green on the release commit; download `fold-motion-aab` from that run.
- `versionCode` increased (Play rejects a code it has seen).
- Release notes in English and Arabic.
- Check the merged manifest still has no INTERNET permission: `aapt2 dump permissions` on the APK.
