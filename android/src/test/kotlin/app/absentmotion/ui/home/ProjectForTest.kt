package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectForTest {
    private fun size(w: Int, h: Int, resolution: Int = 1080) = projectFor("t", w to h, resolution, 30).let { it.width to it.height }

    @Test fun portraitPhoneVideoKeepsItsShapeAtTheChosenResolution() = assertEquals(1080 to 1920, size(2160, 3840))

    @Test fun landscapeVideoKeepsItsShape() = assertEquals(1920 to 1080, size(3840, 2160))

    @Test fun smallFilesAreNotScaledUp() = assertEquals(720 to 1280, size(720, 1280))

    @Test fun oddSidesBecomeEven() = assertEquals(1080 to 1440, size(1081, 1441))

    @Test fun unknownShapeFallsBackToPortrait() = projectFor("t", null, 1080, 30).let { assertEquals(1080 to 1920, it.width to it.height) }

    @Test fun veryLongPicturesStayWithinTheLimit() = size(1000, 20000).let { (w, h) -> assertEquals(MAX_SIDE, h); assertEquals(true, w >= MIN_SIDE) }
}
