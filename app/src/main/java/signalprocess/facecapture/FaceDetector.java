package signalprocess.facecapture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.util.Log;

import com.tzutalin.dlib.Constants;
import com.tzutalin.dlib.FaceDet;
import com.tzutalin.dlib.VisionDetRet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FaceDetector {
    private String TAG = "FaceDetector";

    private int mCounter = 0;

    private Context mContext;
    private CameraActivity mActivity;
    private FaceDet mFaceDet;
    private Paint mLandmarkPaint;

    private final double r_m = 0.4, r_n = 0.5;

    public FaceDetector(Context c, CameraActivity activity) {
        Log.d(TAG, "constructed.");
        this.mContext = c;
        this.mActivity = activity;
        this.mFaceDet = new FaceDet(Constants.getFaceShapeModelPath());

        mLandmarkPaint = new Paint();
        mLandmarkPaint.setColor(Color.GREEN);
        mLandmarkPaint.setStrokeWidth(2);
        mLandmarkPaint.setStyle(Paint.Style.STROKE);

    }

    public void getLandmarks(Bitmap bmp) {
        //!!! bmp must be mutable
        //Log.d(TAG, "get face landmarks.");
        List<VisionDetRet> results;
        synchronized (this) {
            //detect
            results = mFaceDet.detect(bmp);
            if(results == null || results.size() == 0) {
                Log.d(TAG, "no face.");
                return;
            }
            VisionDetRet ret = results.get(0);
            int width = ret.getRight() - ret.getLeft();
            //draw landmarks
            ArrayList<Point> landmarks = ret.getFaceLandmarks();
            Canvas canvas = new Canvas(bmp);
            for (Point point : landmarks) {
                canvas.drawCircle(point.x, point.y, 1, mLandmarkPaint);
            }

            solveFacePose(landmarks);
            solveEmotion(landmarks, width);

        }
    }

    private void solveFacePose(ArrayList<Point> landmarks) {
        //Log.d(TAG, "analyze emotion");

        Point leftEye, rightEye, noseTip, mouthLeft, mouthRight;
        leftEye = landmarks.get(36);
        rightEye = landmarks.get(45);
        noseTip = landmarks.get(30);
        mouthLeft = landmarks.get(48);
        mouthRight = landmarks.get(54);

        Point noseBase = new Point((leftEye.x+rightEye.x)/2, (leftEye.y+rightEye.y)/2);
        Point mouth = new Point((mouthLeft.x+mouthRight.x)/2, (mouthLeft.y+mouthRight.y)/2);

        Point n = new Point((int)(mouth.x + (noseBase.x - mouth.x)*r_m),
                (int)(mouth.y + (noseBase.y - mouth.y)*r_m));

        double theta = Math.acos( (double)((noseBase.x-n.x)*(noseTip.x-n.x) + (noseBase.y-n.y)*(noseTip.y-n.y)) /
        Math.hypot(noseTip.x-n.x, noseTip.y-n.y) / Math.hypot(noseBase.x-n.x, noseBase.y-n.y));
        double tau = Math.atan2( (double)(n.y-noseTip.y), (double)(n.x-noseTip.x) );

        double m1 = (double)((noseTip.x-n.x)*(noseTip.x-n.x) + (noseTip.y-n.y)*(noseTip.y-n.y)) /
                        ((noseBase.x-mouth.x)*(noseBase.x-mouth.x) + (noseBase.y-mouth.y)*(noseBase.y-mouth.y)),
                m2 = Math.cos(theta)*Math.cos(theta);
        double a = r_n*r_n*(1-m2);
        double b = m1 - r_n*r_n + 2*m2*r_n*r_n;
        double c = - m2*r_n*r_n;

        double delta = Math.acos(Math.sqrt( (Math.sqrt(b*b-4*a*c) - b)/(2*a) ));

        //fn: facial normal, sfn: standard(no rotation) facial normal
        double[] fn = new double[3], sfn = new double[3];
        fn[0] = Math.sin(delta)*Math.cos(tau);
        fn[1] = Math.sin(delta)*Math.sin(tau);
        fn[2] = - Math.cos(delta);

        double alpha = Math.PI / 12;
        sfn[0] = 0;
        sfn[1] = Math.sin(alpha);
        sfn[2] = - Math.cos(alpha);

        //PITCH:X YAW:Y ROLL:X
        //Log.d(TAG, "facial normal: " + fn[0] + " " + fn[1] + " " + fn[2]);
        //Log.d(TAG, "standard facial normal: " + sfn[0] + " " + sfn[1] + " " + sfn[2]);

        /*
        live2d rotation order: ZXY
        live2d coordinate           my coordinate           my coordinate
        angle Z                     z axis                  Yaw
        angle X                     y axis                  Pitch
        angle Y                     x axis                  Roll

        my coordinate is same as the paper:
        Estimating Gaze from a Single View of a Face
        link: ieeexplore.ieee.org/document/576433/
         */

        // (w, x, y, z) is Euler quaternion
        //
        double w, x, y, z;
        double angle = Math.acos((sfn[0]*fn[0] + sfn[1]*fn[1] + sfn[2]*fn[2]) /
                Math.sqrt(sfn[0]*sfn[0] + sfn[1]*sfn[1] + sfn[2]*sfn[2]) /
                Math.sqrt(fn[0]*fn[0] + fn[1]*fn[1] + fn[2]*fn[2]));
        w = Math.cos(0.5*angle);
        x = sfn[1]*fn[2] - sfn[2]*fn[1];
        y = sfn[2]*fn[0] - sfn[0]*fn[2];
        z = sfn[0]*fn[1] - sfn[1]*fn[0];

        double l = Math.sqrt(x*x + y*y + z*z);
        x = Math.sin(0.5*angle)*x/l;
        y = Math.sin(0.5*angle)*y/l;
        z = Math.sin(0.5*angle)*z/l;

        //Log.d(TAG, "Angle: " + w);
        
        double yaw, pitch, roll;
        roll = Math.atan2(2*(w*x+y*z), 1-2*(x*x+y*y));
        pitch = Math.asin(2*(w*y-z*x));
        yaw = Math.atan2(2*(w*z+x*y), 1-2*(y*y+z*z));

//        if(yaw < Math.PI / 18) {
        if(sfn[0] < 0.1 && sfn[1] < 0.1) {
            roll = 1.5*Math.atan2(rightEye.y-leftEye.y, rightEye.x-leftEye.x);
        }
        yaw = Math.max(-30, Math.min(30, yaw*180/Math.PI));
        pitch = Math.max(-30, Math.min(30, pitch*180/Math.PI));
        roll = Math.max(-30, Math.min(30, roll*180/Math.PI));

        Log.d(TAG, "Yaw: " + yaw + " Pitch: " + pitch + " Roll: " + roll);

        this.mActivity.emotion[0] = yaw;
        this.mActivity.emotion[1] = pitch;
        this.mActivity.emotion[2] = roll;
        //this.mActivity.mGLSurfaceView.requestRender();
    }

    private void solveEmotion(ArrayList<Point> landmarks, int width) {
        //mouth open
        this.mActivity.emotion[3] = (double)(landmarks.get(57).y-landmarks.get(51).y) /
                (landmarks.get(64).x-landmarks.get(60).x) - 0.2;
    }

}
