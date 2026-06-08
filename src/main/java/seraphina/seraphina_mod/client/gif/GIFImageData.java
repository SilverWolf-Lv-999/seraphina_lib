package seraphina.seraphina_mod.client.gif;

import java.util.List;

record GIFImageData(int width, int height, List<GIFFrame> frames, int totalDurationMillis) {
}
