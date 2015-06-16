package com.nebososo.dolphindroid;

import android.annotation.SuppressLint;
import android.support.annotation.NonNull;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.gesture.GestureOverlayView;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class ControllerActivity extends Activity implements SensorEventListener {

    private long lastBackPress = 0;
    private Toast backToast;
    private static final int totalBackPresses = 3;
    private static final long maxDelayBetweenBackPresses = 500;
    private int backPresses = 0;
    private PowerManager.WakeLock wl;
    private DatagramSocket udpSocket;
    private final byte[] sendBuffer = new byte[27];
    private final ScheduledExecutorService sendExecutor = Executors.newSingleThreadScheduledExecutor();
    private DatagramPacket sendPacket;
    private final AtomicButtonMask buttonMask = new AtomicButtonMask();
    private final Map<Integer, Integer> maskMap = new HashMap<>();
    private final AtomicAccelerometerData accelerometer = new AtomicAccelerometerData();
    private final float[] localAccelerometer = new float[3];
    private float lastX = 0.f;
    private float lastY = 0.f;
    private final AtomicIRData ir = new AtomicIRData();
    private final float[] localIR = new float[2];

    @SuppressLint("ShowToast")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);
        backToast = Toast.makeText(getApplicationContext(), null, Toast.LENGTH_SHORT);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        //noinspection deprecation
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "dolphindroid");

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_GAME);

        GestureOverlayView gestureIR = (GestureOverlayView) findViewById(R.id.gesture_ir);
        System.out.println(gestureIR.getMeasuredWidth() + "x" + gestureIR.getHeight());
        gestureIR.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_DOWN) {
                    float ratio = 2.f/Math.max(v.getWidth(), v.getHeight());
                    float x = event.getX() * ratio;
                    float y = event.getY() * ratio;

                    if (action == MotionEvent.ACTION_MOVE) {
                        ir.add(x - lastX, -(y - lastY));                    }

                    lastX = x;
                    lastY = y;
                }
                return true;
            }
        });

        maskMap.put(R.id.button_1, 1);
        maskMap.put(R.id.button_2, 1 << 1);
        maskMap.put(R.id.button_a, 1 << 2);
        maskMap.put(R.id.button_b, 1 << 3);
        maskMap.put(R.id.button_plus, 1 << 4);
        maskMap.put(R.id.button_minus, 1 << 5);
        maskMap.put(R.id.button_home, 1 << 6);
        maskMap.put(R.id.button_up, 1 << 7);
        maskMap.put(R.id.button_down, 1 << 8);
        maskMap.put(R.id.button_left, 1 << 9);
        maskMap.put(R.id.button_right, 1 << 10);
        maskMap.put(R.id.button_ul, 1 << 7 | 1 << 9);
        maskMap.put(R.id.button_ur, 1 << 7 | 1 << 10);
        maskMap.put(R.id.button_dl, 1 << 8 | 1 << 9);
        maskMap.put(R.id.button_dr, 1 << 8 | 1 << 10);

        for (Map.Entry<Integer, Integer> entry : maskMap.entrySet()) {
            Button b = (Button) findViewById(entry.getKey());
            b.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                        buttonMask.xor(maskMap.get(v.getId()));
                    }
                    return true;
                }
            });
        }

        Intent intent = getIntent();
        final String serverAddress = intent.getStringExtra("address");
        final int serverPort = intent.getIntExtra("port", 0);

        Runnable setupSendRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    udpSocket = new DatagramSocket();
                }
                catch (SocketException e) {
                    disconnect(getResources().getString(R.string.socket_failed));
                    return;
                }

                sendBuffer[0] = (byte) 0xde;
                /* Accelerometer,buttons and IR */
                sendBuffer[2] = (byte) 0x7;

                try {
                    sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length,
                            InetAddress.getByName(serverAddress), serverPort);
                }
                catch (UnknownHostException e) {
                    disconnect(getResources().getString(R.string.resolv_failed));
                    return;
                }

                scheduleSend();
            }
        };

        sendExecutor.schedule(setupSendRunnable, 0, TimeUnit.MILLISECONDS);
    }

    private void scheduleSend() {
        Runnable sendRunnable = new Runnable() {
            @Override
            public void run() {
                int mask = buttonMask.get();
                accelerometer.get(localAccelerometer);
                ir.get(localIR);

                int offset = 3;
                for (int i = 0; i < 3; i++) {
                    /* Divide by Earth's gravity to get the acceleration in Gs */
                    int b = (int) ((localAccelerometer[i] / 9.80665f) * 1024.f * 1024.f);
                    sendBuffer[offset+3] = (byte) (b & 0xff);
                    sendBuffer[offset+2] = (byte) ((b >> 8) & 0xff);
                    sendBuffer[offset+1] = (byte) ((b >> 16) & 0xff);
                    sendBuffer[offset] = (byte) ((b >> 24) & 0xff);

                    offset += 4;
                }

                sendBuffer[offset+3] = (byte) (mask & 0xff);
                sendBuffer[offset+2] = (byte) ((mask >> 8) & 0xff);
                sendBuffer[offset+1] = (byte) ((mask >> 16) & 0xff);
                sendBuffer[offset] = (byte) ((mask >> 24) & 0xff);
                offset += 4;

                for (int i = 0; i < 2; i++) {
                    int b = (int) (localIR[i] * 1024.f * 1024.f);
                    sendBuffer[offset+3] = (byte) (b & 0xff);
                    sendBuffer[offset+2] = (byte) ((b >> 8) & 0xff);
                    sendBuffer[offset+1] = (byte) ((b >> 16) & 0xff);
                    sendBuffer[offset] = (byte) ((b >> 24) & 0xff);

                    offset += 4;
                }


                try {
                    udpSocket.send(sendPacket);
                }
                catch (IOException e) {
                }
            }
        };

        sendExecutor.scheduleAtFixedRate(sendRunnable, 33, 33, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        accelerometer.set(event.values);
    }

    @Override
    protected void onResume() {
        wl.acquire();
        super.onResume();
    }

    @Override
    protected void onPause() {
        wl.release();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sendExecutor.shutdown();
        try {
            sendExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
        udpSocket.close();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastBackPress > maxDelayBetweenBackPresses) {
                backPresses = 1;
            }
            else {
                backPresses++;
            }
            lastBackPress = currentTime;

            if (backPresses == totalBackPresses) {
                backToast.cancel();
                disconnect(null);
            }
            else {

                backToast.setText((totalBackPresses - backPresses)
                        + getResources().getString(R.string.presses_to_go));
                backToast.show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void disconnect(String errorMessage) {
        Intent intent = new Intent(this, ConnectionActivity.class);
        if (errorMessage != null) {
            intent.putExtra("error", errorMessage);
        }
        startActivity(intent);
        finish();
    }
}

class AtomicButtonMask {
    private int mask = 0;

    public synchronized int get() {
        return mask;
    }

    public synchronized void xor(int v) {
        mask ^= v;
    }
}

class AtomicAccelerometerData {
    private final float[] v = new float[3];

    public synchronized void get(float r[]) {
        r[0] = v[0];
        r[1] = v[1];
        r[2] = v[2];
    }

    public synchronized void set(float n[]) {
        v[0] = -n[0];
        v[1] = -n[1];
        v[2] = n[2];
    }
}

class AtomicIRData {
    private final float[] v = new float[2];

    public AtomicIRData() {
        v[0] = 0.5f;
        v[1] = 0.5f;
    }

    public synchronized void get(float r[]) {
        r[0] = v[0];
        r[1] = v[1];
    }

    public synchronized void add(float x, float y) {
        v[0] += x;
        v[0] = Math.max(Math.min(v[0], 1.1f), -1.1f);
        v[1] += y;
        v[1] = Math.max(Math.min(v[1], 1.1f), -1.1f);
    }
}