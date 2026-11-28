# VHS FEVER movie list

Made by the "Find VHS movies" bot. Full-length VHS movies only.

- `reviewer-feed.json`: the movie list the review app reads (movies, their tapes and files, genres)
- `tv-feed.json`: a smaller copy with only what the TV app needs
- `auto-sort.jsonl`: the bot's call for each file (sure / not sure / bad, and why)
- `audio-checks.jsonl`: sound and picture check for each file
- `summary.json`: counts, genres, and what was left out
- `bot/`: what the bot remembers between runs (pages found, TMDB matches, IMDb genres, sound and picture checks)
