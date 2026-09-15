# Deploying to Cloud Run

One image carrying the API and the portal, one service, one domain. GitHub Actions builds
and deploys it; nothing is deployed from a laptop.

Kubernetes is not needed here. It earns its complexity scheduling many services; this is a
jar, a megabyte of static files and a database worth buying rather than running.

Work through the phases in order. Each one is checkable on its own — do not move on until
the check at the end of it passes.

---

## Phase 1 — The project

```bash
export PROJECT=your-project-id
export REGION=us-central1
export DOMAIN=portal.example.com        # the name you bought

gcloud config set project "$PROJECT"
gcloud services enable \
    run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com \
    secretmanager.googleapis.com iamcredentials.googleapis.com \
    compute.googleapis.com vpcaccess.googleapis.com servicenetworking.googleapis.com

gcloud artifacts repositories create b2b --repository-format=docker --location="$REGION"
```

**Check:** `gcloud artifacts repositories list --location="$REGION"` lists `b2b`.

---

## Phase 2 — Network, so Sellfox sees one address

Do this before the database, because the database will sit on the same network.

Sellfox allowlists by IP. Cloud Run's egress is dynamic by default, so syncs fail with
code `40005` — *访问的客户端ip不在白名单列表* — until traffic leaves from an address you own.

```bash
gcloud compute networks create b2b --subnet-mode=custom
gcloud compute networks subnets create b2b \
    --network=b2b --region="$REGION" --range=10.10.0.0/24

gcloud compute addresses create b2b-egress --region="$REGION"
EGRESS_IP="$(gcloud compute addresses describe b2b-egress --region="$REGION" --format='value(address)')"

gcloud compute routers create b2b-router --network=b2b --region="$REGION"
gcloud compute routers nats create b2b-nat \
    --router=b2b-router --region="$REGION" \
    --nat-external-ip-pool=b2b-egress \
    --nat-all-subnet-ip-ranges

echo "Allowlist this in Sellfox: $EGRESS_IP"
```

**Check:** `echo $EGRESS_IP` prints an address, and it is added in Sellfox. Until it is,
everything works except the catalogue sync.

---

## Phase 3 — The database

```bash
gcloud sql instances create b2b \
    --database-version=POSTGRES_17 --tier=db-g1-small --region="$REGION" \
    --storage-auto-increase --backup-start-time=09:00
gcloud sql databases create b2b --instance=b2b
gcloud sql users create b2b --instance=b2b --password='<pick a long one>'
```

Flyway runs the migration on first boot, so there is nothing to load by hand.

**Check:** `gcloud sql instances describe b2b --format='value(state)'` says `RUNNABLE`.

---

## Phase 4 — Secrets

`application.yml` has no fallbacks precisely so a missing one fails loudly rather than
starting on a default someone left in version control.

```bash
printf '%s' '<the database password>'    | gcloud secrets create b2b-db-password --data-file=-
printf '%s' "$(openssl rand -base64 48)" | gcloud secrets create b2b-jwt-secret --data-file=-
printf '%s' '<sellfox app id>'           | gcloud secrets create sellfox-app-id --data-file=-
printf '%s' '<sellfox app secret>'       | gcloud secrets create sellfox-app-secret --data-file=-
printf '%s' '<zoho app password>'        | gcloud secrets create b2b-mail-password --data-file=-

RUNTIME_SA="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')-compute@developer.gserviceaccount.com"
for s in b2b-db-password b2b-jwt-secret sellfox-app-id sellfox-app-secret b2b-mail-password; do
  gcloud secrets add-iam-policy-binding "$s" \
      --member="serviceAccount:$RUNTIME_SA" --role=roles/secretmanager.secretAccessor
done
```

**Check:** `gcloud secrets list` shows five, and `gcloud secrets versions access latest
--secret=b2b-jwt-secret | wc -c` prints something over 40.

---

## Phase 5 — Let GitHub deploy, without a key

Workload Identity Federation lets the workflow exchange GitHub's own OIDC token for
short-lived Google credentials. No service account JSON anywhere: nothing to rotate, and
nothing that keeps working if it leaks.

```bash
gcloud iam service-accounts create github-deploy --display-name="GitHub Actions deploy"
DEPLOY_SA="github-deploy@$PROJECT.iam.gserviceaccount.com"

for role in roles/run.admin roles/artifactregistry.writer roles/cloudsql.client; do
  gcloud projects add-iam-policy-binding "$PROJECT" \
      --member="serviceAccount:$DEPLOY_SA" --role="$role"
done
# Deploying a service means setting the identity it runs as, which requires acting as it.
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA" \
    --member="serviceAccount:$DEPLOY_SA" --role=roles/iam.serviceAccountUser

gcloud iam workload-identity-pools create github --location=global \
    --display-name="GitHub Actions"

gcloud iam workload-identity-pools providers create-oidc github \
    --location=global --workload-identity-pool=github \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
    --attribute-condition="assertion.repository == 'ethan-ning/b2b-wholesale-backend'"

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')"
POOL="projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github"

# Only this repository may impersonate the deployer.
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA" \
    --role=roles/iam.workloadIdentityUser \
    --member="principalSet://iam.googleapis.com/$POOL/attribute.repository/ethan-ning/b2b-wholesale-backend"

echo "GCP_WIF_PROVIDER = $POOL/providers/github"
echo "GCP_DEPLOY_SA    = $DEPLOY_SA"
```

The attribute condition matters. Without it the provider will mint tokens for *any*
GitHub repository, and the binding is the only thing standing between a stranger's fork
and your project.

Then in **github.com → b2b-wholesale-backend → Settings → Secrets and variables → Actions
→ Variables**, add:

| Variable | Value |
|---|---|
| `GCP_PROJECT_ID` | your project id |
| `GCP_REGION` | `us-central1` |
| `GCP_WIF_PROVIDER` | the `projects/.../providers/github` line printed above |
| `GCP_DEPLOY_SA` | `github-deploy@…iam.gserviceaccount.com` |
| `GCP_SQL_INSTANCE` | `b2b` |
| `PUBLIC_ORIGIN` | `https://<your domain>` — scheme and host, no trailing slash |

`PUBLIC_ORIGIN` is the origin the portal is served from, and the deploy refuses to run
without it. Browsers attach `Origin` to a POST even when the page came from the same host,
so an origin the server does not recognise is refused by the CORS filter before the
controller runs — every sign-in returns 403 while every page still loads, which reads as a
wrong password rather than a configuration error.

These are variables, not secrets — none of them is one, and a project id in a log is
easier to debug than a masked string.

**Check:** push to `master` and watch **Actions**. The `test` job runs the whole suite,
including the Postgres-backed integration tests, because the runner has Docker.

---

## Phase 6 — First deploy

The workflow attaches the network from Phase 2 and the database from Phase 3, so those
have to exist before it runs. That is the only ordering that matters.

The workflow deploys on every push to `master`. To do the first one now:

**Actions → Deploy → Run workflow.**

Two things happen that are worth understanding. There is no admin account yet — the seeded
one lives in `db/seed`, which production never puts on the Flyway path, because a migration
that seeds a known password would run everywhere and this repository is public. And the
service has no domain yet; it answers on its `*.run.app` URL.

```bash
URL="$(gcloud run services describe b2b-wholesale --region="$REGION" --format='value(status.url)')"
curl -s "$URL/actuator/health"                     # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' "$URL/"   # 200, the portal
```

Create your admin, with a BCrypt hash at cost 12 that you generated:

```bash
gcloud sql connect b2b --user=b2b
```
```sql
INSERT INTO admin_user (email, password_hash, name, role)
VALUES ('you@example.com', '<bcrypt hash>', 'Your Name', 'SUPER_ADMIN');
```

**Check:** open `$URL/admin/login` and sign in.

---

## Phase 7 — The domain

A global load balancer rather than a Cloud Run domain mapping: it gives an anycast IP that
never changes, a Google-managed certificate, and somewhere to put Cloud Armor or CDN later
without moving the site. It costs about $18 a month, which is the price of not migrating
later.

```bash
gcloud compute network-endpoint-groups create b2b-neg \
    --region="$REGION" --network-endpoint-type=serverless \
    --cloud-run-service=b2b-wholesale

gcloud compute backend-services create b2b-backend --global \
    --load-balancing-scheme=EXTERNAL_MANAGED
gcloud compute backend-services add-backend b2b-backend --global \
    --network-endpoint-group=b2b-neg --network-endpoint-group-region="$REGION"

gcloud compute url-maps create b2b-lb --default-service=b2b-backend

gcloud compute ssl-certificates create b2b-cert --domains="$DOMAIN" --global
gcloud compute target-https-proxies create b2b-https \
    --url-map=b2b-lb --ssl-certificates=b2b-cert

gcloud compute addresses create b2b-ingress --global --ip-version=IPV4
INGRESS_IP="$(gcloud compute addresses describe b2b-ingress --global --format='value(address)')"

gcloud compute forwarding-rules create b2b-https-rule --global \
    --address=b2b-ingress --target-https-proxy=b2b-https --ports=443

echo "Point $DOMAIN at $INGRESS_IP with an A record"
```

Add that A record at your registrar. Then send plain HTTP to HTTPS, so a bookmarked
`http://` link is not a dead end:

```bash
cat > /tmp/b2b-redirect.yaml <<EOF
name: b2b-redirect
defaultUrlRedirect:
  httpsRedirect: true
  redirectResponseCode: MOVED_PERMANENTLY_DEFAULT
EOF
gcloud compute url-maps import b2b-redirect --global --quiet --source=/tmp/b2b-redirect.yaml
gcloud compute target-http-proxies create b2b-http --url-map=b2b-redirect
gcloud compute forwarding-rules create b2b-http-rule --global \
    --address=b2b-ingress --target-http-proxy=b2b-http --ports=80
```

The certificate provisions only once DNS resolves to that address, and it can take up to
an hour. Watch it:

```bash
gcloud compute ssl-certificates describe b2b-cert --global \
    --format='value(managed.status,managed.domainStatus)'
```

**Check:** `ACTIVE` above, and `curl -sI "https://$DOMAIN/" | head -1` says `200`.

Finally, close the back door — with a load balancer in front, the `*.run.app` URL should
not also serve the site:

```bash
gcloud run services update b2b-wholesale --region="$REGION" \
    --ingress=internal-and-cloud-load-balancing
```

---

## Phase 8 — Turn the sync on

The service already deploys with `SELLFOX_SCHEDULE_ENABLED=true`, so once the egress
address from Phase 2 is allowlisted, stock refreshes hourly and the catalogue nightly at
02:15 America/Chicago.

Set the import scope once, in the portal: **Sellfox Sync → Change** → pick a category
group and the warehouses. Saving starts a full sync.

### The two flags that are not optional

`--min-instances=1 --no-cpu-throttling`, both in the workflow.

The sync is a background thread on a timer. Scaled to zero there is no instance to run it,
so 02:15 passes and nothing happens — silently, because nothing failed. And between
requests Cloud Run takes CPU away, so a sync that starts anyway would crawl. Neither shows
up as an error; both show up as a catalogue that quietly stopped updating.

They also remove the three-second JVM cold start.

---

## How CI/CD hangs together

Two repositories, one deployable.

```
b2b-wholesale-backend  push to master ─┐
                                       ├─► test → build image → deploy → smoke check
b2b-wholesale-frontend push to main ───┘
   typecheck, lint, build, then repository_dispatch
```

The portal has no deployment of its own; it is compiled into the API's image. Its workflow
checks it and then asks this repository to deploy that commit, passing the SHA so the
image is built from exactly what was pushed.

For that dispatch the portal repository needs one secret, `DEPLOY_DISPATCH_TOKEN`: a
fine-grained personal access token with **Contents: read and write** on
`b2b-wholesale-backend`. `GITHUB_TOKEN` cannot reach another repository, which is the
point of it.

Deploys are serialised — `concurrency: deploy` — and a queued one is superseded rather
than cancelled mid-flight.

---

## Rolling back

Every deploy is a revision, and traffic moves without a rebuild:

```bash
gcloud run revisions list --service=b2b-wholesale --region="$REGION"
gcloud run services update-traffic b2b-wholesale --region="$REGION" \
    --to-revisions=<revision>=100
```

The database does not roll back with it. `V1__schema.sql` is one script today, so a schema
change means a new migration rather than an edit — Flyway refuses a file whose checksum has
moved, which is the behaviour you want.

---

## What it costs, roughly

| | per month |
|---|---|
| Cloud SQL `db-g1-small` | $25–35 |
| Cloud Run, one warm instance with CPU allocated | $15–25 |
| Load balancer | ~$18 |
| Cloud NAT | ~$3 + traffic |
| Artifact Registry, Secret Manager | a few cents |

Call it $60–85. A GKE Autopilot cluster starts around $75 before it runs anything at all.

The cheapest saving is `--min-instances=0`, but only if you move the sync trigger to Cloud
Scheduler first — see the README. That trades about $20 a month for an auth path to build.

---

## Building locally

Nothing above is needed to run the same image on your machine:

```bash
./build-image.sh          # builds the portal, collects dist/, builds b2b-wholesale:local
docker run --rm -p 8080:8080 \
  -e DB_URL='jdbc:postgresql://host.docker.internal:5432/b2b' \
  -e DB_USER=b2b -e DB_PASSWORD=b2b \
  -e JWT_SECRET='local-development-secret-at-least-32-bytes-long' \
  -e SELLFOX_APP_ID=x -e SELLFOX_APP_SECRET=x \
  b2b-wholesale:local
```

`SELLFOX_APP_ID` and `SELLFOX_APP_SECRET` have to be set to something even when unused:
the production profile refuses to start on a missing setting rather than inventing one.
