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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.lifecycle.ViewModelProvider;

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
    private ImageButton takePictureButton; // Tombol baru untuk mengambil gambar

    private MainViewModel viewModel;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageAnalysis imageAnalysis;
    private ImageCapture imageCapture; // Use case untuk mengambil gambar
    private CameraSelector cameraSelector;
    private long lastAnalysisTimeMs = 0;
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK;

    private int galleryImageOriginalWidth;
    private int galleryImageOriginalHeight;
    private Camera camera;

    private int currentDisplayedImageWidth;
    private int currentDisplayedImageHeight;

    private OnBackPressedCallback backPressedCallback;

    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;

    @Inject
    @Named("cameraExecutor")
    ExecutorService cameraExecutor;

    private final ActivityResultLauncher<String> requestCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    checkReadExternalStoragePermission();
                } else {
                    resultTextView.setText("Camera permission is required to use this application.");
                }
            });

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

    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    try {
                        Bitmap originalBitmap = getBitmapFromUri(imageUri,
                                overlayView.getWidth() > 0 ? overlayView.getWidth() : getResources().getDisplayMetrics().widthPixels,
                                overlayView.getHeight() > 0 ? overlayView.getHeight() : getResources().getDisplayMetrics().heightPixels);

                        if (originalBitmap == null) {
                            throw new IOException("Failed to load bitmap from URI.");
                        }

                        Bitmap bitmapForRoboflow = scaleBitmapToExactSize(originalBitmap, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT);

                        overlayView.setImageToDraw(originalBitmap);
                        overlayView.clear();
                        previewView.setVisibility(View.GONE);

                        galleryImageOriginalWidth = originalBitmap.getWidth();
                        galleryImageOriginalHeight = originalBitmap.getHeight();

                        resultTextView.setText("Analyzing image from gallery...");
                        viewModel.sendImageToRoboflow(bitmapForRoboflow);
                        viewModel.stopAnalysis();
                        viewModel.setCameraLive(false);

                    } catch (IOException e) {
                        Log.e(TAG, "Error loading or scaling image from gallery", e);
                        resultTextView.setText("Failed to load image from gallery.");
                        resetToCameraView();
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
        setupGestures();
        setupObservers();
        setupBackPressedHandler();
        checkCameraPermission();
    }

    private void setupBackPressedHandler() {
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                resetToCameraView();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
    }

    private void initializeUI() {
        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);
        toggleAnalysisButton = findViewById(R.id.toggleAnalysisButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        overlayView = findViewById(R.id.overlayView);
        zoomSlider = findViewById(R.id.zoomSlider);
        selectImageButton = findViewById(R.id.selectImageButton);

        // Inisialisasi tombol baru. Pastikan ID ini ada di XML Anda.
        takePictureButton = findViewById(R.id.takePictureButton);

        toggleAnalysisButton.setOnClickListener(v -> {
            if (!viewModel.isCurrentlyAnalyzing() && !viewModel.isCameraLive()) {
                resetToCameraView();
            }
            viewModel.toggleAnalysis();
        });

        flipCameraButton.setOnClickListener(v -> flipCamera());

        // Listener untuk tombol ambil gambar
        takePictureButton.setOnClickListener(v -> takePictureAndAnalyze());

        zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && camera != null && camera.getCameraInfo().getZoomState().getValue() != null) {
                    float minZoom = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
                    float maxZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
                    float zoomRatio = minZoom + (maxZoom - minZoom) * (progress / 100f);
                    camera.getCameraControl().setZoomRatio(zoomRatio);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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

        previewView.setOnTouchListener((v, event) -> {
            boolean isScaleEventHandled = scaleGestureDetector.onTouchEvent(event);
            boolean isFlingEventHandled = gestureDetector.onTouchEvent(event);
            return isScaleEventHandled || isFlingEventHandled;
        });
    }

    private void setupGestures() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null && viewModel.isCameraLive()) {
                    CameraInfo cameraInfo = camera.getCameraInfo();
                    float currentZoomRatio = cameraInfo.getZoomState().getValue().getZoomRatio();
                    float delta = detector.getScaleFactor();
                    float newZoomRatio = currentZoomRatio * delta;

                    float minZoom = cameraInfo.getZoomState().getValue().getMinZoomRatio();
                    float maxZoom = cameraInfo.getZoomState().getValue().getMaxZoomRatio();

                    camera.getCameraControl().setZoomRatio(Math.max(minZoom, Math.min(newZoomRatio, maxZoom)));
                    return true;
                }
                return false;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_MIN_DISTANCE = 120;
            private static final int SWIPE_THRESHOLD_VELOCITY = 200;
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (viewModel.isCameraLive() && e1 != null && e2 != null) {
                    float deltaX = e2.getX() - e1.getX();
                    if (Math.abs(deltaX) > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        flipCamera();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void flipCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        currentLensFacing = (currentLensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        setupCamera();
    }

    private void setupObservers() {
        viewModel.uiState.observe(this, state -> {
            if (backPressedCallback != null) {
                backPressedCallback.setEnabled(!viewModel.isCameraLive());
            }

            resultTextView.setText(state.message);
            overlayView.clear();

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

            switch (state.status) {
                case READY:
                case STOPPED:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundWhite));
                    if (viewModel.isCameraLive()) {
                        overlayView.setImageToDraw(null);
                        previewView.setVisibility(View.VISIBLE);
                        if (imageAnalysis != null) {
                            unbindAnalysisUseCase();
                        }
                    } else {
                        if (overlayView.getImageToDraw() == null) {
                            resetToCameraView();
                        }
                    }
                    break;

                case ANALYZING:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundRed));
                    if (viewModel.isCameraLive()) {
                        overlayView.setImageToDraw(null);
                        previewView.setVisibility(View.VISIBLE);
                        if (imageAnalysis == null) {
                            bindAnalysisUseCase();
                        }
                    } else {
                        previewView.setVisibility(View.GONE);
                    }
                    break;

                case SUCCESS:
                case ERROR:
                    toggleAnalysisButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackgroundRed));
                    if (!viewModel.isCameraLive()) {
                        previewView.setVisibility(View.GONE);
                    } else {
                        overlayView.setImageToDraw(null);
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

        float viewWidth = overlayView.getWidth();
        float viewHeight = overlayView.getHeight();

        if (viewWidth == 0 || viewHeight == 0 || analyzedModelWidth == 0 || analyzedModelHeight == 0) {
            Log.w(TAG, "DrawBoundingBox: View or analyzed image dimensions are zero.");
            return;
        }

        float contentWidth;
        float contentHeight;

        if (viewModel.isCameraLive()) {
            contentWidth = currentDisplayedImageWidth;
        } else {
            contentWidth = galleryImageOriginalWidth;
        }

        if (viewModel.isCameraLive()){
            contentHeight = currentDisplayedImageHeight;
        } else {
            contentHeight = galleryImageOriginalHeight;
        }

        if (contentWidth == 0 || contentHeight == 0) {
            Log.w(TAG, "DrawBoundingBox: Content dimensions are zero. Cannot draw boxes.");
            return;
        }

        float scaleXModelToContent = contentWidth / (float) analyzedModelWidth;
        float scaleYModelToContent = contentHeight / (float) analyzedModelHeight;
        float scaleContentToView = Math.min(viewWidth / contentWidth, viewHeight / contentHeight);
        float finalScaleX = scaleXModelToContent * scaleContentToView;
        float finalScaleY = scaleYModelToContent * scaleContentToView;
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

    private Bitmap getBitmapFromUri(Uri uri, int reqWidth, int reqHeight) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) throw new IOException("Failed to open input stream for URI: " + uri);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(inputStream, null, options);
        try { inputStream.close(); } catch (IOException e) { Log.e(TAG, "Error closing stream", e); }

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;

        InputStream secondInputStream = getContentResolver().openInputStream(uri);
        if (secondInputStream == null) throw new IOException("Failed to open second input stream for URI: " + uri);
        Bitmap bitmap = BitmapFactory.decodeStream(secondInputStream, null, options);
        try { secondInputStream.close(); } catch (IOException e) { Log.e(TAG, "Error closing stream", e); }

        try (InputStream exifInputStream = getContentResolver().openInputStream(uri)) {
            if (exifInputStream != null) {
                ExifInterface exifInterface = new ExifInterface(exifInputStream);
                int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                Matrix matrix = new Matrix();
                int rotationDegrees = 0;
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rotationDegrees = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotationDegrees = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotationDegrees = 270; break;
                }
                if (rotationDegrees != 0 && bitmap != null) {
                    matrix.postRotate(rotationDegrees);
                    Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (rotatedBitmap != bitmap) bitmap.recycle();
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
        final int width = options.outWidth;
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

    private Bitmap scaleBitmapToExactSize(Bitmap originalBitmap, int targetWidth, int targetHeight) {
        if (originalBitmap == null) return null;
        return Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true);
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            checkReadExternalStoragePermission();
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void checkReadExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                setupCamera();
            } else {
                requestReadMediaImagesPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                setupCamera();
            } else {
                requestReadExternalStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void setupCamera() {
        viewModel.setCameraLive(true);
        overlayView.setImageToDraw(null);
        previewView.setVisibility(View.VISIBLE);
        overlayView.clear();

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

                // Inisialisasi use case ImageCapture
                imageCapture = new ImageCapture.Builder()
                        .setTargetRotation(previewView.getDisplay().getRotation())
                        .build();

                bindPreviewAndCapture();

                if (viewModel.isCurrentlyAnalyzing()) {
                    bindAnalysisUseCase();
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera: " + e.getMessage(), e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewAndCapture() {
        if (cameraProvider != null && !isFinishing() && !isDestroyed()) {
            cameraProvider.unbindAll();

            preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            observeZoomState();

            previewView.post(() -> {
                currentDisplayedImageWidth = previewView.getWidth();
                currentDisplayedImageHeight = previewView.getHeight();
            });
        }
    }

    private void bindAnalysisUseCase() {
        if (cameraProvider == null || isFinishing() || isDestroyed()) return;

        cameraProvider.unbindAll();

        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture);
            observeZoomState();
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind analysis use case", e);
            try {
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                observeZoomState();
            } catch (Exception e2) {
                Log.e(TAG, "Failed to re-bind preview/capture after analysis failure", e2);
            }
        }
    }

    private void observeZoomState() {
        if (camera != null) {
            camera.getCameraInfo().getZoomState().observe(this, zoomState -> {
                float minZoom = zoomState.getMinZoomRatio();
                float maxZoom = zoomState.getMaxZoomRatio();
                float currentZoom = zoomState.getZoomRatio();
                if (maxZoom > minZoom) {
                    int progress = (int) (((currentZoom - minZoom) / (maxZoom - minZoom)) * 100);
                    zoomSlider.setProgress(progress);
                }
            });
        }
    }

    private void unbindAnalysisUseCase() {
        if (cameraProvider != null && imageAnalysis != null) {
            cameraProvider.unbind(imageAnalysis);
            imageAnalysis = null;
            bindPreviewAndCapture();
        }
    }

    private void takePictureAndAnalyze() {
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture use case is not initialized.");
            return;
        }

        resultTextView.setText("Capturing image...");

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                Bitmap bitmap = processCapturedImage(imageProxy);
                imageProxy.close();

                if (bitmap != null) {
                    runOnUiThread(() -> {
                        viewModel.setCameraLive(false);
                        if (viewModel.isCurrentlyAnalyzing()) {
                            viewModel.stopAnalysis();
                        }

                        overlayView.setImageToDraw(bitmap);
                        overlayView.clear();
                        previewView.setVisibility(View.GONE);

                        galleryImageOriginalWidth = bitmap.getWidth();
                        galleryImageOriginalHeight = bitmap.getHeight();

                        Bitmap bitmapForRoboflow = scaleBitmapToExactSize(bitmap, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT);

                        resultTextView.setText("Analyzing captured image...");
                        viewModel.sendImageToRoboflow(bitmapForRoboflow);
                    });
                } else {
                    runOnUiThread(() -> resultTextView.setText("Failed to process captured image."));
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Image capture failed: " + exception.getMessage(), exception);
                runOnUiThread(() -> resultTextView.setText("Failed to capture image: " + exception.getMessage()));
            }
        });
    }

    private Bitmap processCapturedImage(ImageProxy image) {
        if (image.getFormat() != ImageFormat.JPEG) {
            Log.e(TAG, "Image format from ImageCapture is not JPEG.");
            return null;
        }
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

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
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid image format for analysis, expected YUV_420_888");
            return null;
        }

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
        viewModel.setCameraLive(false);
        overlayView.clear();
        overlayView.setImageToDraw(null);
        previewView.setVisibility(View.GONE);

        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (intent.resolveActivity(getPackageManager()) != null) {
            pickImageLauncher.launch(intent);
        } else {
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
            overlayView.setImageToDraw(null);
        }
    }
}
