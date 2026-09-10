# Deploying to Cloud Run

One image, one service, one URL. The portal is packaged alongside the API and served from
the same origin, so there is no CORS list naming a deployment, no second certificate and
no proxy hop — and one thing to roll back when a release is wrong.

Kubernetes is not needed for this. It earns its complexity when you have many services to
schedule or bin-packing to do; here there is a jar, a megabyte of static files, and a
database you would rather buy than run.

## Once, per project

```bash
export PROJECT=your-project
export REGION=us-central1

gcloud config set project "$PROJECT"
gcloud services enable run.googleapis.com sqladmin.googleapis.com \
    artifactregistry.googleapis.com cloudbuild.googleapis.com \
    secretmanager.googleapis.com cloudscheduler.googleapis.com

gcloud artifacts repositories create b2b \
    --repository-format=docker --location="$REGION"
```

### The database

```bash
gcloud sql instances create b2b \
    --database-version=POSTGRES_17 --tier=db-g1-small --region="$REGION"
gcloud sql databases create b2b --instance=b2b
gcloud sql users create b2b --instance=b2b --password='<pick one>'
```

Flyway runs the migration on first boot, so there is nothing to load by hand. The
instance's connection name — `PROJECT:REGION:INSTANCE` — is what `--add-cloudsql-instances`
wants:

```bash
gcloud sql instances describe b2b --format='value(connectionName)'
```

### Secrets

Nothing with a secret in it belongs in an environment variable you can read off a console
page, and `application.yml` has no fallbacks precisely so a missing one fails loudly.

```bash
printf '%s' '<the database password>'      | gcloud secrets create b2b-db-password --data-file=-
printf '%s' "$(openssl rand -base64 48)"   | gcloud secrets create b2b-jwt-secret --data-file=-
printf '%s' '<sellfox app id>'             | gcloud secrets create sellfox-app-id --data-file=-
printf '%s' '<sellfox app secret>'         | gcloud secrets create sellfox-app-secret --data-file=-

# The runtime service account has to be allowed to read them.
SA="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')-compute@developer.gserviceaccount.com"
for s in b2b-db-password b2b-jwt-secret sellfox-app-id sellfox-app-secret; do
  gcloud secrets add-iam-policy-binding "$s" \
      --member="serviceAccount:$SA" --role=roles/secretmanager.secretAccessor
done
```

## Building

The portal lives in its own repository, so its build output has to be collected before the
image is built. Locally:

```bash
./build-image.sh          # builds the portal, copies dist/, builds b2b-wholesale:local
```

In CI, `cloudbuild.yaml` does the same by cloning the portal at `_FRONTEND_REF`:

```bash
gcloud builds submit --config cloudbuild.yaml \
    --substitutions=_REGION="$REGION",_SQL_INSTANCE="$(gcloud sql instances describe b2b --format='value(connectionName)')"
```

Pin `_FRONTEND_REF` to a tag when a release needs to be reproducible; on `main` it tracks
the portal's default branch.

## Deploying by hand

```bash
SQL="$(gcloud sql instances describe b2b --format='value(connectionName)')"

gcloud run deploy b2b-wholesale \
  --image="$REGION-docker.pkg.dev/$PROJECT/b2b/b2b-wholesale:latest" \
  --region="$REGION" \
  --allow-unauthenticated \
  --add-cloudsql-instances="$SQL" \
  --min-instances=1 --no-cpu-throttling \
  --memory=1Gi --cpu=1 --timeout=300 \
  --set-env-vars="DB_URL=jdbc:postgresql:///b2b?cloudSqlInstance=$SQL&socketFactory=com.google.cloud.sql.postgres.SocketFactory,DB_USER=b2b,SELLFOX_SCHEDULE_ENABLED=true" \
  --set-secrets="DB_PASSWORD=b2b-db-password:latest,JWT_SECRET=b2b-jwt-secret:latest,SELLFOX_APP_ID=sellfox-app-id:latest,SELLFOX_APP_SECRET=sellfox-app-secret:latest"
```

`--allow-unauthenticated` lets requests reach the container; it does not make the API
public. Every route but the two logins and the health check needs a token, and a dealer's
token cannot reach `/api/admin/**` — that is what the `b2b-web` tests exist to prove.

### The two flags that are not optional

`--min-instances=1 --no-cpu-throttling`.

The sync is a background thread on a timer. Scaled to zero there is no instance to run it,
so 02:15 passes and nothing happens — silently, since nothing failed. And between requests
Cloud Run takes CPU away, so a two-minute sync that starts anyway would crawl or stall.
Neither shows up as an error; both show up as a catalogue that stopped updating.

They also remove the cold start, which is about three seconds of JVM.

## The first admin

The seeded accounts live in `db/seed`, which production never puts on the Flyway path — a
migration that seeds a known password would run everywhere, and this repository is public.
Insert yours explicitly, with a hash you generated:

```bash
gcloud sql connect b2b --user=b2b
```
```sql
INSERT INTO admin_user (email, password_hash, name, role)
VALUES ('you@example.com', '<bcrypt hash>', 'Your Name', 'SUPER_ADMIN');
```

Any BCrypt implementation at cost 12 will do; the application verifies with Spring
Security's encoder.

## Checking it worked

```bash
URL="$(gcloud run services describe b2b-wholesale --region="$REGION" --format='value(status.url)')"

curl -s "$URL/actuator/health"                    # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' "$URL/"  # 200 — the portal
curl -s -X POST "$URL/api/admin/auth/login" \
     -H 'Content-Type: application/json' \
     -d '{"email":"you@example.com","password":"..."}'
```

Then open `$URL/admin/login` in a browser. A deep link like `$URL/admin/products` should
render rather than 404 — that is `SinglePageAppRouting` forwarding to `index.html`.

## Running the sync

`SELLFOX_SCHEDULE_ENABLED=true` is set above, so the in-process cron runs: stock hourly,
everything nightly at 02:15 America/Chicago. It is safe to leave on if the service ever
scales past one instance — the database permits a single run at a time and turns the rest
away — but it needs an instance alive with CPU, which is what the two flags buy.

**Sellfox allowlists by IP.** Cloud Run's egress is dynamic, so a sync will fail with code
`40005` until the service leaves from a fixed address. Give it one with Direct VPC egress
and a Cloud NAT reserving a static IP, then allowlist that:

```bash
gcloud compute addresses create b2b-egress --region="$REGION"
# attach a VPC connector or Direct VPC egress to the service, route egress through a
# Cloud NAT using that address, then add it in Sellfox.
```

Until that is done the catalogue will not sync. Everything else works.

### If you would rather not pay for a warm instance

Move the trigger out of the process: drop `--min-instances`, set
`SELLFOX_SCHEDULE_ENABLED=false`, and let Cloud Scheduler call the endpoint that already
exists.

```bash
gcloud scheduler jobs create http b2b-sync-stock \
    --schedule='5 * * * *' --time-zone=America/Chicago \
    --uri="$URL/api/admin/sellfox/runs?mode=inventory" --http-method=POST \
    --oidc-service-account-email="$SA"
```

That needs the endpoint to accept a Google-issued token as well as an admin JWT, which is
a change to `WebSecurityConfig` — see the note in the README. Cheaper, and it fires whether
or not anyone has visited the site.

## Rolling back

Every deploy is a revision, and traffic can move without a rebuild:

```bash
gcloud run revisions list --service=b2b-wholesale --region="$REGION"
gcloud run services update-traffic b2b-wholesale --region="$REGION" --to-revisions=<revision>=100
```

The database does not roll back with it. `V1__schema.sql` is a single script today, so a
schema change means a new migration rather than an edit — Flyway refuses a file whose
checksum has moved, which is the behaviour you want.

## What it costs

Roughly $35–70 a month: Cloud SQL `db-g1-small` is most of it, one always-warm Cloud Run
instance with CPU allocated is single digits to twenty, Artifact Registry and Secret
Manager are cents. A GKE Autopilot cluster starts around $75 before it runs anything.
