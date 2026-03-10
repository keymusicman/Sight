This is a Kotlin Multiplatform project targeting Desktop (JVM).

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
    folder is the appropriate location.

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…

### Run Web Graph Viewer (Backend + Browser UI)

The `:web-server` module serves a browser UI at `http://localhost:8080/` and exposes:
- `GET /health`
- `POST /api/upload-graph` (multipart: `graphName` + ZIP `archive`)
- `GET /api/graphs`
- `GET /api/layout/{graphId}`
- `DELETE /api/graph/{graphId}`

Run locally:

```shell
./gradlew :web-server:run
```

Upload ZIP requirements:
- archive must include `app-graph.json` at root
- archive must include `screenshots/` folder
- upload rejects duplicate graph IDs with `409 Conflict`
- ZIP extraction is streamed and zip-slip protected

### Docker

Build and run:

```shell
docker build -t appflower-web:latest .
docker run --rm -p 8080:8080 -e GCS_BUCKET=your-gcs-bucket appflower-web:latest
```

### Google Cloud deployment (Cloud Run + GCS + CDN)

Set variables:

```shell
PROJECT_ID="<your-project-id>"
REGION="us-central1"
SERVICE="appflower-web"
BUCKET="your-gcs-bucket"
SA="appflower-web-sa"
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
gcloud iam service-accounts create "$SA" --display-name="AppFlower Web Service"
gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" \
  --member="serviceAccount:$SA@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"
```

Build and deploy Cloud Run (public):

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

Cloud CDN in front of bucket (backend bucket + HTTPS load balancer):

```shell
gcloud compute backend-buckets create appflower-gcs-backend \
  --gcs-bucket-name="$BUCKET" \
  --enable-cdn
gcloud compute url-maps create appflower-map --default-backend-bucket=appflower-gcs-backend
gcloud compute ssl-certificates create appflower-cert \
  --domains="$DOMAIN" \
  --global
gcloud compute target-https-proxies create appflower-https-proxy \
  --url-map=appflower-map \
  --ssl-certificates=appflower-cert
gcloud compute forwarding-rules create appflower-https-rule \
  --global \
  --target-https-proxy=appflower-https-proxy \
  --ports=443
```

Then map DNS `A/AAAA` records to the global load balancer IP and verify certificate status:

```shell
gcloud compute ssl-certificates describe appflower-cert --global
```
