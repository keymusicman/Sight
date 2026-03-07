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

This repository now includes a lightweight web server at `:web-server` that exposes:
- `POST /api/layout` to compute layout from an `app-graph.json` payload
- a browser UI at `http://localhost:8080/` for loading and rendering graphs

Run it from terminal:

```shell
./gradlew :web-server:run
```

Then open `http://localhost:8080/` and either:
- upload or paste `app-graph.json` (optionally adding screenshot files manually), or
- upload a ZIP archive containing `app-graph.json` at root plus a screenshots directory referenced by `screenshot_location`.

Desktop app now also includes a **Prepare ZIP for Web** button that creates this archive format.
