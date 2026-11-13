package com.lastwave.app.ui.wrapped

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.repository.WrappedPeriod
import com.lastwave.app.data.repository.WrappedReport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.time.Duration

/**
 * Draws every story chapter with the real Compose code (Robolectric native
 * graphics) and writes the frames to `build/wrapped-story/`, so the layouts
 * can be looked at without a device. A chapter's entrance and the backdrop
 * clock are plain numbers, so any moment of the animation can be frozen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class, qualifiers = "w360dp-h760dp-xhdpi")
class WrappedStorySnapshotTest {

    private val out = File("build/wrapped-story").apply { mkdirs() }

    private fun file(name: String) = File("src/main/res/font/$name.ttf")

    private val type by lazy {
        WrappedType(
            sans = FontFamily(
                Font(file("plex_sans_regular"), FontWeight.Normal),
                Font(file("plex_sans_medium"), FontWeight.Medium),
                Font(file("plex_sans_semibold"), FontWeight.SemiBold),
                Font(file("plex_sans_bold"), FontWeight.Bold),
            ),
            mono = FontFamily(
                Font(file("plex_mono_regular"), FontWeight.Normal),
                Font(file("plex_mono_medium"), FontWeight.Medium),
                Font(file("plex_mono_semibold"), FontWeight.SemiBold),
            ),
            nativeMono = Typeface.createFromFile(file("plex_mono_medium")),
        )
    }

    /** Six flat gradient squares standing in for cover art. */
    private val art: (Int) -> String? by lazy {
        val dir = File("build/wrapped-story/art").apply { mkdirs() }
        val colours = listOf(0xFFEF4444, 0xFF3B82F6, 0xFFF59E0B, 0xFF8B5CF6, 0xFF10B981, 0xFFEC4899)
        val paths = colours.mapIndexed { i, c ->
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 240f, 240f, c.toInt(), 0xFF111827.toInt(), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, 240f, 240f, paint)
            val target = File(dir, "a$i.png")
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            target.absolutePath
        }
        return@lazy { slot: Int -> paths.getOrNull(slot) }
    }

    private fun capture(name: String, settleMs: Long = 400, content: @Composable () -> Unit): Bitmap {
        // Frames only run when the test moves the clock. Without this a
        // never-ending animation keeps the looper busy for ever.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            CompositionLocalProvider(LocalWrappedType provides type) {
                Box(Modifier.fillMaxSize().background(WrappedPalette.Zinc950)) { content() }
            }
        }
        var waited = 0L
        while (waited < settleMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(15)
            waited += 50
        }
        // Robolectric leaves the window at whatever size the previous test
        // measured, so the page is laid out at the screen size explicitly.
        val view = activity.window.decorView
        val dm = view.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val full = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        // Robolectric gives the activity a title bar the real app does not
        // have, so shoot the content area only.
        val where = IntArray(2)
        activity.findViewById<View>(android.R.id.content).getLocationInWindow(where)
        val bitmap = Bitmap.createBitmap(full, where[0], where[1], view.width, view.height - where[1])
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
        return bitmap
    }

    /** Pixels that are not the flat page colour: proof that something was drawn. */
    private fun inked(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 8) {
                if (bitmap.getPixel(x, y) != 0xFF09090B.toInt()) count++
            }
        }
        return count
    }

    private fun chapter(name: String, page: StoryPage, report: WrappedReport, index: Int, t: Float, time: Float = 2.4f) {
        val bitmap = capture(name) {
            StoryChapterFrame(
                page = page,
                report = report,
                accent = accentAt(index),
                second = accentAt(index + 2),
                t = { t },
                time = { time },
                sharing = false,
                onShare = {},
                onPlayTop = {},
                onClose = {},
            )
        }
        assertThat(bitmap.height).isIn(1300..1520)
        assertThat(inked(bitmap)).isGreaterThan(400)
    }

    @Test
    fun everyChapterOfAFullYear() {
        val report = WrappedSamples.full(WrappedPeriod.Year(2026), 365, art)
        val pages = storyPagesFor(report)
        assertThat(pages).hasSize(StoryPage.entries.size)
        pages.forEachIndexed { i, page ->
            chapter("year-%02d-%s".format(i + 1, page.name.lowercase()), page, report, i, t = 1f)
        }
    }

    @Test
    fun chaptersCaughtMidEntrance() {
        val report = WrappedSamples.full(WrappedPeriod.Year(2026), 365, art)
        val pages = storyPagesFor(report)
        for (page in listOf(StoryPage.TIME, StoryPage.TOP_ARTISTS, StoryPage.CALENDAR, StoryPage.BADGES)) {
            chapter("mid-${page.name.lowercase()}", page, report, pages.indexOf(page), t = 0.42f, time = 0.7f)
        }
    }

    @Test
    fun firstEveningGetsAShorterStory() {
        val report = WrappedSamples.firstEvening()
        val pages = storyPagesFor(report)
        assertThat(pages).doesNotContain(StoryPage.DISCOVERY)
        assertThat(pages).doesNotContain(StoryPage.TOP_ARTISTS)
        pages.forEachIndexed { i, page ->
            chapter("first-%02d-%s".format(i + 1, page.name.lowercase()), page, report, i, t = 1f)
        }
    }

    @Test
    fun oneMonthCalendar() {
        val report = WrappedSamples.full(WrappedPeriod.Month(2026, 10), 30)
        chapter("month-calendar", StoryPage.CALENDAR, report, 6, t = 1f)
    }

    @Test
    fun wholeStoryWithControls_still() {
        val report = WrappedSamples.full(WrappedPeriod.Year(2026), 365, art)
        val bitmap = capture("story-still") {
            WrappedStory(report, reduceMotion = true, sharing = false, onShare = {}, onPlayTop = {}, onClose = {})
        }
        assertThat(inked(bitmap)).isGreaterThan(400)
    }

    @Test
    fun wholeStoryWithControls_moving() {
        val report = WrappedSamples.full(WrappedPeriod.Year(2026), 365, art)
        // Two seconds of real frames: the entrance, the backdrop clock and the
        // auto-advance timer all run.
        val bitmap = capture("story-moving", settleMs = 2_000) {
            WrappedStory(report, reduceMotion = false, sharing = false, onShare = {}, onPlayTop = {}, onClose = {})
        }
        assertThat(inked(bitmap)).isGreaterThan(400)
    }

    @Test
    fun bubblesNeverOverlap() {
        for (radii in listOf(listOf(1f, 0.78f, 0.65f, 0.61f, 0.43f), listOf(1f, 1f, 1f, 1f, 1f), listOf(1f, 0.42f, 0.42f), listOf(1f, 0.9f))) {
            val centres = packBubbles(radii, gap = 0.07f)
            assertThat(centres).hasSize(radii.size)
            for (i in radii.indices) {
                for (j in i + 1 until radii.size) {
                    val distance = (centres[i] - centres[j]).getDistance()
                    assertThat(distance).isAtLeast(radii[i] + radii[j] + 0.07f - 0.01f)
                }
            }
        }
        assertThat(packBubbles(listOf(1f))).containsExactly(Offset.Zero)
    }

    @Test
    fun dotsAlwaysFitTheGrid() {
        for (minutes in listOf(1L, 14L, 59L, 144L, 145L, 5_999L, 23_254L, 525_600L, 1_400_000L)) {
            val (unit, label) = dotUnit(minutes)
            assertThat(label).isNotEmpty()
            if (minutes <= 144L * 10_080L) assertThat((minutes + unit - 1) / unit).isAtMost(144L)
        }
        assertThat(dotUnit(14).second).isEqualTo("1 MINUTE")
        assertThat(dotUnit(23_254).second).isEqualTo("3 HOURS")
    }
}
