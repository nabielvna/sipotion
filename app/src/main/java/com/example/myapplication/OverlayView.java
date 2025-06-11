package com.example.myapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.LinkedList;
import java.util.List;

public class OverlayView extends View {

    private final List<Box> boundingBoxes = new LinkedList<>();
    private final Paint boxPaint;
    private final Paint textPaint;

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // Atur kuas untuk menggambar kotak
        boxPaint = new Paint();
        boxPaint.setColor(ContextCompat.getColor(context, R.color.colorBackgroundRed));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);

        // Atur kuas untuk menggambar teks
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(50f);
        textPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Menghapus semua kotak yang ada di layar.
     */
    public void clear() {
        synchronized (boundingBoxes) {
            boundingBoxes.clear();
        }
        // Memicu onDraw untuk membersihkan tampilan
        postInvalidate();
    }

    /**
     * Menambahkan kotak baru untuk digambar.
     * @param box Objek Box yang berisi koordinat dan teks.
     */
    public void add(Box box) {
        synchronized (boundingBoxes) {
            boundingBoxes.add(box);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Gambar setiap kotak yang ada di dalam daftar
        synchronized (boundingBoxes) {
            for (Box box : boundingBoxes) {
                // Gambar kotaknya
                canvas.drawRect(box.rect, boxPaint);
                // Gambar teks (label kelas) di atas kotaknya
                canvas.drawText(box.label, box.rect.left, box.rect.top - 10, textPaint);
            }
        }
    }

    /**
     * Data class untuk menyimpan informasi satu bounding box.
     */
    public static class Box {
        public final RectF rect;
        public final String label;

        public Box(RectF rect, String label) {
            this.rect = rect;
            this.label = label;
        }
    }
}
