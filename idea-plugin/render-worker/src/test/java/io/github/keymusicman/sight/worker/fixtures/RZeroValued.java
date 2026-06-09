package io.github.keymusicman.sight.worker.fixtures;

/**
 * Faithfully reproduces the real AGP-generated app R class that caused the blank-render bug:
 * {@code public static int} (NON-final) fields with NO initializer, so javac emits NO
 * {@code <clinit>} and the runtime value is the default {@code 0}. (Real AGP jars additionally
 * carry the true id in a {@code ConstantValue} attribute, but the JVM ignores that for non-final
 * fields — verified via {@code javap -v} on example-app's R.jars — so {@code getstatic}/
 * {@code Field.getInt} still yield {@code 0}.)
 *
 * Unlike {@link RNonFinal} (whose {@code = 0x...} initializers DO produce a {@code <clinit>} and a
 * non-zero runtime value), this fixture exercises the registry's synthesize-and-{@code setInt} path.
 */
public final class RZeroValued {
    public static final class drawable {
        public static int ic_zero;       // no initializer → runtime value 0, no <clinit>
        public static int ic_also_zero;  // ditto
    }
    public static final class string {
        public static int label_zero;
    }
    public static final class styleable {
        // Non-final int[] field — must still be ignored by the scanner, not materialized.
        public static int[] SomeView = { 0, 1 };
    }
}
