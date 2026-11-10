package app.xshifter.open;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** An original vector illustration, sized to its available bounds; no bitmap assets. */
final class CassetteView extends View {
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int count, selected;

  CassetteView(Context c, int count, int selected) {
    super(c);
    this.count = Math.max(2, count);
    this.selected = selected;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float s = Math.min(getWidth() / 230f, getHeight() / 220f);
    canvas.save();
    canvas.translate((getWidth() - 230 * s) / 2, (getHeight() - 220 * s) / 2);
    canvas.scale(s, s);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(0.6f);
    p.setColor(0xff253337);
    canvas.drawCircle(133, 108, 92, p);
    canvas.drawCircle(133, 108, 70, p);
    canvas.drawLine(20, 108, 230, 108, p);
    canvas.drawLine(133, 8, 133, 212, p);
    p.setColor(0xff0b1011);
    p.setStyle(Paint.Style.FILL);
    canvas.drawOval(30, 183, 224, 212, p);
    for (int i = count - 1; i >= 0; i--) {
      float radius = 31 + 54 * i / Math.max(1, count - 1), cx = 145 - i * 4.7f, cy = 95 + i * 3.8f;
      Path cog = new Path();
      int teeth = 16 + i;
      for (int j = 0; j < teeth * 4; j++) {
        double a = 2 * Math.PI * j / (teeth * 4);
        float r = radius * ((j % 4 == 0 || j % 4 == 3) ? 1.055f : 1f);
        float x = cx + (float) Math.cos(a) * r * .71f, y = cy + (float) Math.sin(a) * r;
        if (j == 0) cog.moveTo(x, y);
        else cog.lineTo(x, y);
      }
      cog.close();
      boolean active = i == selected;
      p.setStyle(Paint.Style.FILL);
      p.setShader(
          new LinearGradient(
              cx - radius,
              cy - radius,
              cx + radius,
              cy + radius,
              active
                  ? new int[] {0xffe1ff9e, Design.LIME, 0xff85ac35}
                  : new int[] {0xff647477, 0xff29383c, 0xff152024},
              null,
              Shader.TileMode.CLAMP));
      canvas.drawPath(cog, p);
      p.setShader(null);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(active ? 1.4f : .8f);
      p.setColor(active ? 0xffedffc9 : 0xff819194);
      canvas.drawPath(cog, p);
      for (int ring = 0; ring < 3; ring++) {
        float r = radius * (.38f + ring * .19f);
        p.setColor(active ? 0xff526f25 : 0xff152023);
        p.setStrokeWidth(ring == 0 ? 3 : 1.2f);
        canvas.drawOval(cx - r * .71f, cy - r, cx + r * .71f, cy + r, p);
      }
      p.setStyle(Paint.Style.FILL);
      p.setColor(active ? 0xff283c13 : 0xff111b1e);
      canvas.drawOval(cx - 9, cy - 13, cx + 9, cy + 13, p);
    }
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(2);
    p.setColor(0xff77898b);
    canvas.drawLine(143, 95, 195, 63, p);
    p.setStyle(Paint.Style.FILL);
    p.setColor(Design.LIME);
    canvas.drawCircle(199, 61, 3, p);
    canvas.restore();
  }
}
