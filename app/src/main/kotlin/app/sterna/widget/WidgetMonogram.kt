package app.sterna.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import app.sterna.R
import app.sterna.ui.components.ToneRamp

/**
 * The badge of a widget row, as a `Bitmap` — the only way a `RemoteViews` can carry a coloured
 * initial, since there is no drawable to tint into a letter and no composition out here.
 *
 * ⛔ NO TINT RULE AND NO KEY RULE LIVE HERE. Both were cut out into [widgetMonogramArgb],
 * [widgetMonogramLetterArgb] and [widgetMonogramKey] — plain functions, no Android type — because
 * this object cannot be touched from the JVM suite at all: loading it runs the initialiser of
 * [cache], an `android.util.LruCache`. What is left below is drawing, and drawing is a bench claim.
 *
 * ⚠ THE BITMAP HANDED BACK IS SHARED AND MUTABLE. It is the cache's own object, and the same
 * instance is handed to every row that resolves to the same letter, colour and side. ⛔ Do not draw
 * on it, do not recycle it: the next row would be served whatever was scribbled on it, and a
 * recycled bitmap thrown at `setImageViewBitmap` takes the launcher's binding with it.
 */
internal object WidgetMonogram {

    /**
     * The disc for [monogram], painted in the tones handed in — cached, then handed out as is.
     *
     * ⛔ THE CACHE IS NOT AN OPTIMISATION HERE. `getViewAt` builds a fresh `RemoteViews` on every
     * call, HOW MANY calls is the launcher's `ListView` decision and not this app's, the list runs
     * to [RecentMailWidgetDraw.ROW_LIMIT] rows, and all of it happens on a binder thread the
     * launcher is waiting on. Uncached, one flick of a home screen re-renders up to 25 discs.
     */
    fun badge(context: Context, monogram: RecentRowMonogram, ramps: List<ToneRamp>): Bitmap {
        val side = context.resources.getDimensionPixelSize(R.dimen.widget_recent_monogram)
        val argb = widgetMonogramArgb(monogram, ramps)
        val key = widgetMonogramKey(monogram.initial, argb, side)
        cache.get(key)?.let { return it }
        return draw(monogram.initial, argb, side).also { cache.put(key, it) }
    }

    /**
     * A filled circle with the initial centred on it. ⚠ The baseline is computed from the FONT
     * METRICS and not from half the height: a glyph's ink sits above its baseline, so centring on
     * `side / 2` alone leaves every letter floating high by roughly a third of its height.
     */
    private fun draw(initial: String, argb: Int, side: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = side / 2f
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }
        canvas.drawCircle(centre, centre, centre, disc)
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = widgetMonogramLetterArgb(argb)
            textSize = side * LETTER_SCALE
            textAlign = Paint.Align.CENTER
        }
        val metrics = letter.fontMetrics
        canvas.drawText(initial, centre, centre - (metrics.ascent + metrics.descent) / 2f, letter)
        return bitmap
    }

    /**
     * The letter's height as a fraction of the disc — the ratio the app's own badge draws at, a
     * `titleMedium` (16 sp) inside 40 dp. The widget's disc is smaller, and the ratio is what makes
     * it read as the same badge rather than as a different one.
     */
    private const val LETTER_SCALE = 0.4f

    /**
     * ⛔ BOUNDED IN BYTES, NOT IN ENTRIES. A disc's side comes from the resources, so an xxxhdpi
     * screen draws one four times the width of an mdpi one — sixteen times the memory for the same
     * count. A cap of "n badges" therefore means a different amount of memory on every phone, and
     * the biggest screens would be the ones holding the most.
     *
     * ⭐ NOTHING INVALIDATES THIS CACHE, and nothing has to: the resolved colour is part of the key
     * ([widgetMonogramKey]), so a theme change does not overwrite an entry, it simply stops asking
     * for it, and the LRU drops it when the room is wanted.
     */
    private const val CACHE_KB = 512

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
}
