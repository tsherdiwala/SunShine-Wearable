/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.example.android.R;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunShineService extends CanvasWatchFaceService {

    private static final String TAG = SunShineService.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunShineService.Engine> mWeakReference;

        public EngineHandler(SunShineService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunShineService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mLightTextPaint;
        Paint mHorizontalRulePaint;
        Paint mTemperatureLowPaint, mTemperatureHighPaint;
        boolean mAmbient;
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mPadding;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        /**
         * Shared preference to store the weather information locally
         */
        private SimpleDateFormat mDateFormat;
        private SharedPreferences mSharedPreferences;
        private GoogleApiClient mGoogleApiClient;
        private int mTempHigh, mTempLow;
        private long mWeatherId;
        private Bitmap mWeatherConditionBitmap;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(SunShineService.this);
            mGoogleApiClient = new GoogleApiClient.Builder(SunShineService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .build();

            mDateFormat = new SimpleDateFormat("EEE, MMM dd YYYY", Locale.getDefault());


            loadData();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunShineService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunShineService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mPadding = resources.getDimension(R.dimen.padding);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(
                    ContextCompat.getColor(SunShineService.this, R.color.digital_text),
                    0, //is being set in onApplyWindowInsets
                    Paint.Align.CENTER
            );

            mLightTextPaint = createTextPaint(
                    ContextCompat.getColor(SunShineService.this, R.color.digital_text_light),
                    resources.getDimension(R.dimen.date_text_size),
                    Paint.Align.CENTER
            );

            mHorizontalRulePaint = new Paint();
            mHorizontalRulePaint.setColor(
                    ContextCompat.getColor(SunShineService.this, R.color.digital_text_light)
            );

            mTemperatureLowPaint = createTextPaint(
                    ContextCompat.getColor(SunShineService.this, R.color.digital_text_light),
                    resources.getDimension(R.dimen.temp_text_size),
                    Paint.Align.LEFT
            );

            mTemperatureHighPaint = createTextPaint(
                    ContextCompat.getColor(SunShineService.this, R.color.digital_text),
                    resources.getDimension(R.dimen.temp_text_size),
                    Paint.Align.LEFT
            );


            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, float textSize, Paint.Align align) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTextSize(textSize);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextAlign(align);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunShineService.this.registerReceiver(mTimeZoneReceiver, filter);
            mGoogleApiClient.connect();
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunShineService.this.unregisterReceiver(mTimeZoneReceiver);
            mGoogleApiClient.disconnect();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunShineService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            int hour = mCalendar.get(Calendar.HOUR_OF_DAY);
            String hourString;
            if (hour < 10) {
                hourString = getString(R.string.zero_prefix, hour);
            } else {
                hourString = String.valueOf(hour);
            }

            int minute = mCalendar.get(Calendar.MINUTE);
            String minuteString;
            if (minute < 10) {
                minuteString = getString(R.string.zero_prefix, minute);
            } else {
                minuteString = String.valueOf(minute);
            }


            String text = getString(R.string.time_format, hourString, minuteString);
            canvas.drawText(text, bounds.width() / 2, mYOffset, mTextPaint);

            if (!mAmbient) {
                String date = mDateFormat.format(mCalendar.getTime());

                float dateY = mYOffset + mLightTextPaint.getTextSize();

                canvas.drawText(date.toUpperCase(), bounds.width() / 2, dateY, mLightTextPaint);

                float halfWidth = bounds.width() / 2;

                float hrY = dateY + mPadding;

                canvas.drawLine((float) 0.75 * halfWidth, hrY, (float) 1.25 * halfWidth, hrY, mLightTextPaint);

                if (mTempHigh != 0 && mTempLow != 0 && mWeatherConditionBitmap != null) {


                    canvas.drawBitmap(
                            mWeatherConditionBitmap,
                            bounds.width() / 2 - mWeatherConditionBitmap.getWidth() - mPadding,
                            dateY + mPadding,
                            mLightTextPaint
                    );

                    //Temp low and temp high both have same text size
                    float tempY = dateY
                            + mPadding
                            + (mWeatherConditionBitmap.getHeight() - mTemperatureLowPaint.getTextSize()) / 2
                            + mTemperatureLowPaint.getTextSize();

                    String tempHigh = getString(R.string.temperature_format, mTempHigh);
                    canvas.drawText(
                            tempHigh,
                            bounds.width() / 2,
                            tempY,
                            mTemperatureHighPaint
                    );

                    String tempLow = getString(R.string.temperature_format, mTempLow);
                    canvas.drawText(
                            tempLow,
                            bounds.width() / 2 + mTemperatureLowPaint.measureText(tempHigh) + mPadding,
                            tempY,
                            mTemperatureLowPaint
                    );


                } else {

                    float noDataY = hrY + mPadding + mTemperatureLowPaint.getTextSize();

                    canvas.drawText(
                            getString(R.string.no_weather),
                            bounds.width() / 2,
                            noDataY,
                            mTemperatureLowPaint
                    );
                }


            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG,"GoogleApiClient connected");

            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "Data changed");
            for (int i = 0; i < dataEventBuffer.getCount(); i++) {
                DataEvent event = dataEventBuffer.get(i);
                DataItem item = event.getDataItem();
                Log.d(TAG, "Data changed item: "+item.getUri());
                if (Constants.Data.PATH.compareTo(item.getUri().getPath()) == 0) {
                    DataMap map = DataMapItem.fromDataItem(item).getDataMap();
                    double tempHigh = map.getDouble(Constants.Data.WEATHER_TEMP_HIGH);
                    double tempLow = map.getDouble(Constants.Data.WEATHER_TEMP_LOW);
                    long id = map.getLong(Constants.Data.WEATHER_ID);

                    Log.d(TAG, "High, low, id: "+tempHigh+","+tempLow+","+id);

                    saveData((int) tempHigh, (int) tempLow, id);
                    loadData();
                    invalidate();
                }
            }
        }

        private void loadData() {
            mTempHigh = mSharedPreferences.getInt(Constants.SP.WEATHER_HIGH_I, 0);
            mTempLow = mSharedPreferences.getInt(Constants.SP.WEATHER_LOW_I, 0);
            mWeatherId = mSharedPreferences.getLong(Constants.SP.WEATHER_ID_L, 0);

            int drawableResId = 0;

            int choice = (int) (mWeatherId / 100);
            if (choice == 2) {
                drawableResId = R.drawable.ic_storm;
            } else if (choice == 3) {
                drawableResId = R.drawable.ic_light_rain;
            } else if (choice == 5) {
                drawableResId = R.drawable.ic_rain;
            } else if (choice == 6) {
                drawableResId = R.drawable.ic_snow;
            } else if (mWeatherId == 800) {
                drawableResId = R.drawable.ic_clear;
            } else if (mWeatherId == 801) {
                drawableResId = R.drawable.ic_light_clouds;
            } else if (choice == 8) {
                drawableResId = R.drawable.ic_cloudy;
            }

            if (drawableResId != 0) {
                mWeatherConditionBitmap
                        = BitmapFactory.decodeResource(getResources(), drawableResId);
            }

        }

        private void saveData(int tempHigh, int tempLow, long id) {
            SharedPreferences.Editor editor = mSharedPreferences.edit();
            editor.putInt(Constants.SP.WEATHER_HIGH_I, tempHigh);
            editor.putInt(Constants.SP.WEATHER_LOW_I, tempLow);
            editor.putLong(Constants.SP.WEATHER_ID_L, id);
            editor.apply();
        }
    }
}
