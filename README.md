# VHS FEVER

Full-length VHS movies from the Internet Archive, sorted by genre.

## How it works

One bot, **Find VHS movies** (`.github/workflows/movies.yml`), does everything:

1. Finds new uploads in the VHS Vault, plus tapes found elsewhere on the Archive.
2. Picks pages it hasn't read yet (`data/batches/` lists every page it has read).
3. Keeps only recordings 40 minutes or longer that aren't split into pieces, taped
   off TV, tape openings, or DVD/Blu-ray.
4. Matches them on TMDB and checks their sound and picture.
5. Keeps only full-length VHS movies, cleans up the names and adds TMDB genres.
6. Publishes **one movie list** to the `movies-data` branch.

It then starts its next run by itself. To pause it, set `keep_going` to `false`
in `data/batches/auto.json`. `size` there is how many Archive pages it reads per run.

A movie goes straight onto the TV shelves only when the bot is sure: one clear TMDB
match whose running time fits, clearly a VHS tape, and good sound and picture.
Everything else waits in the review app.

## The apps

- **Review app** (`android/`): look over the movies the bot isn't sure about, fix the
  TMDB match, mark files good or bad. Reviews are saved to `review/decisions.json` on
  the `review-data` branch.
- **TV app** (`tv/`): the video store. Movies the bot is sure about plus the ones you
  marked good, on shelves by genre. Tapes made for small children (Bob the Builder,
  Thomas, sing-alongs, ABCs, short cartoon specials) are tagged `kids` by the bot
  (`scripts/kids_tapes.py`) and go on the Kids shelf instead of Animation or Family.

Both read the same list: `movies-data/reviewer-feed.json`.

## Keys

`TMDB_API_READ_ACCESS_TOKEN` is a repository secret used by the bot and built into the apps.
