// app/src/main/java/com/example/myapplication/ImageSaver.java

package com.example.myapplication;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;

public class ImageSaver {
    private static final String TAG = "ImageSaver";
    private static final String FOLDER_NAME = "sipotion";

    private final ContentResolver contentResolver;
    private final Context context;

    @Inject
    public ImageSaver(@ApplicationContext Context context) {
        this.context = context;
        this.contentResolver = context.getContentResolver();
    }

    public void saveBitmap(Bitmap bitmap, long timestamp, String posture, float confidence) {
        String confidenceFormatted = String.format(Locale.US, "%.2f", confidence);
        String fileName = String.format("sipotion_%d_%s_%s.jpg", timestamp, posture, confidenceFormatted);

        // Gunakan MediaStore untuk Android 10 (Q) ke atas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveBitmapWithMediaStore(bitmap, fileName);
        } else {
            // Gunakan metode file biasa untuk versi lebih lama (memerlukan izin)
            saveBitmapWithLegacyMethod(bitmap, fileName);
        }
    }

    private void saveBitmapWithMediaStore(Bitmap bitmap, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        // Menempatkan gambar di dalam folder DCIM/sipotion
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + File.separator + FOLDER_NAME);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri imageUri = contentResolver.insert(collection, values);

        if (imageUri != null) {
            try (OutputStream outputStream = contentResolver.openOutputStream(imageUri)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                Log.i(TAG, "Gambar berhasil disimpan ke Galeri (MediaStore): " + imageUri);
            } catch (IOException e) {
                Log.e(TAG, "Gagal menyimpan bitmap dengan MediaStore", e);
            } finally {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                contentResolver.update(imageUri, values, null, null);
            }
        }
    }

    private void saveBitmapWithLegacyMethod(Bitmap bitmap, String fileName) {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER_NAME);
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                Log.e(TAG, "Gagal membuat direktori: " + directory.getAbsolutePath());
                return;
            }
        }

        File file = new File(directory, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            Log.i(TAG, "Gambar berhasil disimpan di (Legacy): " + file.getAbsolutePath());
            // Beritahu galeri untuk scan file ini
            // MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
        } catch (IOException e) {
            Log.e(TAG, "Error saat menyimpan gambar (Legacy)", e);
        }
    }
}