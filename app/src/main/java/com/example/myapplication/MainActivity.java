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
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private PreviewView previewView;
    private TextView resultTextView;
    private Button toggleAnalysisButton;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private ExecutorService roboflowExecutor;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();
    private final String TAG = "SITTING_POSTURE";
    private final String ROBOFLOW_API_URL = "https://detect.roboflow.com/sitting-posture-detection-3933f/1?api_key=sKe1oZrE1L1CzkUuJCaw"; // Your URL

    private boolean isAnalyzing = false;
    private long lastAnalysisTimeMs = 0;
    private static final long ANALYSIS_INTERVAL_MS = 1000;

    private ActivityResultLauncher<String> requestCameraPermissionLauncher;
    private ImageAnalysis imageAnalysis;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);
        toggleAnalysisButton = findViewById(R.id.toggleAnalysisButton);

        cameraExecutor = Executors.newSingleThreadExecutor();
        roboflowExecutor = Executors.newSingleThreadExecutor();

        requestCameraPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startCamera();
                    } else {
                        Toast.makeText(this, "Camera permission is required to use this feature.", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });

        toggleAnalysisButton.setOnClickListener(v -> {
            if (isAnalyzing) {
                stopAnalysis();
            } else {
                startAnalysis();
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startAnalysis() {
        if (cameraProvider == null) {
            Toast.makeText(this, "Camera not initialized yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        isAnalyzing = true;
        toggleAnalysisButton.setText("Stop Analysis");
        resultTextView.setText("Analysis Running...");
        bindImageAnalysisUseCase();
        Log.d(TAG, "Analysis started.");
    }

    private void stopAnalysis() {
        isAnalyzing = false;
        toggleAnalysisButton.setText("Start Analysis");
        resultTextView.setText("Analysis Stopped");
        Log.d(TAG, "Analysis stopped.");
    }


    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreviewUseCase();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera: " + e.getMessage(), e);
                Toast.makeText(this, "Error starting camera.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewUseCase() {
        if (cameraProvider == null) {
            Log.e(TAG, "Camera provider not available to bind preview.");
            return;
        }
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            Log.d(TAG, "Preview Usecase Bound");
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind preview: " + e.getMessage(), e);
        }
    }


    private void bindImageAnalysisUseCase() {
        if (cameraProvider == null) {
            Log.e(TAG, "Camera provider not available to bind image analysis.");
            return;
        }

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (!isAnalyzing) {
                imageProxy.close();
                return;
            }

            long currentTimeMs = System.currentTimeMillis();
            if (currentTimeMs - lastAnalysisTimeMs >= ANALYSIS_INTERVAL_MS) {
                lastAnalysisTimeMs = currentTimeMs;
                Log.d(TAG, "Processing frame at: " + currentTimeMs + " Format: " + imageProxy.getFormat());

                Bitmap bitmap = imageProxyToBitmap(imageProxy);
                if (bitmap != null) {
                    sendImageToRoboflow(bitmap);
                } else {
                    Log.e(TAG, "Could not convert ImageProxy to Bitmap.");
                }
            }
            imageProxy.close();
        });

        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            Log.d(TAG, "Preview and ImageAnalysis Usecases Bound");
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind ImageAnalysis or Preview: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to start analysis: " + e.getMessage(), Toast.LENGTH_LONG).show());
            stopAnalysis();
        }
    }

    private byte[] yuvToNv21(ImageProxy image) {
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        int ySize = yBuffer.remaining();
        int width = image.getWidth();
        int height = image.getHeight();

        byte[] nv21 = new byte[width * height * 3 / 2];

        yBuffer.get(nv21, 0, ySize);

        int uvOffset = ySize;
        int uPixelStride = uPlane.getPixelStride();
        int vPixelStride = vPlane.getPixelStride();
        int uRowStride = uPlane.getRowStride();
        int vRowStride = vPlane.getRowStride();

        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vPos = row * vRowStride + col * vPixelStride;
                int uPos = row * uRowStride + col * uPixelStride;

                if (vPos < vBuffer.capacity() && uPos < uBuffer.capacity()) {
                    if (uvOffset < nv21.length - 1) {
                        nv21[uvOffset++] = vBuffer.get(vPos);
                        nv21[uvOffset++] = uBuffer.get(uPos);
                    } else {
                        Log.w(TAG, "NV21 buffer overflow during V/U copy. Skipping remaining chroma data.");
                        return nv21;
                    }
                } else {
                    Log.w(TAG, "Buffer capacity exceeded for V/U plane access. vPos=" + vPos + ", uPos=" + uPos +
                            ". vCap=" + vBuffer.capacity() + ", uCap=" + uBuffer.capacity() + ". Skipping remaining chroma data.");
                    return nv21;
                }
            }
        }
        return nv21;
    }


    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        if (imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported image format: Expected YUV_420_888, got " + imageProxy.getFormat());
            return null;
        }

        byte[] nv21Bytes = yuvToNv21(imageProxy);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        YuvImage yuvImage = null;
        try {
            yuvImage = new YuvImage(nv21Bytes, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
            if (!yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 80, out)) {
                Log.e(TAG, "YuvImage.compressToJpeg failed.");
                return null;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to create YuvImage or compress: " + e.getMessage(), e);
            return null;
        }  catch (Exception e) {
            Log.e(TAG, "Unexpected error during YuvImage processing: " + e.getMessage(), e);
            return null;
        }


        byte[] imageBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

        if (bitmap == null) {
            Log.e(TAG, "BitmapFactory.decodeByteArray returned null, possibly due to malformed JPEG data.");
            return null;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        if (rotatedBitmap != bitmap) {
            bitmap.recycle();
        }

        return rotatedBitmap;
    }

    private void sendImageToRoboflow(Bitmap bitmap) {
        roboflowExecutor.submit(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] byteArray = baos.toByteArray();
                String base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP);

                RequestBody requestBody = RequestBody.create(base64Image,
                        MediaType.parse("application/x-www-form-urlencoded"));

                Request request = new Request.Builder()
                        .url(ROBOFLOW_API_URL)
                        .post(requestBody)
                        .addHeader("Content-Type", "application/x-www-form-urlencoded")
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Detection Success: " + responseBody);

                    RoboflowResponse roboflowResponse = gson.fromJson(responseBody, RoboflowResponse.class);

                    if (roboflowResponse != null && roboflowResponse.predictions != null && !roboflowResponse.predictions.isEmpty()) {
                        Prediction first = roboflowResponse.predictions.get(0);
                        String resultText = "Detected: " + first.className +
                                " (" + Math.round(first.confidence * 100) + "%)";
                        Log.d(TAG, "Detected posture: " + first.className + " with confidence " + first.confidence);
                        runOnUiThread(() -> {
                            resultTextView.setText(resultText);
                        });
                    } else {
                        Log.d(TAG, "No posture detected or empty predictions.");
                        runOnUiThread(() -> {
                            if (isAnalyzing) resultTextView.setText("No posture detected");
                        });
                    }
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    Log.e(TAG, "Detection Failed: " + response.code() + " - " + errorBody);
                    runOnUiThread(() -> {
                        if (isAnalyzing) resultTextView.setText("API Error: " + response.code());
                        // Toast.makeText(MainActivity.this, "API Error: " + response.code(), Toast.LENGTH_SHORT).show();
                    });
                }
                if (response.body() != null) {
                    response.body().close();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error sending image to Roboflow: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    if (isAnalyzing) resultTextView.setText("Error: " + e.getMessage());
                });
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
        if (roboflowExecutor != null) {
            roboflowExecutor.shutdownNow();
        }
        Log.d(TAG, "onDestroy called, executors shut down.");
    }

    public static class RoboflowResponse {
        public List<Prediction> predictions;
    }

    public static class Prediction {
        public float x;
        public float y;
        public float width;
        public float height;
        public float confidence;

        @SerializedName("class")
        public String className;

        @SerializedName("class_id")
        public int classId;
    }
}