package seraphina.seraphina_mod.client.svg;

final class SvgAnimationFrameKey {
    static final long FINAL = Long.MAX_VALUE;

    private static final long FRAME_INTERVAL_MILLIS = 50L;

    private SvgAnimationFrameKey() {
    }

    static long forTime(float timeSeconds, boolean finalFrame) {
        if (finalFrame) {
            return FINAL;
        }
        return Math.max(0L, (long) Math.floor(timeSeconds * 1000.0F / FRAME_INTERVAL_MILLIS));
    }

    static String frameName(long frameKey) {
        return frameKey == FINAL ? "final" : Long.toString(frameKey);
    }
}
