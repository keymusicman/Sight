# Render Experiments

| Experiment | Avg/image | inflate+task | executeCallbacks | render() | PNG write | Total time | Size | Chart                           |
|------------|----------:|-------------:|-----------------:|---------:|----------:|-----------:|-------:|---------------------------------|
| Baseline (2026-05-15) | 282ms | 133ms | 14ms | 14ms | 120ms | 67 521ms | 30.4MB | [chart](render-timings-chart.md) |
| PNG compressionQuality=0.0 (2026-05-16) | 327ms | 136ms | 11ms | 15ms | 166ms | 79 060ms | 29MB | |
| PNG compressionQuality=1.0 (2026-05-16) | 316ms | 120ms | 12ms | 15ms | 169ms | 75 626ms | 2.14GB | |
| Module resolution cache (2026-05-16) | 199ms | 70ms | 2ms | 11ms | 115ms | 46 574ms | — | |
| Async PNG write (2026-05-16) | 401ms | 272ms | 21ms | 17ms | 127ms | 90 657ms | — | |

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