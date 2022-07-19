package com.ppg.heartrate;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private PreviewView previewView;
    private TextView tv;
    private double hrtratebpm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.preview_view);
        tv = findViewById(R.id.text);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
        }
        startCameraPreview();
    }

    private void requestCameraPermission() {
        int requestCode = 100;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, requestCode);
    }

    public void startCameraPreview() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder()
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis imageFrameAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageFrameAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), imageProxy -> {
            processImageData(imageProxy.getWidth(), imageProxy.getHeight(), imageProxy.getPlanes()[0].getBuffer());
            Log.d("HBPM", Double.toString(hrtratebpm));
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    TextView tv= (TextView) findViewById(R.id.text);
                    tv.setText(Double.toString(hrtratebpm));
                }
            });
            imageProxy.close();
        });
        Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, imageFrameAnalysis, preview);
        camera.getCameraControl().enableTorch(true);
    }


    private long decodeYUV420SPtoRedSum(int width, int height, ByteBuffer byteBuffer) {
        int frameSize = width * height;
        int sum = 0;
        int j = 0;

        for (int yp = 0; j < height; ++j) {
            int uvp = frameSize + (j >> 1) * width;
            int u = 0;
            int v = 0;

            for (int i = 0; i < width; ++yp) {
                int y = (255 & byteBuffer.get(yp)) - 16;
                if (y < 0) {
                    y = 0;
                }

                if ((i & 1) == 0 && uvp < byteBuffer.capacity()) {
                    v = (255 & byteBuffer.get(uvp++)) - 128;
                    u = (255 & byteBuffer.get(uvp++)) - 128;
                }

                int y1192 = 1192 * y;
                int r = y1192 + 1634 * v;
                int g = y1192 - 833 * v - 400 * u;
                int b = y1192 + 2066 * u;
                if (r < 0) {
                    r = 0;
                }

                if (g < 0) {
                    g = 0;
                }

                if (b < 0) {
                    b = 0;
                }

                int pixel = -16777216 | r << 6 & 16711680 | g >> 2 & '\uff00' | b >> 10 & 255;
                int red = pixel >> 16 & 255;
                sum += red;
                ++i;
            }
        }

        return sum;
    }

    public final double decodeYUV420SPtoRedAvg(int width, int height, @Nullable ByteBuffer byteBuffer) {
        if (byteBuffer == null) {
            return 0;
        } else {
            double frameSize = width * height;
            long sum = this.decodeYUV420SPtoRedSum(width, height, byteBuffer);
            return sum / frameSize;
        }
    }

    private int beatsIndex;
    private final int beatsArraySize = 9;
    private final int[] beatsArray = new int[this.beatsArraySize];
    private int averageIndex;
    private final int averageArraySize = 10;
    private final double[] averageArray = new double[this.averageArraySize];
    private Type currentType = Type.GREEN;
    private double beats;
    private long startTime;

    public final void processImageData(int width, int height, @Nullable ByteBuffer byteBuffer) {
        double imgAvg = decodeYUV420SPtoRedAvg(width, height, byteBuffer);
        boolean _shouldProcess = true;
        if (imgAvg == 0 || imgAvg == 255) {
            _shouldProcess = false;
            return;
        }
        int averageArrayAvg = 0;
        int averageArrayCnt = 0;
        double rollingAverage = 0;

        for (double v : averageArray) {
            if (v > 0) {
                averageArrayAvg += v;
                averageArrayCnt++;
            }
        }

        if (averageArrayCnt > 0)
            rollingAverage = (double)averageArrayAvg / averageArrayCnt;

        Type newType = this.currentType;
        if (imgAvg < rollingAverage) {
            newType = Type.RED;
            if (newType != this.currentType) {
                this.beats++;
            }
        } else if (imgAvg > rollingAverage) {
            newType = Type.GREEN;
        }

        if (this.averageIndex == this.averageArraySize) {
            this.averageIndex = 0;
        }

        this.averageArray[this.averageIndex] = imgAvg;
        this.averageIndex++;
        if (newType != this.currentType) {
            this.currentType = newType;
        }

        long endTime = System.currentTimeMillis();
        double totalTimeInSecs = (double) (endTime - this.startTime) / 1000.0D;
        if (totalTimeInSecs >= 10) {
            double bps = this.beats / totalTimeInSecs;
            int dpm = (int) (bps * 60.0D);
            if (dpm < 30 || dpm > 180) {
                this.startTime = System.currentTimeMillis();
                this.beats = 0.0D;
                _shouldProcess = false;
                return;
            }

            if (this.beatsIndex == this.beatsArraySize) {
                this.beatsIndex = 0;
            }

            this.beatsArray[this.beatsIndex] = dpm;
            this.beatsIndex++;
            int beatsArrayAvg = 0;
            int beatsArrayCnt = 0;

            for (double v : beatsArray) {
                if (v > 0) {
                    beatsArrayAvg += v;
                    ++beatsArrayCnt;
                }
            }

            this.hrtratebpm = (double)beatsArrayAvg / beatsArrayCnt;
            this.startTime = System.currentTimeMillis();
            this.beats = 0.0D;
        }

        _shouldProcess = false;
    }
}