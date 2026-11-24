# VHS FEVER Research Desk

A static, browser-only review tool for the JSONL outputs from the VHS FEVER scrape workflow.

## Open it
After GitHub Pages is enabled with **GitHub Actions** as the publishing source, visit:

https://homelessbrian.github.io/vhs-fever-catalog/reviewer/

## Load a scrape
1. Open the **VHS FEVER scrape proof of concept** workflow run.
2. Download the `vhs-fever-scrape-poc` artifact and extract the ZIP.
3. In Research Desk, choose **Load JSONL data**.
4. Select `vhsvault-scrape-poc-enriched.jsonl` to review the inspected sample. You can also select the classified file to bring in the broader discovery list. If selecting multiple files, choose raw, classified, inventory, enriched in that order; records are merged by Internet Archive identifier.
5. Search and filter by classification, TMDB state, and review decision. Select a record to see its classification evidence, inventory clues, TMDB match/candidates and filename-based artwork suggestions.
6. Mark **Keep**, **Investigate**, or **Reject** and add notes. Export decisions as JSON when finished.

## Data handling
- Runs entirely in the browser; no server or external JavaScript libraries.
- Source JSONL files are not uploaded anywhere by this app.
- Review decisions are stored in this browser's local storage. Use **Export review decisions** to back them up or move them.
- A review decision is not a rights determination or permission to display artwork/video.
- Image links are direct source references from the metadata; this tool does not download, cache, proxy or rehost them.
