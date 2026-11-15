package fr.nassim.focuslauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View

/**
 * Formes penchées de l'accueil « Diagonale ». Aplats uniquement, aucune allocation pendant le dessin.
 */

/** Fond en parallélogramme (bords gauche et droit inclinés vers la droite en haut) pour tuiles et boutons. */
class SlantDrawable(color: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = b.height() * SLANT
        path.rewind()
        path.moveTo(b.left + s, b.top.toFloat())
        path.lineTo(b.right.toFloat(), b.top.toFloat())
        path.lineTo(b.right - s, b.bottom.toFloat())
        path.lineTo(b.left.toFloat(), b.bottom.toFloat())
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Décalage horizontal par unité de hauteur : environ 10 degrés. */
        const val SLANT = 0.176f
    }
}

/**
 * Partie gauche de l'accueil : la pochette, recadrée au centre et coupée en biais à droite, avec le trait
 * d'accent le long de la coupe. Sans pochette : fond uni et motif de disque.
 */
class SlantCoverView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val src = Rect()
    private val dst = Rect()
    private var bmp: Bitmap? = null
    private var bg = 0xFF050505.toInt()
    private var empty = 0xFF15130E.toInt()
    private var accent = 0xFFD4AF62.toInt()

    /** [bg] : fond de l'écran (masque la coupe), [empty] : fond sans pochette, [accent] : trait et motif. */
    fun setColors(bg: Int, empty: Int, accent: Int) {
        this.bg = bg; this.empty = empty; this.accent = accent
        invalidate()
    }

    fun setBitmap(b: Bitmap?) {
        if (b === bmp) return
        bmp = b
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val b = bmp
        paint.style = Paint.Style.FILL
        if (b != null && !b.isRecycled && b.width > 0 && b.height > 0) {
            // Recadrage au centre : l'image remplit toute la zone sans être déformée.
            val bw = b.width
            val bh = b.height
            if (bw.toLong() * h > w.toLong() * bh) {
                val cw = (bh.toLong() * w / h).toInt()
                src.set((bw - cw) / 2, 0, (bw + cw) / 2, bh)
            } else {
                val ch = (bw.toLong() * h / w).toInt()
                src.set(0, (bh - ch) / 2, bw, (bh + ch) / 2)
            }
            dst.set(0, 0, w, h)
            paint.color = 0xFFFFFFFF.toInt()
            c.drawBitmap(b, src, dst, paint)
        } else {
            paint.color = empty
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.color = accent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            val cx = w * 0.42f
            val cy = h * 0.5f
            c.drawCircle(cx, cy, h * 0.19f, paint)
            c.drawCircle(cx, cy, h * 0.125f, paint)
            c.drawCircle(cx, cy, h * 0.025f, paint)
        }
        // Coupe en biais : un triangle de la couleur du fond recouvre le coin bas droit.
        val cut = w * CUT
        path.rewind()
        path.moveTo(w.toFloat(), 0f)
        path.lineTo(w.toFloat(), h.toFloat())
        path.lineTo(w - cut, h.toFloat())
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = accent
        c.drawLine(w - 1.5f, 0f, w - cut - 1.5f, h.toFloat(), paint)
    }

    companion object {
        /** Part de la largeur mangée par la coupe en bas de la vue. */
        const val CUT = 0.22f
    }
}
