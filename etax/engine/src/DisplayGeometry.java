import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import org.json.JSONObject;

/** Logical display pixels are the shared coordinate space for screenshots, nodes and input. */
final class DisplayGeometry {
    final int width, height, rotation;
    DisplayGeometry(int width, int height, int rotation) {
        this.width = width; this.height = height; this.rotation = rotation & 3;
    }
    JSONObject json() throws Exception {
        return new JSONObject().put("width", width).put("height", height).put("rotation", rotation)
                .put("coordinateSpace", "display");
    }
    boolean matches(JSONObject expected) {
        return expected != null && expected.optInt("width") == width && expected.optInt("height") == height
                && expected.optInt("rotation", -1) == rotation;
    }
    boolean contains(double x, double y) {
        return !Double.isNaN(x) && !Double.isInfinite(x) && !Double.isNaN(y) && !Double.isInfinite(y)
                && x >= 0 && y >= 0 && x < width && y < height;
    }
    static boolean landscape(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    }
    static boolean portrait(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }
    static int[] orient(int width, int height, String requested, int manifestOrientation) {
        boolean wide = "landscape".equals(requested) || ("auto".equals(requested) && landscape(manifestOrientation));
        boolean tall = "portrait".equals(requested) || ("auto".equals(requested) && portrait(manifestOrientation));
        return (wide && width < height) || (tall && width > height)
                ? new int[]{height, width} : new int[]{width, height};
    }
    /** Invert the virtual display's ROTATES_WITH_CONTENT projection; never stretch its pixels. */
    Bitmap logicalBitmap(Bitmap physical) {
        int logicalW = (rotation & 1) != 0 ? physical.getHeight() : physical.getWidth();
        int logicalH = (rotation & 1) != 0 ? physical.getWidth() : physical.getHeight();
        if (logicalW != width || logicalH != height) throw new IllegalStateException("CAPTURE_GEOMETRY_MISMATCH");
        if (rotation == 0) return physical;
        Matrix inverse = new Matrix();
        inverse.postRotate(-90f * rotation);
        return Bitmap.createBitmap(physical, 0, 0, physical.getWidth(), physical.getHeight(), inverse, true);
    }
}
