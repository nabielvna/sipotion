package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.exifinterface.media.ExifInterface;

import com.example.myapplication.data.UiState;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SITTING_POSTURE_ACT";
    private static final long ANALYSIS_INTERVAL_MS = 800;
    private static final int TARGET_IMAGE_WIDTH = 640; // Target lebar untuk analisis Roboflow
    private static final int TARGET_IMAGE_HEIGHT = 480; // Target tinggi untuk analisis Roboflow

    private PreviewView previewView;
    private TextView resultTextView;
    private Button toggleAnalysisButton;
    private ImageButton flipCameraButton;
    private OverlayView overlayView;
    private SeekBar zoomSlider;
    private ImageButton selectImageButton;

    private MainViewModel viewModel;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageAnalysis imageAnalysis;
    private CameraSelector cameraSelector;
    private long lastAnalysisTimeMs = 0;
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK;

    // Dimensi gambar asli dari galeri yang sedang ditampilkan di OverlayView untuk scaling bounding box
    private int galleryImageOriginalWidth;
    private int galleryImageOriginalHeight;
    private Camera camera;

    // Deklarasi variabel untuk dimensi tampilan kamera (PreviewView)
    private int currentDisplayedImageWidth;
    private int currentDisplayedImageHeight;

    @Inject
    @Named("cameraExecutor")
    ExecutorService cameraExecutor;

    // Launcher untuk izin kamera
    private final ActivityResultLauncher<String> requestCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    checkReadExternalStoragePermission();
                } else {
                    resultTextView.setText("Camera permission is required to use this application.");
                }
            });

    // Launcher untuk izin READ_EXTERNAL_STORAGE (untuk Android <= API 32)
    private final ActivityResultLauncher<String> requestReadExternalStoragePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    Log.d(TAG, "Storage permission granted.");
                    setupCamera();
                } else {
                    Log.w(TAG, "Storage permission denied.");
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        resultTextView.setText("Storage permission permanently denied. Please enable it in app settings to access gallery.");
                    } else {
                        resultTextView.setText("Storage permission denied. Gallery access will not be available.");
                    }
                }
            });

    // Launcher untuk izin READ_MEDIA_IMAGES (untuk Android >= API 33)
    private final ActivityResultLauncher<String> requestReadMediaImagesPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    Log.d(TAG, "Read Media Images permission granted.");
                    setupCamera();
                } else {
                    Log.w(TAG, "Read Media Images permission denied.");
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)) {
                        resultTextView.setText("Gallery access permission permanently denied. Please enable it in app settings to access gallery.");
                    } else {
                        resultTextView.setText("Gallery access permission denied. Gallery access will not be available.");
                    }
                }
            });

    // ActivityResultLauncher untuk memilih gambar dari galeri
    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    try {
                        // Memuat bitmap asli untuk ditampilkan di OverlayView
                        // Menggunakan dimensi overlayView saat ini sebagai reqWidth/reqHeight untuk getBitmapFromUri
                        Bitmap originalBitmap = getBitmapFromUri(imageUri,
                                overlayView.getWidth() > 0 ? overlayView.getWidth() : getResources().getDisplayMetrics().widthPixels,
                                overlayView.getHeight() > 0 ? overlayView.getHeight() : getResources().getDisplayMetrics().heightPixels);

                        if (originalBitmap == null) {
                            throw new IOException("Failed to load bitmap from URI.");
                        }

                        // Buat bitmap yang diskalakan untuk analisis Roboflow (TARGET_IMAGE_WIDTH x TARGET_IMAGE_HEIGHT)
                        Bitmap bitmapForRoboflow = scaleBitmapToExactSize(originalBitmap, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT);

                        // Set bitmap di OverlayView untuk digambar
                        overlayView.setImageToDraw(originalBitmap);
                        overlayView.clear(); // Hapus bounding box yang mungkin ada dari analisis sebelumnya
                        previewView.setVisibility(View.GONE); // Sembunyikan PreviewView kamera

                        // Simpan dimensi gambar asli galeri untuk scaling bounding box
                        galleryImageOriginalWidth = originalBitmap.getWidth();
                        galleryImageOriginalHeight = originalBitmap.getHeight();

                        resultTextView.setText("Analyzing image from gallery...");
                        viewModel.sendImageToRoboflow(bitmapForRoboflow); // Kirim bitmap yang diskalakan untuk analisis
                        viewModel.stopAnalysis(); // Hentikan analisis kamera
                        viewModel.setCameraLive(false); // Set bahwa bukan mode kamera live

                    } catch (IOException e) {
                        Log.e(TAG, "Error loading or scaling image from gallery", e);
                        resultTextView.setText("Failed to load image from gallery.");
                        resetToCameraView();
                    } finally {
                        // originalBitmap akan di-recycle oleh OverlayView setelah selesai digambar,
                        // atau saat setImageToDraw() dipanggil lagi dengan null/bitmap lain.
                        // bitmapForRoboflow akan di-recycle oleh ViewModel setelah digunakan.
                    }
                } else if (result.getResultCode() == RESULT_CANCELED) {
                    Log.d(TAG, "Image selection canceled by user.");
                    resultTextView.setText("Image selection canceled.");
                    resetToCameraView();
                } else {
                    Log.e(TAG, "Image selection failed with result code: " + result.getResultCode());
                    resultTextView.setText("Image selection failed.");
                    resetToCameraView();
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        initializeUI();
        setupObservers();
        checkCameraPermission();
    }

    private void initializeUI() {
        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);
        toggleAnalysisButton = findViewById(R.id.toggleAnalysisButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        overlayView = findViewById(R.id.overlayView);
        zoomSlider = findViewById(R.id.zoomSlider);
        selectImageButton = findViewById(R.id.selectImageButton);

        toggleAnalysisButton.setOnClickListener(v -> {
            viewModel.toggleAnalysis();
            viewModel.setCameraLive(viewModel.isCurrentlyAnalyzing());
            if (viewModel.isCurrentlyAnalyzing()) {
                overlayView.setImageToDraw(null); // Hapus gambar galeri dari overlay
                previewView.setVisibility(View.VISIBLE); // Tampilkan preview kamera
                overlayView.clear(); // Bersihkan bounding box
            } else {
                // Saat analisis kamera dihentikan, kembali ke tampilan kamera default
                overlayView.clear(); // Bersihkan bounding box
                overlayView.setImageToDraw(null); // Hapus gambar galeri
                previewView.setVisibility(View.VISIBLE); // Kembali ke tampilan kamera default
            }
        });

        flipCameraButton.setOnClickListener(v -> {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
            currentLensFacing = (currentLensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            setupCamera();
        });

        zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && camera != null) {
                    float minZoom = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
                    float maxZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
                    float zoomRatio = minZoom + (maxZoom - minZoom) * (progress / 100f);
                    camera.getCameraControl().setZoomRatio(zoomRatio);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Not used
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Not used
            }
        });

        selectImageButton.setOnClickListener(v -> {
            boolean hasPermission;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            } else {
                hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }

            if (hasPermission) {
                openGallery();
            } else {
                checkReadExternalStoragePermission();
            }
        });
    }

    private void setupObservers() {
        viewModel.uiState.observe(this, state -> {
            resultTextView.setText(state.message);
            overlayView.clear(); // Selalu bersihkan bounding box sebelum menggambar yang baru

            // Prediksi hanya digambar jika ada dan statusnya ANALYZING atau SUCCESS
            if ((state.status == UiState.Status.ANALYZING || state.status == UiState.Status.SUCCESS) &&
                    state.predictions != null && !state.predictions.isEmpty()) {

                int analyzedModelWidth = TARGET_IMAGE_WIDTH;
                int analyzedModelHeight = TARGET_IMAGE_HEIGHT;

                if (overlayView.getWidth() > 0 && overlayView.getHeight() > 0) {
                    for (MainViewModel.Prediction prediction : state.predictions) {
                        drawBoundingBox(prediction, analyzedModelWidth, analyzedModelHeight);
                    }
                    overlayView.invalidate();
                }
            }

            // Atur visibilitas UI berdasarkan status dan isCameraLive
            switch (state.status) {
                case READY:
                case STOPPED:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundWhite));
                    if (viewModel.isCameraLive()) {
                        overlayView.setImageToDraw(null); // Pastikan gambar galeri bersih
                        previewView.setVisibility(View.VISIBLE);
                        if (imageAnalysis != null) {
                            unbindAnalysisUseCase();
                        }
                    } else {
                        // Jika dihentikan dan bukan mode kamera live (berarti sebelumnya galeri)
                        // Pastikan gambar galeri tetap terlihat jika ada.
                        if (overlayView.getImageToDraw() == null) { // Jika tidak ada gambar galeri di overlay, kembali ke kamera
                            resetToCameraView();
                        }
                        // Jika ada gambar galeri, biarkan OverlayView menampilkannya
                    }
                    break;

                case ANALYZING:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundRed));
                    if (viewModel.isCameraLive()) {
                        overlayView.setImageToDraw(null); // Pastikan gambar galeri bersih
                        previewView.setVisibility(View.VISIBLE);
                        if (imageAnalysis == null) {
                            bindAnalysisUseCase();
                        }
                    } else { // Menganalisis dari galeri
                        // Biarkan OverlayView menampilkan gambar galeri
                        previewView.setVisibility(View.GONE);
                    }
                    break;

                case SUCCESS:
                case ERROR:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundRed));
                    // Setelah sukses/error analisis, jika bukan live camera, tetap tampilkan gambar galeri
                    if (!viewModel.isCameraLive()) {
                        previewView.setVisibility(View.GONE);
                        // Biarkan OverlayView menampilkan gambar galeri
                    } else { // Jika ini dari live camera, pastikan previewView terlihat
                        overlayView.setImageToDraw(null); // Bersihkan gambar galeri jika beralih ke kamera
                        previewView.setVisibility(View.VISIBLE);
                    }
                    break;
            }
        });
    }

    private void drawBoundingBox(MainViewModel.Prediction prediction, int analyzedModelWidth, int analyzedModelHeight) {
        float halfWidth = prediction.width / 2;
        float halfHeight = prediction.height / 2;
        float left = prediction.x - halfWidth;
        float top = prediction.y - halfHeight;
        float right = prediction.x + halfWidth;
        float bottom = prediction.y + halfHeight;

        // Dapatkan dimensi tampilan tempat overlay digambar (ukuran OverlayView)
        float viewWidth = overlayView.getWidth();
        float viewHeight = overlayView.getHeight();

        if (viewWidth == 0 || viewHeight == 0 || analyzedModelWidth == 0 || analyzedModelHeight == 0) {
            Log.w(TAG, "DrawBoundingBox: View or analyzed image dimensions are zero.");
            return;
        }

        float contentWidth;
        float contentHeight;

        if (viewModel.isCameraLive()) {
            contentWidth = currentDisplayedImageWidth; // Dimensi tampilan PreviewView
            contentHeight = currentDisplayedImageHeight;
        } else {
            // Jika dari galeri, gunakan dimensi gambar asli galeri yang digambar di OverlayView
            contentWidth = galleryImageOriginalWidth;
            contentHeight = galleryImageOriginalHeight;
        }

        if (contentWidth == 0 || contentHeight == 0) {
            Log.w(TAG, "DrawBoundingBox: Content dimensions (camera or gallery) are zero. Cannot draw boxes.");
            return;
        }

        // Hitung skala dari koordinat model (TARGET_IMAGE_WIDTH/HEIGHT) ke dimensi gambar asli yang ditampilkan
        float scaleXModelToContent = contentWidth / (float) analyzedModelWidth;
        float scaleYModelToContent = contentHeight / (float) analyzedModelHeight;

        // Kemudian, skala dari dimensi gambar asli yang ditampilkan ke dimensi OverlayView
        float scaleContentToView = Math.min(viewWidth / contentWidth, viewHeight / contentHeight);


        // Skala total yang diterapkan pada koordinat model
        float finalScaleX = scaleXModelToContent * scaleContentToView;
        float finalScaleY = scaleYModelToContent * scaleContentToView;


        // Hitung offset agar gambar tetap di tengah dalam overlay (jika ada letterboxing/pillarboxing)
        float renderedContentWidth = contentWidth * scaleContentToView;
        float renderedContentHeight = contentHeight * scaleContentToView;

        float offsetXContent = (viewWidth - renderedContentWidth) / 2;
        float offsetYContent = (viewHeight - renderedContentHeight) / 2;


        RectF scaledBox = new RectF(
                (left * finalScaleX) + offsetXContent,
                (top * finalScaleY) + offsetYContent,
                (right * finalScaleX) + offsetXContent,
                (bottom * finalScaleY) + offsetYContent
        );

        String label = prediction.className + " (" + Math.round(prediction.confidence * 100) + "%)";
        OverlayView.Box box = new OverlayView.Box(scaledBox, label);

        overlayView.add(box);
    }


    // --- Fungsi baru untuk memuat dan menskala bitmap dari URI secara efisien ---
    private Bitmap getBitmapFromUri(Uri uri, int reqWidth, int reqHeight) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("Failed to open input stream for URI: " + uri);
        }

        // Mendapatkan dimensi bitmap tanpa memuatnya ke memori
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(inputStream, null, options);
        try {
            inputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing stream (decode bounds)", e);
        }

        // Hitung inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap dengan inSampleSize yang baru
        options.inJustDecodeBounds = false;
        InputStream secondInputStream = getContentResolver().openInputStream(uri);
        if (secondInputStream == null) {
            throw new IOException("Failed to open second input stream for URI: " + uri);
        }
        Bitmap bitmap = BitmapFactory.decodeStream(secondInputStream, null, options);
        try {
            secondInputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing stream (decode bitmap)", e);
        }

        // Periksa rotasi EXIF dan terapkan jika ada
        try (InputStream exifInputStream = getContentResolver().openInputStream(uri)) {
            if (exifInputStream != null) {
                ExifInterface exifInterface = new ExifInterface(exifInputStream);
                int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                Matrix matrix = new Matrix();
                int rotationDegrees = 0;
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        rotationDegrees = 90;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        rotationDegrees = 180;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        rotationDegrees = 270;
                        break;
                }
                if (rotationDegrees != 0 && bitmap != null) {
                    matrix.postRotate(rotationDegrees);
                    Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle();
                    }
                    bitmap = rotatedBitmap;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading EXIF for rotation", e);
        }
        return bitmap;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth; // Mengubah nama variabel agar tidak konflik dengan nama fungsi calculateInSampleSize

        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // Fungsi untuk menskala bitmap ke ukuran yang tepat (digunakan untuk mengirim ke Roboflow)
    private Bitmap scaleBitmapToExactSize(Bitmap originalBitmap, int targetWidth, int targetHeight) {
        if (originalBitmap == null) return null;

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true);

        // Jangan recycle originalBitmap di sini jika kita masih menampilkannya di OverlayView.
        // Kita hanya recycle bitmapForRoboflow di ViewModel.
        return scaledBitmap;
    }


    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission already granted.");
            checkReadExternalStoragePermission();
        } else {
            Log.d(TAG, "Requesting camera permission.");
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void checkReadExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Read Media Images permission already granted.");
                setupCamera();
            } else {
                Log.d(TAG, "Requesting Read Media Images permission.");
                requestReadMediaImagesPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Read External Storage permission already granted.");
                setupCamera();
            } else {
                Log.d(TAG, "Requesting Read External Storage permission.");
                requestReadExternalStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void setupCamera() {
        viewModel.setCameraLive(true); // Pastikan ViewModel tahu kamera live aktif
        overlayView.setImageToDraw(null); // Hapus gambar galeri dari overlay saat beralih ke kamera
        previewView.setVisibility(View.VISIBLE); // Tampilkan preview kamera
        overlayView.clear(); // Bersihkan bounding box

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing)
                        .build();

                if (!cameraProvider.hasCamera(cameraSelector)) {
                    Toast.makeText(this, "Kamera tidak tersedia", Toast.LENGTH_SHORT).show();
                    currentLensFacing = (currentLensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
                    cameraSelector = new CameraSelector.Builder().requireLensFacing(currentLensFacing).build();
                    if (!cameraProvider.hasCamera(cameraSelector)) {
                        Toast.makeText(this, "Tidak ada kamera yang bisa digunakan", Toast.LENGTH_LONG).show();
                        return;
                    }
                }

                preview = new Preview.Builder().build();
                bindPreviewOnly();

                if (viewModel.isCurrentlyAnalyzing()) {
                    bindAnalysisUseCase();
                }

                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview);
                camera.getCameraInfo().getZoomState().observe(this, zoomState -> {
                    float minZoom = zoomState.getMinZoomRatio();
                    float maxZoom = zoomState.getMaxZoomRatio();
                    float currentZoom = zoomState.getZoomRatio();
                    if (maxZoom > minZoom) {
                        int progress = (int) (((currentZoom - minZoom) / (maxZoom - minZoom)) * 100);
                        zoomSlider.setProgress(progress);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera: " + e.getMessage(), e);
                resultTextView.setText("Failed to start camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewOnly() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            // Setelah bind preview saja, pastikan dimensi tampilan untuk kamera terisi
            previewView.post(() -> {
                currentDisplayedImageWidth = previewView.getWidth();
                currentDisplayedImageHeight = previewView.getHeight();
            });
        }
    }

    private void bindAnalysisUseCase() {
        if (cameraProvider == null) {
            return;
        }
        if (imageAnalysis != null) {
            cameraProvider.unbind(imageAnalysis);
        }

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind analysis use case", e);
            resultTextView.setText("Failed to bind analysis: " + e.getMessage());
            try {
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to re-bind preview after analysis failure", e2);
            }
        }
    }

    private void unbindAnalysisUseCase() {
        if (cameraProvider != null && imageAnalysis != null) {
            cameraProvider.unbind(imageAnalysis);
            imageAnalysis = null;
            bindPreviewOnly();
        }
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (!viewModel.isCurrentlyAnalyzing() || !viewModel.isCameraLive()) {
            imageProxy.close();
            return;
        }
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs - lastAnalysisTimeMs < ANALYSIS_INTERVAL_MS) {
            imageProxy.close();
            return;
        }
        lastAnalysisTimeMs = currentTimeMs;

        try {
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap != null) {
                Bitmap bitmapForRoboflow = scaleBitmapToExactSize(bitmap, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT);
                viewModel.sendImageToRoboflow(bitmapForRoboflow);
                if (bitmapForRoboflow != bitmap) {
                    bitmap.recycle();
                }
            }
        } finally {
            imageProxy.close();
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) return null;

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);
        byte[] imageBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());

        if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotatedBitmap != bitmap) {
            bitmap.recycle();
        }
        return rotatedBitmap;
    }

    private void openGallery() {
        viewModel.setCameraLive(false);
        overlayView.clear();
        overlayView.setImageToDraw(null);
        previewView.setVisibility(View.GONE);

        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (intent.resolveActivity(getPackageManager()) != null) {
            pickImageLauncher.launch(intent);
        } else {
            Log.e(TAG, "No gallery app found on this device to handle ACTION_PICK for images.");
            resultTextView.setText("No gallery app found.");
            resetToCameraView();
        }
    }

    private void resetToCameraView() {
        overlayView.setImageToDraw(null);
        previewView.setVisibility(View.VISIBLE);
        viewModel.setCameraLive(true);
        setupCamera();
        overlayView.clear();
        resultTextView.setText("Ready to analyze");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (overlayView != null) {
            overlayView.setImageToDraw(null); // Memastikan bitmap di-recycle saat Activity dihancurkan
        }
    }
}