package app.xshifter.open;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;

/** Native drawing and typography shared by the four instrument screens. */
final class Design {
  static final int BG = 0xff0d1214,
      SURFACE = 0xff172023,
      RAISED = 0xff212d31,
      LINE = 0xff304045,
      INK = 0xfff4f6ed,
      MUTED = 0xffa5b4b5,
      LIME = 0xffd3fa6a;
  final Typeface body, display;
  final Context context;

  Design(Context context) {
    this.context = context;
    body =
        new Typeface.Builder(context.getAssets(), "fonts/Manrope.ttf")
            .setFontVariationSettings("'wght' 450")
            .build();
    display = Typeface.createFromAsset(context.getAssets(), "fonts/BarlowCondensed-SemiBold.ttf");
  }

  int dp(float n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }

  GradientDrawable panel(int fill, int border, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    if (border != 0) d.setStroke(dp(1), border);
    return d;
  }

  Drawable interactive(int fill, int border, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(0x26d3fa6a), panel(fill, border, radius), null);
  }

  static final class Icon extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int type, color;

    Icon(Context c, int type, int color) {
      super(c);
      this.type = type;
      this.color = color;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.translate((getWidth() - 24f) / 2, (getHeight() - 24f) / 2);
      float scale = Math.min(getWidth(), getHeight()) / 32f;
      canvas.translate(12, 12);
      canvas.scale(scale, scale);
      canvas.translate(-12, -12);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.7f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      if (type == 0) {
        canvas.drawCircle(12, 12, 7.5f, p);
        canvas.drawCircle(12, 12, 2.5f, p);
        for (int i = 0; i < 8; i++) {
          double a = i * Math.PI / 4;
          canvas.drawLine(
              12 + (float) Math.cos(a) * 8,
              12 + (float) Math.sin(a) * 8,
              12 + (float) Math.cos(a) * 10,
              12 + (float) Math.sin(a) * 10,
              p);
        }
      } else if (type == 1) {
        for (int i = 0; i < 3; i++) {
          int x = 5 + i * 7;
          canvas.drawLine(x, 3, x, 21, p);
          p.setStyle(Paint.Style.FILL);
          canvas.drawRoundRect(
              x - 2.5f, (i == 1 ? 7 : 13), x + 2.5f, (i == 1 ? 12 : 18), 1.2f, 1.2f, p);
          p.setStyle(Paint.Style.STROKE);
        }
      } else if (type == 2) {
        canvas.drawRoundRect(4, 3, 20, 21, 2, 2, p);
        canvas.drawLine(8, 8, 16, 8, p);
        canvas.drawLine(8, 12, 16, 12, p);
        canvas.drawLine(8, 16, 13, 16, p);
      } else if (type == 3) {
        canvas.drawRoundRect(7, 2, 17, 22, 2, 2, p);
        canvas.drawLine(10, 18, 14, 18, p);
        canvas.drawCircle(12, 7, 1.5f, p);
      } else {
        p.setStrokeWidth(3f);
        canvas.drawLine(5, 4, 19, 20, p);
        canvas.drawLine(19, 4, 5, 20, p);
      }
      canvas.restore();
    }
  }
}
