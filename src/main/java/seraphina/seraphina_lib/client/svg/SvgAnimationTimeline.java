package seraphina.seraphina_lib.client.svg;

import org.w3c.dom.Document;

import java.awt.Color;
import java.util.List;

record SvgAnimationTimeline(List<SvgAnimation> animations, boolean repeatForever, float endSeconds) {
    static SvgAnimationTimeline empty() {
        return new SvgAnimationTimeline(List.of(), false, 0.0F);
    }

    static SvgAnimationTimeline collect(Document document, boolean paintAnimationsEnabled, Color disabledPaintAnimationColor) {
        List<SvgAnimation> animations = SvgAnimationCollector.collect(document, paintAnimationsEnabled, disabledPaintAnimationColor);
        return new SvgAnimationTimeline(List.copyOf(animations), repeatsForever(animations), endSeconds(animations));
    }

    boolean animated() {
        return !animations.isEmpty();
    }

    void apply(float timeSeconds) {
        for (SvgAnimation animation : animations) {
            animation.restoreBaseValue();
        }

        for (SvgAnimation animation : animations) {
            animation.apply(timeSeconds);
        }
    }

    private static boolean repeatsForever(List<SvgAnimation> animations) {
        return animations.stream().anyMatch(SvgAnimation::repeatForever);
    }

    private static float endSeconds(List<SvgAnimation> animations) {
        float endSeconds = 0.0F;
        for (SvgAnimation animation : animations) {
            endSeconds = Math.max(endSeconds, animation.begin() + animation.duration());
        }
        return endSeconds;
    }
}
