package signalprocess.facecapture;

/**
 * Created by Administrator on 2017/5/11.
 */

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;


public class CameraPreview extends SurfaceView
                           implements SurfaceHolder.Callback {

    private String TAG = "CameraPreview";
    private Context mContext;
    private CameraActivity mActivity;

    private int CAMERA_REQUEST_CODE = 20;
    private Camera mCamera = null;
    private SurfaceHolder mSurfaceHolder = null;
    private SurfaceTexture mSurfaceTexture = null;

    private FaceDetector mFaceDetector;

    //private int pWidth = 480, pHeight = 320;
    private int pWidth = 352, pHeight = 288;
    public int bufferSize;
    public byte[] buffer;

    public CameraPreview(Context context) {
        super(context);
        this.mContext = context;
        Log.d(TAG, "constructed.");
    }
    public CameraPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.mContext = context;
    }
    public CameraPreview(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.mContext = context;
    }

    public void init(CameraActivity activity) {
        Log.d(TAG, "init.");
        this.mActivity = activity;

        mSurfaceHolder = this.getHolder();
        mSurfaceHolder.setKeepScreenOn(true);
        mSurfaceHolder.addCallback(this);

        //any number, because the data will be discarded
        mSurfaceTexture = new SurfaceTexture(10);
        //create face detector
        mFaceDetector = new FaceDetector(this.mContext, this.mActivity);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surface view created.");
        try {
            if (mCamera == null) {
                mCamera = Camera.open(1);
            }

            mCamera.setPreviewTexture(mSurfaceTexture);
        } catch (Exception e) {
            Log.d(TAG, "Failed to setPreviewTexture");
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surface changed.");
        initCamera();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surface destroyed.");
        if (mCamera != null) {
            mCamera.setPreviewCallbackWithBuffer(null); //这个必须在前，不然退出出错
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private void initCamera() {
        if (mCamera != null) {
            Log.d(TAG, "camera opened.");
            try {
                Camera.Parameters params = mCamera.getParameters();
                //get supported preview size
                List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
                for (int i = 0; i < previewSizes.size(); i++) {
                    Camera.Size s = previewSizes.get(i);
                    Log.d(TAG, "PreviewSize,width: " + s.width + " height " + s.height);
                }
                List<int[]> previewFps = params.getSupportedPreviewFpsRange();
                for (int i = 0; i < previewFps.size(); i++) {
                    int[] s = previewFps.get(i);
                    Log.d(TAG, "Preview FPS range: " + s[0] + " -> " + s[1]);
                }

                params.setPreviewSize(pWidth, pHeight); // 指定preview的大小
                //params.setPreviewFpsRange(7000, 30000);
                params.setPreviewFormat(ImageFormat.NV21);
                mCamera.setParameters(params);

                bufferSize = pWidth * pHeight * ImageFormat.getBitsPerPixel(params.getPreviewFormat()) / 8;
                buffer = new byte[bufferSize];

                mCamera.addCallbackBuffer(buffer);
                mCamera.setPreviewCallbackWithBuffer(new FrameCallback());
                mCamera.startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class FrameCallback implements Camera.PreviewCallback {

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            //Log.d(TAG, "preview callback.");
            camera.addCallbackBuffer(buffer);
            final YuvImage image = new YuvImage(data, ImageFormat.NV21, pWidth, pHeight, null);
            ByteArrayOutputStream os = new ByteArrayOutputStream(data.length);

            if (image.compressToJpeg(new Rect(0, 0, pWidth, pHeight), 100, os)) {
                byte[] tmp = os.toByteArray();
                Bitmap src = BitmapFactory.decodeByteArray(tmp, 0, tmp.length);
                //rotate 90 degree
                Matrix matrix = new Matrix();
                matrix.postScale(-1, 1);
                matrix.postTranslate(src.getWidth(), 0);
                final Bitmap dst = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
                new Canvas(dst).drawBitmap(src, matrix, new Paint());

                synchronized (mSurfaceHolder) {
                    mFaceDetector.getLandmarks(dst);
                    Canvas canvas = mSurfaceHolder.lockCanvas();
                    canvas.drawBitmap(dst, 0, 0, null);
                    mSurfaceHolder.unlockCanvasAndPost(canvas);
                }

            }
        }
    }
}

