# SloopyTV code server

A Cloudflare Worker that turns a customer login into a six-digit code. Single use, dies after
24 hours, login stored encrypted with a key derived from the code (PBKDF2-SHA256, 100k rounds,
AES-256-GCM). The app decrypts on the TV. See `src/index.js`.

## Deploy (one time)

1. Free Cloudflare account at https://dash.cloudflare.com/sign-up
2. In this folder:

```bash
npx wrangler login                       # opens the browser, approve once
npx wrangler kv namespace create CODES   # prints an id; paste it into wrangler.toml
npx wrangler secret put ADMIN_PASSWORD   # the password for the admin page
npx wrangler deploy                      # prints the site address
```

The address looks like `https://sloopytv-codes.<you>.workers.dev`. Put that into the app's
`CODE_SERVER` in `app/build.gradle.kts` and cut a release; customers never see the address.

## Run locally

```bash
npx wrangler dev --local --port 8787
```

Then open http://127.0.0.1:8787/ . Local runs use a `.dev.vars` file for the password:
`ADMIN_PASSWORD=whatever`.
