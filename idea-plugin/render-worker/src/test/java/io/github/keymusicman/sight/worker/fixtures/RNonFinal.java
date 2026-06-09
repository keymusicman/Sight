package io.github.keymusicman.sight.worker.fixtures;

/**
 * Mimics a real AGP-generated R class whose resource id fields are {@code public static int}
 * (NON-final). This is what AGP emits for application and library modules under
 * non-transitive / non-final R ids — verified via {@code javap} on the example-app R.jars,
 * which had 0 final and 130 non-final int fields. The registry must read these.
 */
public final class RNonFinal {
    public static final class drawable {
        public static int ic_avatar = 0x7f080010;
        public static int ic_close = 0x7f080011;
    }
    public static final class string {
        public static int title = 0x7f0f0010;
    }
    public static final class styleable {
        // Non-final int[] field — must still be ignored by the scanner.
        public static int[] SomeView = { 0, 1 };
    }
}
