/* Copyright 2014 Eddy Xiao <bewantbe@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package github.bewantbe.audio_analyzer_for_android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;

import java.util.Arrays;

import static java.lang.Math.log10;
import static java.lang.Math.pow;

/**
 * The spectrogram plot part of AnalyzerGraphic
 */

class SpectrogramPlot {
    static final String TAG = "SpectrogramPlot:";
    static final String[] axisLabels = {"Hz", "dB", "Sec"};
    boolean showFreqAlongX = false;

    private static final int[] cma = ColorMapArray.hot;

    int[] spectrogramColors = new int[0];  // int:ARGB, nFreqPoints columns, nTimePoints rows
    int[] spectrogramColorsShifting;       // temporarily of spectrogramColors for shifting mode
    int spectrogramColorsPt;          // pointer to the row to be filled (row major)

    enum TimeAxisMode {  // java's enum type is inconvenient
        SHIFT(0), OVERWRITE(1);       // 0: moving (shifting) spectrogram, 1: overwriting in loop

        private final int value;
        TimeAxisMode(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    private TimeAxisMode showModeSpectrogram = TimeAxisMode.OVERWRITE;
    private boolean bShowTimeAxis = true;

    private double timeWatch = 4.0;
    private volatile int timeMultiplier = 1;  // should be accorded with nFFTAverage in AnalyzerActivity
    int nFreqPoints;
    int nTimePoints;
    double timeInc;

    private Matrix matrixSpectrogram = new Matrix();
    private Paint smoothBmpPaint;
    private Paint backgroundPaint;
    private Paint cursorPaint;
    private Paint gridPaint, rulerBrightPaint;
    private Paint labelPaint;
    private Paint cursorTimePaint;

    ScreenPhysicalMapping axisFreq;
    ScreenPhysicalMapping axisTime;
    private GridLabel fqGridLabel;
    private GridLabel tmGridLabel;
    private float DPRatio;
    private float gridDensity = 1/85f;  // every 85 pixel one grid line, on average
    private float cursorFreq;
    private int canvasHeight=0, canvasWidth=0;
    float labelBeginX, labelBeginY;

    double dBLowerBound = -120;
    double dBUpperBound = 0.0;

    SpectrogramPlot(Context _context) {
        DPRatio = _context.getResources().getDisplayMetrics().density;

        gridPaint = new Paint();
        gridPaint.setColor(Color.DKGRAY);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.6f * DPRatio);

        cursorPaint = new Paint(gridPaint);
        cursorPaint.setColor(Color.parseColor("#00CD00"));

        cursorTimePaint = new Paint(cursorPaint);
        cursorTimePaint.setStyle(Paint.Style.STROKE);
        cursorTimePaint.setStrokeWidth(0);

        rulerBrightPaint = new Paint();
        rulerBrightPaint.setColor(Color.rgb(99, 99, 99));  // 99: between Color.DKGRAY and Color.GRAY
        rulerBrightPaint.setStyle(Paint.Style.STROKE);
        rulerBrightPaint.setStrokeWidth(1);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.GRAY);
        labelPaint.setTextSize(14.0f * DPRatio);
        labelPaint.setTypeface(Typeface.MONOSPACE);  // or Typeface.SANS_SERIF

        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.BLACK);

        cursorFreq = 0f;

        fqGridLabel = new GridLabel(GridLabel.Type.FREQ, canvasWidth  * gridDensity / DPRatio);
        tmGridLabel = new GridLabel(GridLabel.Type.TIME, canvasHeight * gridDensity / DPRatio);

        axisFreq = new ScreenPhysicalMapping(0, 0, 0, ScreenPhysicalMapping.Type.LINEAR);
        axisTime = new ScreenPhysicalMapping(0, 0, 0, ScreenPhysicalMapping.Type.LINEAR);

        Log.i(TAG, "SpectrogramPlot() initialized");
    }

    void setCanvas(int _canvasWidth, int _canvasHeight, RectF axisBounds) {
        Log.i(TAG, "setCanvas()");
        canvasWidth  = _canvasWidth;
        canvasHeight = _canvasHeight;
        if (showFreqAlongX) {
            axisFreq.setNCanvasPixel(canvasWidth - labelBeginX);
            axisTime.setNCanvasPixel(labelBeginY);
        } else {
            axisTime.setNCanvasPixel(canvasWidth - labelBeginX);
            axisFreq.setNCanvasPixel(labelBeginY);
        }
        if (axisBounds != null) {
            if (showFreqAlongX) {
                axisFreq.setBounds(axisBounds.left, axisBounds.right);
                axisTime.setBounds(axisBounds.top,  axisBounds.bottom);
            } else {
                axisTime.setBounds(axisBounds.left, axisBounds.right);
                axisFreq.setBounds(axisBounds.top,  axisBounds.bottom);
            }
            if (showModeSpectrogram == TimeAxisMode.SHIFT) {
                float b1 = axisTime.vLowerBound;
                float b2 = axisTime.vUpperBound;
                axisTime.setBounds(b2, b1);
            }
        }
        fqGridLabel.setDensity(axisFreq.nCanvasPixel * gridDensity / DPRatio);
        tmGridLabel.setDensity(axisTime.nCanvasPixel * gridDensity / DPRatio);

        synchronized (this) {
            logBmp.init(nFreqPoints, nTimePoints, axisFreq);
        }
        synchronized (this) {
            logSegBmp.init(nFreqPoints, nTimePoints, axisFreq);
        }
    }

    void setZooms(float xZoom, float xShift, float yZoom, float yShift) {
//        Log.i(TAG, "setZooms()");
        if (showFreqAlongX) {
            axisFreq.setZoomShift(xZoom, xShift);
            axisTime.setZoomShift(yZoom, yShift);
        } else {
//            Log.i(TAG, "  yZoom = " + yZoom + "  yShift = " + yShift);
            axisFreq.setZoomShift(yZoom, yShift);
            axisTime.setZoomShift(xZoom, xShift);
        }
    }

    // Linear or Logarithmic frequency axis
    void setFreqAxisMode(ScreenPhysicalMapping.Type mapType, float freq_lower_bound_for_log) {
        axisFreq.setMappingType(mapType, freq_lower_bound_for_log);
        if (mapType == ScreenPhysicalMapping.Type.LOG) {
            fqGridLabel.setGridType(GridLabel.Type.FREQ_LOG);
        } else {
            fqGridLabel.setGridType(GridLabel.Type.FREQ);
        }
        synchronized (this) {
            logBmp.init(nFreqPoints, nTimePoints, axisFreq);
        }
        synchronized (this) {
            logSegBmp.init(nFreqPoints, nTimePoints, axisFreq);
        }
    }

    void setupSpectrogram(int sampleRate, int fftLen, double timeDurationE, int nAve) {
        timeWatch = timeDurationE;
        timeMultiplier = nAve;
        timeInc = fftLen / 2.0 / sampleRate;  // time of each slice. /2.0 due to overlap window
        synchronized (this) {
            boolean bNeedClean = nFreqPoints != fftLen / 2;
            nFreqPoints = fftLen / 2;                    // no direct current term
            nTimePoints = (int)Math.ceil(timeWatch / timeInc);
            if (spectrogramColors == null || spectrogramColors.length != nFreqPoints * nTimePoints) {
                spectrogramColors = new int[nFreqPoints * nTimePoints];
                spectrogramColorsShifting = new int[nFreqPoints * nTimePoints];
                bNeedClean = true;
            }
            if (!bNeedClean && spectrogramColorsPt >= nTimePoints) {
                Log.w(TAG, "setupSpectrogram(): Should not happen!!");
                Log.i(TAG, "setupSpectrogram(): spectrogramColorsPt="+spectrogramColorsPt+ "  nFreqPoints="+nFreqPoints+"  nTimePoints="+nTimePoints);
                bNeedClean = true;
            }
            if (bNeedClean) {
                spectrogramColorsPt = 0;
                Arrays.fill(spectrogramColors, 0);
            }
        }
        synchronized (this) {
            logBmp.init(nFreqPoints, nTimePoints, axisFreq);
        }
        synchronized (this) {
            logSegBmp.init(nFreqPoints, nTimePoints, axisFreq);
        }
        Log.i(TAG, "setupSpectrogram() done" +
                "\n  sampleRate    = " + sampleRate +
                "\n  fftLen        = " + fftLen +
                "\n  timeDurationE = " + timeDurationE + " * " + nAve + "  (" + nTimePoints + " points)");
    }

    // Draw axis, start from (labelBeginX, labelBeginY) in the canvas coordinate
    // drawOnXAxis == true : draw on X axis, otherwise Y axis
    private void drawAxis(Canvas c, float labelBeginX, float labelBeginY, boolean drawOnXAxis,
                          GridLabel.Type scale_mode) {
        int scale_mode_id = scale_mode.getValue();
        float canvasMin;
        float canvasMax;
        if (drawOnXAxis) {
            canvasMin = labelBeginX;
            canvasMax = canvasWidth;
        } else {
            canvasMin = 0;
            canvasMax = labelBeginY;
        }
        ScreenPhysicalMapping axis = axisFreq;
        if (scale_mode == GridLabel.Type.TIME) {
            axis = axisTime;
        }
        GridLabel[] gridLabelArray = {fqGridLabel, null, tmGridLabel};
//        Log.i(TAG, "drawAxis():  axis.vMinInView()" + axis.vMinInView() + "  axis.vMaxInView() = " + axis.vMaxInView());
        gridLabelArray[scale_mode_id].updateGridLabels(axis.vMinInView(), axis.vMaxInView());
//        Log.i(TAG, "   updateGridLabels()  ... done");
        String axisLabel = axisLabels[scale_mode_id];

        double[][] gridPoints = {gridLabelArray[scale_mode_id].values, gridLabelArray[scale_mode_id].ticks};
        StringBuilder[] gridPointsStr = gridLabelArray[scale_mode_id].strings;
        char[][]        gridPointsSt  = gridLabelArray[scale_mode_id].chars;

        // plot axis mark
        float posAlongAxis;
        float textHeigh     = labelPaint.getFontMetrics(null);
        float labelLargeLen = 0.5f * textHeigh;
        float labelSmallLen = 0.6f*labelLargeLen;
        for(int i = 0; i < gridPoints[1].length; i++) {
            //posAlongAxis =((float)gridPoints[1][i] - axisMin) / (axisMax-axisMin) * (canvasMax - canvasMin) + canvasMin;
            posAlongAxis = canvasMin + axis.pixelFromV((float)gridPoints[1][i]);
            if (drawOnXAxis) {
                c.drawLine(posAlongAxis, labelBeginY, posAlongAxis, labelBeginY+labelSmallLen, gridPaint);
            } else {
                c.drawLine(labelBeginX-labelSmallLen, posAlongAxis, labelBeginX, posAlongAxis, gridPaint);
            }
        }
        for(int i = 0; i < gridPoints[0].length; i++) {
//            posAlongAxis = ((float)gridPoints[0][i] - axisMin) / (axisMax-axisMin) * (canvasMax - canvasMin) + canvasMin;
            posAlongAxis = canvasMin + axis.pixelFromV((float)gridPoints[0][i]);
            if (drawOnXAxis) {
                c.drawLine(posAlongAxis, labelBeginY, posAlongAxis, labelBeginY+labelLargeLen, rulerBrightPaint);
            } else {
                c.drawLine(labelBeginX-labelLargeLen, posAlongAxis, labelBeginX, posAlongAxis, rulerBrightPaint);
            }
        }
        if (drawOnXAxis) {
            c.drawLine(canvasMin, labelBeginY, canvasMax, labelBeginY, labelPaint);
        } else {
            c.drawLine(labelBeginX, canvasMin, labelBeginX, canvasMax, labelPaint);
        }

        // plot labels
        float widthDigit = labelPaint.measureText("0");
        float posOffAxis = labelBeginY + 0.3f*labelLargeLen + textHeigh;
        for(int i = 0; i < gridPointsStr.length; i++) {
            //posAlongAxis = ((float)gridPoints[0][i] - axisMin) / (axisMax-axisMin) * (canvasMax - canvasMin) + canvasMin;
            posAlongAxis = canvasMin + axis.pixelFromV((float)gridPoints[0][i]);
            if (drawOnXAxis) {
                if (posAlongAxis + widthDigit * gridPointsStr[i].length() > canvasWidth - (axisLabel.length() + .3f)*widthDigit) {
                    continue;
                }
                c.drawText(gridPointsSt[i], 0, gridPointsStr[i].length(), posAlongAxis, posOffAxis, labelPaint);
            } else {
//                Log.i(TAG, "posAlongAxis = " + posAlongAxis + "  canvasMax+textHeigh = " + (canvasMax+textHeigh) +
//                           "  diff = " + (canvasMax + textHeigh - (posAlongAxis - 0.5f*textHeigh)));
                if (posAlongAxis - 1.0f*textHeigh < canvasMin + textHeigh) {
                    continue;
                }
                c.drawText(gridPointsSt[i], 0, gridPointsStr[i].length(),
                        labelBeginX - widthDigit * gridPointsStr[i].length() - 0.5f * labelLargeLen, posAlongAxis, labelPaint);
            }
        }
        if (drawOnXAxis) {
            c.drawText(axisLabel, canvasWidth - (axisLabel.length() +.3f)*widthDigit, posOffAxis, labelPaint);
        } else {
            c.drawText(axisLabel, labelBeginX - widthDigit * axisLabel.length() - 0.5f * labelLargeLen, canvasMin+textHeigh, labelPaint);
        }
    }

    // Draw time axis for spectrogram
    // Working in the original canvas frame
    private void drawTimeAxis(Canvas c, float labelBeginX, float labelBeginY, boolean drawOnXAxis) {
//            Log.i(TAG, "drawTimeAxis(): max=" + getTimeMax() + "  min=" + getTimeMin());
//        drawAxis(c, labelBeginX, labelBeginY, nt, drawOnXAxis,
//                getTimeMax(), getTimeMin(), GridLabel.Type.TIME);

        if (showFreqAlongX) {
            drawAxis(c, labelBeginX, labelBeginY, drawOnXAxis, GridLabel.Type.TIME);
        } else {
            drawAxis(c, labelBeginX, labelBeginY, drawOnXAxis, GridLabel.Type.TIME);
        }
    }

    // Draw frequency axis for spectrogram
    // Working in the original canvas frame
    // nx: number of grid lines on average
    private void drawFreqAxis(Canvas c, float labelBeginX, float labelBeginY, boolean drawOnXAxis) {
        if (showFreqAlongX) {
            drawAxis(c, labelBeginX, labelBeginY, drawOnXAxis, GridLabel.Type.FREQ);
        } else {
            drawAxis(c, labelBeginX, labelBeginY, drawOnXAxis, GridLabel.Type.FREQ);
        }
    }

//    private float getTimeMin() {
//        return axisTime.vMinInView();
//    }

    private float getTimeMax() {
        return axisTime.vMaxInView();
    }

    float getCursorFreq() {
        return canvasWidth == 0 ? 0 : cursorFreq;
    }

    void setCursor(float x, float y) {
        if (showFreqAlongX) {
            //cursorFreq = axisBounds.width() * (xShift + (x-labelBeginX)/(canvasWidth-labelBeginX)/xZoom);  // frequency
            cursorFreq = axisFreq.vFromPixel(x - labelBeginX);
        } else {
            //cursorFreq = axisBounds.width() * (1 - yShift - y/labelBeginY/yZoom);  // frequency
            cursorFreq = axisFreq.vFromPixel(y);
        }
        if (cursorFreq < 0) {
            cursorFreq = 0;
        }
    }

    void hideCursor() {
        cursorFreq = 0;
    }

    private void drawFreqCursor(Canvas c) {
        if (cursorFreq == 0) return;
        float cX, cY;
        // Show only the frequency cursor
        if (showFreqAlongX) {
            cX = axisFreq.pixelFromV(cursorFreq) + labelBeginX;
            c.drawLine(cX, 0, cX, labelBeginY, cursorPaint);
        } else {
            cY = axisFreq.pixelFromV(cursorFreq);
            c.drawLine(labelBeginX, cY, canvasWidth, cY, cursorPaint);
        }
    }

//    float getFreqMax() {
//        return axisFreq.vMaxInView();
//    }
//
//    float getFreqMin() {
//        return axisFreq.vMinInView();
//    }

    void setTimeMultiplier(int nAve) {
        timeMultiplier = nAve;
        axisTime.vUpperBound = (float)(timeWatch * timeMultiplier);
    }

    void setShowTimeAxis(boolean bSTA) {
        bShowTimeAxis = bSTA;
    }

    void setSpectrogramModeShifting(boolean b) {
        if ((showModeSpectrogram == TimeAxisMode.SHIFT) != b) {
            // mode change, swap time bounds.
            float b1 = axisTime.vLowerBound;
            float b2 = axisTime.vUpperBound;
            axisTime.setBounds(b2, b1);
        }
        if (b) {
            showModeSpectrogram = TimeAxisMode.SHIFT;
            setPause(isPaused);  // update time estimation
        } else {
            showModeSpectrogram = TimeAxisMode.OVERWRITE;
        }
    }

    void prepare() {
        if (showModeSpectrogram == TimeAxisMode.SHIFT)
            setPause(isPaused);
    }

    void setShowFreqAlongX(boolean b) {
        if (showFreqAlongX != b) {
            // Set (swap) canvas size
            float t = axisFreq.nCanvasPixel;
            axisFreq.setNCanvasPixel(axisTime.nCanvasPixel);
            axisTime.setNCanvasPixel(t);
            // swap bounds of freq axis
            axisFreq.reverseBounds();

            fqGridLabel.setDensity(axisFreq.nCanvasPixel * gridDensity / DPRatio);
            tmGridLabel.setDensity(axisTime.nCanvasPixel * gridDensity / DPRatio);
        }
        showFreqAlongX = b;
    }

    void setSmoothRender(boolean b) {
        if (b) {
            smoothBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        } else {
            smoothBmpPaint = null;
        }
    }

    private int colorFromDB(double d) {
        if (d >= dBUpperBound) {
            return cma[0];
        }
        if (d <= dBLowerBound || Double.isInfinite(d) || Double.isNaN(d)) {
            return cma[cma.length-1];
        }
        return cma[(int)(cma.length * (dBUpperBound - d) / (dBUpperBound - dBLowerBound))];
    }

    private double timeLastSample = 0;
    private boolean updateTimeDiff = false;

    void setPause(boolean p) {
        if (p == false) {
            timeLastSample = System.currentTimeMillis()/1000.0;
        }
        isPaused = p;
    }

    // Will be called in another thread (SamplingLoop)
    void saveRowSpectrumAsColor(final double[] db) {
        double tNow = System.currentTimeMillis()/1000.0;
        updateTimeDiff = true;
        if (Math.abs(timeLastSample - tNow) > 0.5) {
            timeLastSample = tNow;
        } else {
            timeLastSample += timeInc * timeMultiplier;
            timeLastSample += (tNow - timeLastSample) * 1e-2;  // track current time
        }

        // db.length == 2^n + 1
        synchronized (this) {  // essentially a lock on spectrogramColors
            int pRef = spectrogramColorsPt*nFreqPoints - 1;
            for (int i = 1; i < db.length; i++) {  // no direct current term
                spectrogramColors[pRef + i] = colorFromDB(db[i]);
            }
            spectrogramColorsPt++;
            if (spectrogramColorsPt >= nTimePoints) {
                spectrogramColorsPt = 0;
            }
            logBmp.fill(db);
            logSegBmp.fill(db);
        }
    }

    private float getLabelBeginY() {
        float textHeigh     = labelPaint.getFontMetrics(null);
        float labelLaegeLen = 0.5f * textHeigh;
        if (!showFreqAlongX && !bShowTimeAxis) {
            return canvasHeight;
        } else {
            return canvasHeight - 0.6f*labelLaegeLen - textHeigh;
        }
    }

    // Left margin for ruler
    private float getLabelBeginX() {
        float textHeigh     = labelPaint.getFontMetrics(null);
        float labelLaegeLen = 0.5f * textHeigh;
        if (showFreqAlongX) {
            if (bShowTimeAxis) {
                int j = 3;
                for (int i = 0; i < tmGridLabel.strings.length; i++) {
                    if (j < tmGridLabel.strings[i].length()) {
                        j = tmGridLabel.strings[i].length();
                    }
                }
                return 0.6f*labelLaegeLen + j*0.5f*textHeigh;
            } else {
                return 0;
            }
        } else {
            return 0.6f*labelLaegeLen + 2.5f*textHeigh;
        }
    }

    private float pixelTimeCompensate = 0;
    volatile boolean isPaused = false;

    // Plot spectrogram with axis and ticks on the whole canvas c
    void drawSpectrogramPlot(Canvas c) {
        labelBeginX = getLabelBeginX();  // this seems will make the scaling gesture inaccurate
        labelBeginY = getLabelBeginY();
        if (showFreqAlongX) {
            axisFreq.setNCanvasPixel(canvasWidth-labelBeginX);
            axisTime.setNCanvasPixel(labelBeginY);
        } else {
            axisTime.setNCanvasPixel(canvasWidth-labelBeginX);
            axisFreq.setNCanvasPixel(labelBeginY);
        }
        fqGridLabel.setDensity(axisFreq.nCanvasPixel * gridDensity / DPRatio);
        tmGridLabel.setDensity(axisTime.nCanvasPixel * gridDensity / DPRatio);

        // show Spectrogram
        float halfFreqResolutionShift;  // move the color patch to match the center frequency
        matrixSpectrogram.reset();
        if (showFreqAlongX) {
            // when xZoom== 1: nFreqPoints -> canvasWidth; 0 -> labelBeginX
            matrixSpectrogram.postScale(axisFreq.zoom * axisFreq.nCanvasPixel / nFreqPoints,
                    axisTime.zoom * axisTime.nCanvasPixel / nTimePoints);
            halfFreqResolutionShift = axisFreq.zoom * axisFreq.nCanvasPixel / nFreqPoints / 2;
            matrixSpectrogram.postTranslate((labelBeginX - axisFreq.shift * axisFreq.zoom * axisFreq.nCanvasPixel + halfFreqResolutionShift),
                    -axisTime.shift * axisTime.zoom * axisTime.nCanvasPixel);
        } else {
            // postRotate() will make c.drawBitmap about 20% slower, don't know why
            matrixSpectrogram.postRotate(-90);
            matrixSpectrogram.postScale(axisTime.zoom * axisTime.nCanvasPixel / nTimePoints,
                    axisFreq.zoom * axisFreq.nCanvasPixel / nFreqPoints);
            // (1-yShift) is relative position of shift (after rotation)
            // yZoom*labelBeginY is canvas length in frequency direction in pixel unit
            halfFreqResolutionShift = axisFreq.zoom * axisFreq.nCanvasPixel / nFreqPoints/2;
            matrixSpectrogram.postTranslate((labelBeginX - axisTime.shift * axisTime.zoom * axisTime.nCanvasPixel),
                    (1-axisFreq.shift) * axisFreq.zoom * axisFreq.nCanvasPixel - halfFreqResolutionShift);
        }
        c.save();
        c.concat(matrixSpectrogram);

        // Time compensate to make it smoother shifting
        // But if use pressed pause, stop compensate.
        if (!isPaused && updateTimeDiff) {
            double timeCurrent = System.currentTimeMillis() / 1000.0;
            pixelTimeCompensate = (float) ((timeLastSample - timeCurrent) / (timeInc * timeMultiplier * nTimePoints) * nTimePoints);
            updateTimeDiff = false;
//            Log.i(TAG, " time diff = " + (timeLastSample - timeCurrent));
        }
        if (showModeSpectrogram == TimeAxisMode.SHIFT) {
            c.translate(0.0f, pixelTimeCompensate);
        }

        // public void drawBitmap (int[] colors, int offset, int stride, float x, float y,
        //                         int width, int height, boolean hasAlpha, Paint paint)
//      long t = SystemClock.uptimeMillis();
        // drawBitmap(int[] ...) was deprecated in API level 21.
        // http://developer.android.com/reference/android/graphics/Canvas.html#drawBitmap(int[], int, int, float, float, int, int, boolean, android.graphics.Paint)
        // Consider use Bitmap
        // http://developer.android.com/reference/android/graphics/Bitmap.html#setPixels(int[], int, int, int, int, int, int)
        if (axisFreq.mapType == ScreenPhysicalMapping.Type.LOG) {
            // Reference answer
//            c.save();
//            c.scale(1, 0.5f);
//            logBmp.draw(c);
//            if (showModeSpectrogram == TimeAxisMode.OVERWRITE) {
//                c.drawLine(0, logBmp.bmPt, logBmp.nFreq, logBmp.bmPt, cursorTimePaint);
//            }
//            c.restore();

//            c.save();
//            c.translate(0, nTimePoints/2);
//            c.scale((float)nFreqPoints / logSegBmp.bmpWidth, 0.5f);
//            logSegBmp.draw(c);
//            if (showModeSpectrogram == TimeAxisMode.OVERWRITE) {
//                c.drawLine(0, logSegBmp.bmPt, logSegBmp.bmpWidth, logSegBmp.bmPt, cursorTimePaint);
//            }
//            c.restore();

            c.scale((float)nFreqPoints / logSegBmp.bmpWidth, 1.0f);
            synchronized (this) {
                logSegBmp.draw(c);
            }
            if (showModeSpectrogram == TimeAxisMode.OVERWRITE) {
                c.drawLine(0, logSegBmp.bmPt, logSegBmp.bmpWidth, logSegBmp.bmPt, cursorTimePaint);
            }
        } else {
            synchronized (this) {
                if (showModeSpectrogram == TimeAxisMode.SHIFT) {
                    System.arraycopy(spectrogramColors, 0, spectrogramColorsShifting,
                            (nTimePoints - spectrogramColorsPt) * nFreqPoints, spectrogramColorsPt * nFreqPoints);
                    System.arraycopy(spectrogramColors, spectrogramColorsPt * nFreqPoints, spectrogramColorsShifting,
                            0, (nTimePoints - spectrogramColorsPt) * nFreqPoints);
                    c.drawBitmap(spectrogramColorsShifting, 0, nFreqPoints, 0, 0,
                            nFreqPoints, nTimePoints, false, smoothBmpPaint);
                } else {
                    c.drawBitmap(spectrogramColors, 0, nFreqPoints, 0, 0,
                            nFreqPoints, nTimePoints, false, smoothBmpPaint);
                }
            }
            // new data line
            if (showModeSpectrogram == TimeAxisMode.OVERWRITE) {
                c.drawLine(0, spectrogramColorsPt, nFreqPoints, spectrogramColorsPt, cursorTimePaint);
            }
        }
        c.restore();
        drawFreqCursor(c);
        if (showFreqAlongX) {
            c.drawRect(0, labelBeginY, canvasWidth, canvasHeight, backgroundPaint);
            drawFreqAxis(c, labelBeginX, labelBeginY, showFreqAlongX);
            if (labelBeginX > 0) {
                c.drawRect(0, 0, labelBeginX, labelBeginY, backgroundPaint);
                drawTimeAxis(c, labelBeginX, labelBeginY, !showFreqAlongX);
            }
        } else {
            c.drawRect(0, 0, labelBeginX, labelBeginY, backgroundPaint);
            drawFreqAxis(c, labelBeginX, labelBeginY, showFreqAlongX);
            if (labelBeginY != canvasHeight) {
                c.drawRect(0, labelBeginY, canvasWidth, canvasHeight, backgroundPaint);
                drawTimeAxis(c, labelBeginX, labelBeginY, !showFreqAlongX);
            }
        }
    }

    private LogFreqSpectrogramBMP logBmp = new LogFreqSpectrogramBMP();

    private class LogFreqSpectrogramBMP {
        final static String TAG = "LogFreqSpectrogramBMP:";
        int nFreq = 0;
        int nTime = 0;
        int[] bm = new int[0];   // elememts are in "time major" order.
        int[] bmShiftCache = new int[0];
        int bmPt = 0;
        int[] mapFreqToPixL = new int[0];  // map that a frequency point should map to bm[]
        int[] mapFreqToPixH = new int[0];
        ScreenPhysicalMapping axis = null;

        LogFreqSpectrogramBMP() {

        }

        // like setupSpectrogram()
        void init(int _nFreq, int _nTime, ScreenPhysicalMapping _axis) {
            // _nFreq == 2^n
            if (bm.length != _nFreq * _nTime) {
                bm = new int[_nFreq * _nTime];
                bmShiftCache = new int[bm.length];
            }
            if (mapFreqToPixL.length != _nFreq + 1) {
                Log.d(TAG, "init(): New");
                mapFreqToPixL = new int[_nFreq + 1];
                mapFreqToPixH = new int[_nFreq + 1];
            }
            if (nFreq != _nFreq || nTime != _nTime) {
                Arrays.fill(bm, 0);
                bmPt = 0;
            }  // else only update axis
            nFreq = _nFreq;
            nTime = _nTime;
            axis = _axis;
            if (axis == null) {
                Log.e(TAG, "init(): damn: axis == null");
            }
            float dFreq = Math.max(axis.vLowerBound, axis.vUpperBound) / nFreq;
            Log.i(TAG, "init(): axis.vL=" + axis.vLowerBound + "  axis.vU=" + axis.vUpperBound + "  axis.nC=" + axis.nCanvasPixel);
            for (int i = 0; i <= nFreq; i++) {  // freq = i * dFreq
                // do not show DC component (xxx - 1).
                mapFreqToPixL[i] = (int) Math.floor(axis.pixelNoZoomFromV((i - 0.5f) * dFreq) / axis.nCanvasPixel * nFreq);
                mapFreqToPixH[i] = (int) Math.floor(axis.pixelNoZoomFromV((i + 0.5f) * dFreq) / axis.nCanvasPixel * nFreq);
                if (mapFreqToPixH[i] >= nFreq) mapFreqToPixH[i] = nFreq - 1;
                if (mapFreqToPixH[i] < 0) mapFreqToPixH[i] = 0;
                if (mapFreqToPixL[i] >= nFreq) mapFreqToPixL[i] = nFreq - 1;
                if (mapFreqToPixL[i] < 0) mapFreqToPixL[i] = 0;
//                Log.i(TAG, "init(): [" + i + "]  L = " + axis.pixelNoZoomFromV((i-0.5f)*dFreq) + "  H = " + axis.pixelNoZoomFromV((i+0.5f)*dFreq));
            }
            if (axis.vLowerBound > axis.vUpperBound) {
                // swap mapFreqToPixL and mapFreqToPixH
                int[] tmpV = mapFreqToPixL;
                mapFreqToPixL = mapFreqToPixH;
                mapFreqToPixH = tmpV;
            }
        }

        void fill(double[] db) {
            if (db.length - 1 != nFreq) {
                Log.e(TAG, "full(): WTF");
                return;
            }
            int bmP0 = bmPt * nFreq;
            double maxDB;
            int i = 1;  // skip DC component(i = 0).
            while (i <= nFreq) {
                maxDB = db[i];
                int j = i + 1;
                while (j <= nFreq && mapFreqToPixL[i] + 1 == mapFreqToPixH[j]) {
                    // If multiple frequency points map to one pixel, show only the maximum.
                    if (db[j] > maxDB) maxDB = db[j];
                    j++;
                }
                int c = colorFromDB(maxDB);
                for (int k = mapFreqToPixL[i]; k < mapFreqToPixH[i]; k++) {
                    bm[bmP0 + k] = c;
                }
                i = j;
            }
            bmPt++;
            if (bmPt >= nTime) bmPt = 0;
        }

        void draw(Canvas c) {
            if (bm.length == 0) return;
            if (showModeSpectrogram == TimeAxisMode.SHIFT) {
                System.arraycopy(bm, 0, bmShiftCache, (nTime - bmPt) * nFreq, bmPt * nFreq);
                System.arraycopy(bm, bmPt * nFreq, bmShiftCache, 0, (nTime - bmPt) * nFreq);
                c.drawBitmap(bmShiftCache, 0, nFreq, 0.0f, 0.0f,
                        nFreq, nTime, false, smoothBmpPaint);
            } else {
                c.drawBitmap(bm, 0, nFreq, 0.0f, 0.0f,
                        nFreq, nTime, false, smoothBmpPaint);
            }
        }
    }

    private LogSegFreqSpectrogramBMP logSegBmp = new LogSegFreqSpectrogramBMP();

    private class LogSegFreqSpectrogramBMP {
        final static String TAG = "LogSeg..:";
        int nFreq = 0;
        int nTime = 0;
        int[] bm = new int[0];   // elememts are in "time major" order.
        int[] bmShiftCache = new int[0];
        int bmPt = 0;
        double[] iFreqToPix = new double[0];
        double[] pixelAbscissa = new double[0];
        double[] freqAbscissa = new double[0];
        int bmpWidth = 0;
        final double incFactor = 2;

        void init(int _nFreq, int _nTime, ScreenPhysicalMapping _axis) {
            if (_nFreq == 0 || _nTime == 0 || Math.max(_axis.vLowerBound, _axis.vUpperBound) == 0) {
                return;
            }
            // Note that there is limit for bmpWidth, i.e. Canvas.getMaximumBitmapHeight
            // https://developer.android.com/reference/android/graphics/Canvas.html#getMaximumBitmapHeight%28%29
            // Seems that this limit is at about 4096.
            // In case that this limit is reached, we might break down pixelAbscissa[] to smaller pieces.
            bmpWidth = (int)(_nFreq * incFactor * 2.0); // the extra factor (2.0) here is for sharper image.
            if (bm.length != bmpWidth * _nTime) {
                bm = new int[bmpWidth * _nTime];
                bmShiftCache = new int[bm.length];
            }
            if (nFreq != _nFreq || nTime != _nTime) {
                Arrays.fill(bm, 0);
                bmPt = 0;
            }  // else only update axis and mapping
            nFreq = _nFreq;
            nTime = _nTime;

            double maxFreq = Math.max(_axis.vLowerBound, _axis.vUpperBound);
            double minFreq = maxFreq / nFreq;
            double dFreq   = maxFreq / nFreq;

            int nSegment = (int)(Math.log((maxFreq + 0.1)/minFreq) / Math.log(incFactor)) + 1;
            Log.d(TAG, "nFreq = " + nFreq + "  dFreq = " + dFreq + "  nSegment = " + nSegment + "  bmpWidth = " + bmpWidth);

            pixelAbscissa = new double[nSegment + 1];
            freqAbscissa  = new double[nSegment + 1];
            pixelAbscissa[0] = 0;
            freqAbscissa[0] = minFreq;  // should be minFreq
            //Log.v(TAG, "pixelAbscissa[" + 0 + "] = " + pixelAbscissa[0] + "  freqAbscissa[i] = " + freqAbscissa[0]);
            Log.v(TAG, "pixelAbscissa[" + 0 + "] = " + pixelAbscissa[0]);
            for (int i = 1; i <= nSegment; i++) {
                /**  Mapping [0, 1] -> [fmin, fmax]
                 *   /  fmax  \ x
                 *   | ------ |   * fmin
                 *   \  fmin  /
                 *   This makes the "pixels"(x) more uniformly map to frequency points in logarithmic scale.
                 */
                pixelAbscissa[i] = (pow(maxFreq/minFreq, (double)i/nSegment) * minFreq - minFreq) / (maxFreq - minFreq);
                pixelAbscissa[i] = Math.floor(pixelAbscissa[i] * bmpWidth);   // align to pixel boundary
                freqAbscissa [i] = pixelAbscissa[i] / bmpWidth * (maxFreq-minFreq) + minFreq;
                Log.v(TAG, "pixelAbscissa[" + i + "] = " + pixelAbscissa[i] + "  freqAbscissa[i] = " + freqAbscissa[i]);
            }

            // Map between [pixelAbscissa[i-1]..pixelAbscissa[i]] and [freqAbscissa[i-1]..freqAbscissa[i]]
            iFreqToPix = new double[nFreq+1];
            iFreqToPix[0] = 0;
            double eps = 1e-7;  // 7 + log10(8192) < 15
            int iF = 1;
            ScreenPhysicalMapping axisSeg = new ScreenPhysicalMapping(1.0f, (float)minFreq, (float)maxFreq, ScreenPhysicalMapping.Type.LOG);
            for (int i = 1; i <= nSegment; i++) {
                axisSeg.setNCanvasPixel((float)Math.round(pixelAbscissa[i] - pixelAbscissa[i-1]));  // should work without round()
                axisSeg.setBounds((float)freqAbscissa[i-1], (float)freqAbscissa[i]);
                Log.v(TAG, "axisSeg[" + i + "] .nC = " + axisSeg.nCanvasPixel + "  .vL = " + axisSeg.vLowerBound + "  .vU = " + axisSeg.vUpperBound);
                while ((iF + 0.5) * dFreq <= freqAbscissa[i] + eps) {
                    // upper bound of the pixel position of frequency point iF
                    iFreqToPix[iF] = axisSeg.pixelFromV((float)((iF + 0.5) * dFreq)) + pixelAbscissa[i-1];
//                    Log.d(TAG, "seg = " + i + "  iFreqToPix[" + iF + "] = " + iFreqToPix[iF]);
                    iF++;
                }
            }
            if (iF < nFreq) {  // last point
                iFreqToPix[nFreq] = pixelAbscissa[nSegment];
            }
        }

        double[] dbPixelMix = new double[0];

        void fill(double[] db) {
            if (db.length - 1 != nFreq) {
                Log.e(TAG, "full(): WTF");
                return;
            }
            if (dbPixelMix.length != bmpWidth) {
                dbPixelMix = new double[bmpWidth];
            }
            Arrays.fill(dbPixelMix, 0.0);
            double b0 = iFreqToPix[0];
            for (int i = 1; i <= nFreq; i++) {
                // assign color to pixel iFreqToPix[i-1] .. iFreqToPix[i]
                double b1 = iFreqToPix[i];
                if ((int)b0 == (int)b1) {
                    dbPixelMix[(int)b0] += db[i] * (b1 - b0);
                    continue;
                }
                if (b0 % 1 != 0) {  // mix color
                    //dbPixelMix[(int)b0] += db[i] * (1 - b0 % 1);  // dB mean
                    double db0 = db[i-1];  // i should > 1
                    double db1 = db[i];
                    dbPixelMix[(int)b0] = 10 * log10(pow(10, db0/10)*(b0 % 1) + pow(10, db1/10)*(1 - b0 % 1));  // energy mean
                }
                for (int j = (int)Math.ceil(b0); j < (int)b1; j++) {
                    dbPixelMix[j] = db[i];
                }
//                if (b1 % 1 > 0) {  // avoid out of bound (b1 == bmpWidth)
//                    dbPixelMix[(int) b1] += db[i] * (b1 % 1);
//                }
                b0  = b1;
            }
            int bmP0 = bmPt * bmpWidth;
            for (int i = 0; i < bmpWidth; i++) {
                bm[bmP0 + i] = colorFromDB(dbPixelMix[i]);
            }
            bmPt++;
            if (bmPt >= nTime) bmPt = 0;
        }

        String st1old;  // for debug
        String st2old;  // for debug

        void draw(Canvas c) {
            if (bm.length == 0 || axisFreq.nCanvasPixel == 0) {
                Log.d(TAG, "draw(): what.....");
                return;
            }
            int i1 = pixelAbscissa.length - 1;
            String st1 = "draw():  pixelAbscissa["+(i1-1)+"]="+pixelAbscissa[i1-1]+"  pixelAbscissa["+i1+"]="+pixelAbscissa[i1]+"  bmpWidth="+bmpWidth;
            String st2 = "draw():  axis.vL="+axisFreq.vLowerBound+"  axis.vU="+axisFreq.vUpperBound +"  axisFreq.nC="+axisFreq.nCanvasPixel+"  nTime="+nTime;
            if (!st1.equals(st1old)) {
                Log.v(TAG, st1);
                Log.v(TAG, st2);
                st1old = st1;
                st2old = st2;
            }
            int[] bmTmp = bm;
            if (showModeSpectrogram == TimeAxisMode.SHIFT) {
                System.arraycopy(bm, 0, bmShiftCache, (nTime - bmPt) * bmpWidth, bmPt * bmpWidth);
                System.arraycopy(bm, bmPt * bmpWidth, bmShiftCache, 0, (nTime - bmPt) * bmpWidth);
                bmTmp = bmShiftCache;
            }
            for (int i = 1; i < pixelAbscissa.length; i++) {  // draw each segmentation
                c.save();
                float f1 = (float) freqAbscissa[i - 1];
                float f2 = (float) freqAbscissa[i];
                float p1 = axisFreq.pixelNoZoomFromV(f1);
                float p2 = axisFreq.pixelNoZoomFromV(f2);
                if (axisFreq.vLowerBound > axisFreq.vUpperBound) {
                    p1 = axisFreq.nCanvasPixel - p1;
                    p2 = axisFreq.nCanvasPixel - p2;
                }
                double widthFactor = (p2 - p1) / (pixelAbscissa[i] - pixelAbscissa[i - 1]) * (bmpWidth / axisFreq.nCanvasPixel);
                // Log.v(TAG, "draw():  f1=" + f1 + "  f2=" + f2 + "  p1=" + p1 + "  p2=" + p2 + "  widthFactor=" + widthFactor + "  modeInt=" + axisFreq.mapType);
                c.scale((float) widthFactor, 1);
                c.drawBitmap(bmTmp, (int) pixelAbscissa[i - 1], bmpWidth, p1 / axisFreq.nCanvasPixel * bmpWidth / (float) widthFactor, 0.0f,
                        (int) (pixelAbscissa[i] - pixelAbscissa[i - 1]), nTime, false, smoothBmpPaint);
                c.restore();
            }
        }
    }
}