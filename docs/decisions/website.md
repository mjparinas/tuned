# Website

The public website at **www.tunedplayer.com** (Melvin registered it 2026-11-19; until DNS points there it is also `tuned-devices.vercel.app`), served by the Vercel project `tuned-devices` from `devices/public/`. The account, TV sign-in, device list and admin pages live on the same site ([accounts](accounts.md), [premium](premium.md), [admin](admin.md)).

## The landing page (Melvin, 2026-11-19)

"Similar to liftsmith, can you create a beautiful landing page for the app", then "borrow some design from getliftsmith.com" and "add a feature section that lists all of our features". `/` follows Liftsmith's home page: a hero with the app on a phone and the download button, a strip of facts, how it works in three steps, the main features each beside real screenshots, **Everything in Tuned** (every feature, grouped), coming soon, questions, the newest blog posts and a closing call to action. It wears the app's look from `site.css` ([branding](branding.md#the-website-melvin-2026-11-19)) plus `web.css`: the channel stack, violet and teal, rounded cards and fully round buttons, light and dark from the device.

- **Screenshots are the app's own screens** drawn with sample data ([screenshots](screenshots.md)), never a real provider, channel or sports brand, saved as WebP in `devices/public/img/`. `img/og.png` is the picture shared links show.
- **The feature list is one file**, `devices/site/features.mjs`, built from this record: a group per area, each feature a title and a line, with `premium: true` on what Premium unlocks in the Google Play version. The landing page and `/features.json` (for the account page and anything else that lists features) are built from it. A pull request that adds or removes a feature changes it too.
- **Download** is the newest GitHub release's APK, `github.com/mjparinas/tuned/releases/latest/download/tuned.apk`, which GitHub keeps pointing at the latest release ([updates](updates.md)).
- **Store badges** (Melvin, 2026-11-19: "similar to lift smith use the proper google play, ios, mac image buttons"; "mention that the samsung tizen app and ios, mac apps are coming soon"): Google Play, App Store, Mac App Store and Samsung TV badges in the stores' standard black style, each dimmed with "Coming soon" until its address is filled in `devices/site/config.mjs` (`stores`), when it becomes a link. No dates are given. As on Liftsmith they are drawn, because the official badge files can't be fetched from where the site is built; swap in the official artwork from Google's and Apple's badge pages when convenient. The iOS, Mac and Samsung TV apps are plans, with nothing built yet.
- Never mention a sleep timer or a second video on screen ([scope](scope.md)); the one-connection rule is a selling point and is stated as such.
- The old order-number page that was at `/` (for someone who lost every Premium device) moved to `/devices`, and the app's Your devices screen points there.

## The blog (Melvin, 2026-11-19)

"Can you add blog posts that talk about whats new for each release and the date", and "ensure that each blog post has a CTA action, link to the android app".

- **Posts are Markdown** with frontmatter (`title`, `description`, `date` as YYYY-MM-DD, `kind`) in `devices/site/posts/`. The file's path is its address: `posts/whats-new/tuned-1-13-0.md` is `/blog/whats-new/tuned-1-13-0/`, and each folder gets its own index page. Kinds: `release` (What's new), `guide`, `compare`, `roundup`; the blog's front page groups posts by kind. The Markdown has headings, lists, tables, links, images, quotes, and lines starting with `<` pass through as HTML; images go in `devices/public/blog/img/`.
- **Every post ends with the call to action** to download Tuned for Android (and the coming-soon stores), from the shared template, so no post adds its own.
- **One post per release.** The releases up to 1.13.0 were written from their GitHub release notes, with a hand-written opening each. From now on `release.yml`, after publishing a GitHub release from `main`, runs `devices/release-post.mjs` on the release's notes (`feat` under New, `perf` under Faster, `fix` under Fixed; CI, tests, docs, refactors and admin-only work left out), rebuilds the site, commits the post to `main` (so it goes live with the release, Pacific date) and merges `main` back into `staging`. An existing post is never overwritten, so a post can be written by hand before the release.
- An RSS feed is at `/blog/feed.xml`.

## How it's built

No framework and no build step on Vercel, like the rest of `devices/`. `node devices/build-site.mjs` turns `devices/site/` (`config.mjs`, `features.mjs`, `home.html`, `posts/`) into `devices/public/index.html`, `public/blog/**`, `sitemap.xml`, `blog/feed.xml` and `features.json`, with the shared header, footer, canonical address, Open Graph tags and structured data (`lib/site-build.mjs`). The output is committed; the test `devices/lib/site-build.test.mjs` (CI's Node tests) fails when it is out of date, and Prettier skips the generated pages.

- **`sitemap.xml`** lists the landing page, the blog, each folder's index and every post, on `https://www.tunedplayer.com`. **`robots.txt`** allows everything but `/admin`, `/api/`, `/account`, `/app/`, `/tv` and `/devices`, and names the sitemap.
