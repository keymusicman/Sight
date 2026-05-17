# Render Experiments

| Experiment | Avg/image | inflate+task | executeCallbacks | render() | PNG write | Total time |   Size | Chart                           |
|------------|----------:|-------------:|-----------------:|---------:|----------:|-----------:|-------:|---------------------------------|
| Baseline (2026-05-15) | 282ms | 133ms | 14ms | 14ms | 120ms | 67 521ms | 30.4MB | [chart](render-timings-chart.md) |
| PNG compressionQuality=0.0 (2026-05-16) | 327ms | 136ms | 11ms | 15ms | 166ms | 79 060ms |   29MB | |
| PNG compressionQuality=1.0 (2026-05-16) | 316ms | 120ms | 12ms | 15ms | 169ms | 75 626ms | 2.14GB | |
| Module resolution cache (2026-05-16) | 199ms | 70ms | 2ms | 11ms | 115ms | 46 574ms |      — | |
| Async PNG write (2026-05-16) | 401ms | 272ms | 21ms | 17ms | 127ms | 90 657ms |      — | |
| FQN resolution cache — cold (2026-05-17) | 278ms | 122ms | 12ms | 14ms | 131ms | 62 733ms |      — | |
| FQN resolution cache — warm (2026-05-17) | 175ms | 30ms | 2ms | 11ms | 132ms | 39 610ms |      — | |
| Plugin settings PNG — warm (2026-05-17) | 166ms | 31ms | 3ms | 11ms | 120ms | 37 710ms | 33.3MB | |
| Plugin settings JPEG 85% — cold (2026-05-17) | 193ms | 141ms | 2ms | 12ms | 37ms | 43 734ms | 22.2MB | |
| Plugin settings JPEG 85% — warm (2026-05-17) | 395ms | 340ms | 2ms | 14ms | 37ms | 89 324ms | 22.2MB | |
| Plugin settings JPEG 85% — cold #2 (2026-05-17) | 161ms | 103ms | 9ms | 11ms | 36ms | 36 386ms | 22.2MB | |
| Plugin settings JPEG 85% — warm #2 (2026-05-17) | 204ms | 146ms | 3ms | 13ms | 40ms | 46 194ms | 22.2MB | |
| Plugin settings JPEG 85% — warm #3 (2026-05-17) | 349ms | 291ms | 4ms | 14ms | 38ms | 78 908ms | 22.2MB | |
| JPEG 85% + flush fix — warm (2026-05-17) | 97ms | 44ms | 3ms | 11ms | 38ms | 22 130ms | 22.2MB | |
| JPEG 85% + flush fix — warm #2 (2026-05-17) | 307ms | 251ms | 4ms | 13ms | 38ms | 69 561ms | 22.2MB | |

## PNG compression

```kotlin
val pngWriter = ImageIO.getImageWritersByFormatName("PNG").next()
ImageIO.createImageOutputStream(outFile).use { ios ->
    pngWriter.output = ios
    val param = pngWriter.defaultWriteParam.apply {
        if (canWriteCompressed()) {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 1.0f // 0.0f
        }
    }
    pngWriter.write(null, IIOImage(outputImage, null, null), param)
    pngWriter.dispose()
}
```