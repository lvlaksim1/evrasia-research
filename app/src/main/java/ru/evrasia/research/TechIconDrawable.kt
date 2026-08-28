package ru.evrasia.research

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

class TechIconDrawable(private val kind: Kind, private val color: Int) : Drawable() {
    enum class Kind { MENU, NAVIGATE, NETWORK, BACK }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = this@TechIconDrawable.color }
    override fun draw(c: Canvas) {
        val b=bounds; val s=minOf(b.width(),b.height()).toFloat(); val x=b.exactCenterX(); val y=b.exactCenterY(); val r=s*.27f; p.strokeWidth=s*.075f
        when(kind){
            Kind.MENU -> { for(i in -1..1)c.drawLine(x-r,y+i*r*.72f,x+r,y+i*r*.72f,p) }
            Kind.NAVIGATE -> { val q=Path(); q.moveTo(x-r*.9f,y-r*.72f);q.lineTo(x+r,y);q.lineTo(x-r*.9f,y+r*.72f);q.lineTo(x-r*.38f,y);q.close();c.drawPath(q,p);c.drawLine(x-r*.35f,y,x+r*.72f,y,p) }
            Kind.NETWORK -> { val rr=r*.27f; val pts=arrayOf(floatArrayOf(x,y-r),floatArrayOf(x-r*.88f,y+r*.65f),floatArrayOf(x+r*.88f,y+r*.65f));c.drawLine(pts[0][0],pts[0][1],pts[1][0],pts[1][1],p);c.drawLine(pts[1][0],pts[1][1],pts[2][0],pts[2][1],p);c.drawLine(pts[2][0],pts[2][1],pts[0][0],pts[0][1],p);pts.forEach{c.drawCircle(it[0],it[1],rr,p)} }
            Kind.BACK -> { c.drawLine(x+r,y,x-r*.75f,y,p);c.drawLine(x-r*.75f,y,x-r*.1f,y-r*.65f,p);c.drawLine(x-r*.75f,y,x-r*.1f,y+r*.65f,p) }
        }
    }
    override fun setAlpha(alpha:Int){p.alpha=alpha}
    override fun setColorFilter(cf:ColorFilter?){p.colorFilter=cf}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
