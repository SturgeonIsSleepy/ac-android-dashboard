package cn.acflip.dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseArray;
import java.io.IOException;
import java.io.InputStream;

final class HudArt {
    private final Bitmap icons, panel, digits;
    private final Rect[] iconCells = new Rect[9], glyphs = new Rect[14];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final SparseArray<PorterDuffColorFilter> colors = new SparseArray<>();
    private final String alphabet = "0123456789:.+-";
    HudArt(Context context) {
        icons = load(context,"icons.png"); panel = load(context,"panel.png"); digits = load(context,"digits.png");
        for (int i = 0; i < 9; i++) iconCells[i] = new Rect(i%3*icons.getWidth()/3,i/3*icons.getHeight()/3,
                (i%3+1)*icons.getWidth()/3,(i/3+1)*icons.getHeight()/3);
        int width = digits.getWidth(), height = digits.getHeight(); int[] pixels = new int[width*height];
        digits.getPixels(pixels,0,width,0,0,width,height);
        int[] top = {height/2,height}, bottom = {0,height/2};
        for (int i = 0; i < 14; i++) {
            int row = i/7, left = width, right = 0;
            for (int y = row*height/2; y < (row+1)*height/2; y++)
                for (int x = i%7*width/7; x < (i%7+1)*width/7; x++)
                    if ((pixels[y*width+x]>>>24) > 128) {
                        left = Math.min(left,x); right = Math.max(right,x+1);
                        if (i < 10) { top[row] = Math.min(top[row],y); bottom[row] = Math.max(bottom[row],y+1); }
                    }
            glyphs[i] = new Rect(left,row*height/2,right,(row+1)*height/2);
        }
        for (int i = 0; i < 14; i++) { glyphs[i].top = top[i/7]; glyphs[i].bottom = bottom[i/7]; }
    }
    private Bitmap load(Context context, String name) {
        try (InputStream source = context.getAssets().open("hud08/"+name)) { return BitmapFactory.decodeStream(source); }
        catch (IOException e) { throw new IllegalStateException("Missing generated HUD asset: "+name,e); }
    }
    private void color(int value) {
        PorterDuffColorFilter filter = colors.get(value);
        if (filter == null) { filter = new PorterDuffColorFilter(value,PorterDuff.Mode.SRC_IN); colors.put(value,filter); }
        paint.setColorFilter(filter);
    }
    void icon(Canvas canvas, int cell, float x, float y, float size, int tint) {
        color(tint); canvas.drawBitmap(icons,iconCells[cell],new RectF(x,y,x+size,y+size),paint);
    }
    void panel(Canvas canvas) {
        paint.setColorFilter(null); canvas.save(); canvas.clipRect(88,36,594,348);
        canvas.drawBitmap(panel,null,new RectF(0,0,682,422),paint); canvas.restore();
    }
    void number(Canvas canvas, String value, float centerX, float baseline, float size, float maximumWidth, int tint) {
        value = value.replace('—','-'); float width = 0;
        for (int i = 0; i < value.length(); i++) width += advance(value.charAt(i))*size;
        if (width > maximumWidth) { size *= maximumWidth/width; width = maximumWidth; }
        float x = centerX-width/2; color(tint);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); int index = alphabet.indexOf(c); float cell = advance(c)*size;
            if (index >= 0) {
                Rect source = glyphs[index];
                float ink = Math.min(cell*.94f,size*source.width()/source.height());
                float left = x+(cell-ink)/2;
                canvas.drawBitmap(digits,source,new RectF(left,baseline-size,left+ink,baseline),paint);
            }
            x += cell;
        }
    }
    private float advance(char glyph) { return glyph == ':' ? .25f : glyph == '.' ? .29f : glyph == '+' ? .77f : glyph == '-' ? .6f : .78f; }
}
