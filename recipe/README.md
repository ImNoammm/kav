# Fixing Moovit or Google without an update

Every Kav checks two signed recipe files once a day and keeps the last good copy:

- `recipe/kav.json` in this repo: the Moovit values Kav sends (API key, the Moovit
  version Kav says it is, the store app id, the server addresses, headers) and a
  patch for Google's recipe.
- Vela Maps' `calibration.json`: how Kav asks Google Maps' web search. Vela keeps it
  current; Kav reads only the search parts of it.

A copy counts only when its signature checks out against the key Kav pins for it,
and only when every address in it is Moovit's own (`*.moovitapp.com`, https) or
Google's. A copy that fails either check is ignored whole and Kav keeps what it had.

## When Moovit changes a value

1. Edit `recipe/kav.json` and raise `"version"` by one. Kav only takes a newer one.
2. Run `tools/sign_recipe.sh`. It signs with `.signing/recipe.key` and checks the
   signature.
3. Commit and push `recipe/`. Every Kav picks it up within a day.

What the file can change:

| Field | What it is |
| --- | --- |
| `moovit.apiKey`, `moovit.clientVersion`, `moovit.storeAppId` | The values Moovit's app sends |
| `moovit.hosts.app4`, `app5`, `app4cdn`, `static` | Moovit's server addresses |
| `moovit.userHeaders` | Headers on sign-up and renewal: a value replaces Kav's, `null` leaves the header out |
| `moovit.headers` | The same for every other call |
| `google` | A patch on Vela's recipe (`suggestEndpoint`, `suggestPb`, `suggestPaths`, `searchEndpoint`, `searchPb`, `paths`, `userAgent`) |

A Google patch names the Vela file it was made for in `"velaVersion"`. Once Vela
publishes a newer file of its own, Vela's wins and the patch stops applying, so a
stale patch never overrides a newer fix.

What still needs an app update: a new kind of request, a new field in a request's
body, a new sign-in step. The file changes values, not how Kav works.

## The key

`.signing/recipe.key` is the private half. It is never committed (`.gitignore`) and
it should be backed up somewhere safe: without it no new recipe can be signed, and
changing to a new key takes an app update. The public half is pinned in
`data/KavRecipe.kt`.
