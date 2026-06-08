# Sight

Visualize your Android app's navigation graph from annotations on your `@Preview` composables.

## Modules

| Module | Description |
|--------|-------------|
| `graph-annotations` | `@AppFlowGraph`, `@AppFlowScreen`, `@AppFlowTransition` — apply these in your Android project |
| `graph-processor` | KSP processor that reads the annotations and writes `build/graph/app-graph-fragment.json` |
| `graph-renderer` | Layout algorithm + data models. Pure JVM, no UI dependency |
| `graph-ui` | Interactive Compose canvas — pan/zoom, hover highlighting, screenshot carousel |
| `idea-plugin` | IntelliJ/Android Studio tool window |
| `web-server` | Ktor server + browser UI for sharing/CI |
| `sample-android` | Minimal Android showcase (standalone Gradle project in `sample-android/`) |

## Quick start (sample)

```shell
cd sample-android
./gradlew :app:kspDebugKotlin
# → build/graph/app-graph-fragment.json
```

## Web server

```shell
./gradlew :web-server:run
# → http://localhost:8080
```

### Docker

```shell
docker build -t sight-web:latest .
docker run --rm -p 8080:8080 -e GCS_BUCKET=your-gcs-bucket sight-web:latest
```

### Google Cloud deployment (Cloud Run + GCS + CDN)

Set variables:

```shell
PROJECT_ID="<your-project-id>"
REGION="us-central1"
SERVICE="sight-web"
BUCKET="your-gcs-bucket"
SA="sight-web-sa"
DOMAIN="graph.example.com"
```

Create bucket and CORS:

```shell
gcloud config set project "$PROJECT_ID"
gcloud storage buckets create "gs://$BUCKET" --location="$REGION" --uniform-bucket-level-access
gcloud storage buckets update "gs://$BUCKET" --cors-file=<(cat <<'JSON'
[
  {
    "origin": ["*"],
    "method": ["GET", "POST", "DELETE", "OPTIONS"],
    "responseHeader": ["Content-Type"],
    "maxAgeSeconds": 3600
  }
]
JSON
)
```

Create service account and grant bucket access:

```shell
gcloud iam service-accounts create "$SA" --display-name="Sight Web Service"
gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" \
  --member="serviceAccount:$SA@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"
```

Build and deploy Cloud Run:

```shell
gcloud builds submit --tag "gcr.io/$PROJECT_ID/$SERVICE:latest"
gcloud run deploy "$SERVICE" \
  --image "gcr.io/$PROJECT_ID/$SERVICE:latest" \
  --region "$REGION" \
  --platform managed \
  --allow-unauthenticated \
  --service-account "$SA@$PROJECT_ID.iam.gserviceaccount.com" \
  --set-env-vars "GCS_BUCKET=$BUCKET"
```

Cloud CDN in front of bucket:

```shell
gcloud compute backend-buckets create sight-gcs-backend \
  --gcs-bucket-name="$BUCKET" \
  --enable-cdn
gcloud compute url-maps create sight-map --default-backend-bucket=sight-gcs-backend
gcloud compute ssl-certificates create sight-cert \
  --domains="$DOMAIN" \
  --global
gcloud compute target-https-proxies create sight-https-proxy \
  --url-map=sight-map \
  --ssl-certificates=sight-cert
gcloud compute forwarding-rules create sight-https-rule \
  --global \
  --target-https-proxy=sight-https-proxy \
  --ports=443
```

Then map DNS `A/AAAA` records to the global load balancer IP and verify certificate status:

```shell
gcloud compute ssl-certificates describe sight-cert --global
```
