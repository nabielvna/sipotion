package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.myapplication.data.UiState;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SITTING_POSTURE_ACT";
    private static final long ANALYSIS_INTERVAL_MS = 500;

    private PreviewView previewView;
    private TextView resultTextView;
    private Button toggleAnalysisButton;
    private ImageButton flipCameraButton;
    private OverlayView overlayView;

    private MainViewModel viewModel;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageAnalysis imageAnalysis;
    private CameraSelector cameraSelector;
    private long lastAnalysisTimeMs = 0;
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK;
    private int imageWidth;
    private int imageHeight;

    @Inject
    @Named("cameraExecutor")
    ExecutorService cameraExecutor;

    private final ActivityResultLauncher<String> requestCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    checkStoragePermission();
                } else {
                    resultTextView.setText("Camera permission is required to use this application.");
                }
            });

    private final ActivityResultLauncher<String> requestStoragePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    resultTextView.setText("Storage permission denied. Images will not be saved on older Android versions.");
                }
                setupCamera();
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

        toggleAnalysisButton.setOnClickListener(v -> viewModel.toggleAnalysis());

        flipCameraButton.setOnClickListener(v -> {
            if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT;
            } else {
                currentLensFacing = CameraSelector.LENS_FACING_BACK;
            }
            setupCamera();
        });
    }

    private void setupObservers() {
        viewModel.uiState.observe(this, state -> {
            resultTextView.setText(state.message);
            overlayView.clear();

            switch (state.status) {
                case READY:
                case STOPPED:
                    // Anda bisa mengatur UI untuk status stop/ready di sini
                    unbindAnalysisUseCase();
                    break;
                case ANALYZING:
                    // Anda bisa mengatur UI untuk status analyzing di sini
                    bindAnalysisUseCase();
                    break;
                case SUCCESS:
                    bindAnalysisUseCase();
                    if (state.prediction != null) {
                        drawBoundingBox(state.prediction);
                    }
                    break;
                case ERROR:
                    bindAnalysisUseCase();
                    break;
            }
        });
    }

    private void drawBoundingBox(MainViewModel.Prediction prediction) {
        float halfWidth = prediction.width / 2;
        float halfHeight = prediction.height / 2;
        float left = prediction.x - halfWidth;
        float top = prediction.y - halfHeight;
        float right = prediction.x + halfWidth;
        float bottom = prediction.y + halfHeight;

        float viewWidth = overlayView.getWidth();
        float viewHeight = overlayView.getHeight();

        if (imageWidth == 0 || imageHeight == 0) return;

        float scaleX = viewWidth / (float) imageWidth;
        float scaleY = viewHeight / (float) imageHeight;

        float scale = Math.min(scaleX, scaleY);

        float offsetX = (viewWidth - (imageWidth * scale)) / 2;
        float offsetY = (viewHeight - (imageHeight * scale)) / 2;

        // --- PERBAIKAN: Menghilangkan tanda hubung yang salah dari nama variabel ---
        RectF scaledBox = new RectF(
                (left * scale) + offsetX,
                (top * scale) + offsetY,
                (right * scale) + offsetX,
                (bottom * scale) + offsetY
        );

        OverlayView.Box box = new OverlayView.Box(scaledBox, prediction.className);
        overlayView.add(box);
        overlayView.invalidate();
    }


    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            checkStoragePermission();
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                setupCamera();
            } else {
                requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else {
            setupCamera();
        }
    }

    private void setupCamera() {
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

            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera: " + e.getMessage(), e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewOnly() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
        }
    }

    private void bindAnalysisUseCase() {
        if (cameraProvider == null || imageAnalysis != null) {
            if (imageAnalysis != null) cameraProvider.unbind(imageAnalysis);
            imageAnalysis = null;
        }

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            cameraProvider.unbindAll();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind analysis use case", e);
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
        if (!viewModel.isCurrentlyAnalyzing()) {
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
                viewModel.sendImageToRoboflow(bitmap);
            }
        } finally {
            imageProxy.close();
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image.getImageInfo().getRotationDegrees() == 90 || image.getImageInfo().getRotationDegrees() == 270) {
            imageWidth = image.getHeight();
            imageHeight = image.getWidth();
        } else {
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
        }

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
