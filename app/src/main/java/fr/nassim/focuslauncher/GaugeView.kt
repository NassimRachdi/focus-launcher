package fr.nassim.focuslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Jauge en demi-cercle. Aucune allocation dans onDraw (important sur RK3188 / 1 Go).
 * Le texte de la valeur est dessiné au centre de l'arc.
 */
class GaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val stroke = dp(10f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        color = 0xFFE5322D.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28f, resources.displayMetrics)
    }
    private val box = RectF()
    private var fraction = 0f
    private var label = "--"

    init {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(R.attr.fLine, tv, true)) track.color = tv.data
        if (context.theme.resolveAttribute(R.attr.fTxt, tv, true)) text.color = tv.data
    }

    fun setColor(c: Int) { arc.color = c; invalidate() }

    /** fraction : 0..1, valueText : texte affiché au centre */
    fun set(fraction: Float, valueText: String) {
        val f = fraction.coerceIn(0f, 1f)
        if (f == this.fraction && valueText == label) return
        this.fraction = f; label = valueText
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w / 2f, h) - stroke
        val cx = w / 2f
        val cy = h - stroke / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 180f, 180f, false, track)
        if (fraction > 0f) c.drawArc(box, 180f, 180f * fraction, false, arc)
        c.drawText(label, cx, cy - stroke * 0.4f, text)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
