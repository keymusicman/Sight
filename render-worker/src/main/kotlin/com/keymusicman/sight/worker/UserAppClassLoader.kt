package com.keymusicman.sight.worker

import java.io.File
import java.net.URLClassLoader

/**
 * Builds a [URLClassLoader] over the user's runtime classpath (the user's compiled
 * Compose app + its dependency JARs + the SDK platform `android.jar`).
 *
 * Parent should be the worker's own classloader so Layoutlib classes
 * (`ComposeViewAdapter`, layoutlib-api, etc.) are visible during view inflation.
 */
fun newUserClassLoader(paths: List<String>, parent: ClassLoader): URLClassLoader =
    URLClassLoader(
        paths.map { File(it).toURI().toURL() }.toTypedArray(),
        parent,
    )
