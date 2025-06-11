package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;

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
    private static final long ANALYSIS_INTERVAL_MS = 1000;

    // View Components
    private PreviewView previewView;
    private TextView resultTextView;
    private Button toggleAnalysisButton;

    // ViewModel and CameraX
    private MainViewModel viewModel;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageAnalysis imageAnalysis;
    private CameraSelector cameraSelector;
    private long lastAnalysisTimeMs = 0;

    @Inject
    @Named("cameraExecutor")
    ExecutorService cameraExecutor;

    private final ActivityResultLauncher<String> requestCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    setupCamera();
                } else {
                    resultTextView.setText("Camera permission is required to use this application.");
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

        // --- MODIFICATION START ---
        // Simplified the button's logic to just a toggle.
        toggleAnalysisButton.setOnClickListener(v -> viewModel.toggleAnalysis());
        // --- MODIFICATION END ---
    }

    private void setupObservers() {
        viewModel.uiState.observe(this, state -> {
            resultTextView.setText(state.message);

            // Logic to control the button's text, color, and camera analysis binding
            switch (state.status) {
                case READY:
                case STOPPED:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundWhite)); // Assuming a start color
                    unbindAnalysisUseCase();
                    break;

                case ANALYZING:
                case SUCCESS:
                case ERROR:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundRed)); // Running color
                    bindAnalysisUseCase(); // Bind (or ensure it's bound) for analysis
                    break;
            }
        });
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCamera();
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void setupCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                preview = new Preview.Builder().build();
                cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();
                bindPreviewOnly();
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
        if (cameraProvider == null || imageAnalysis != null) return; // Avoid re-binding

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            // Unbind everything first to add the analysis use case cleanly
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
            // Re-bind preview only to keep the camera view active
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