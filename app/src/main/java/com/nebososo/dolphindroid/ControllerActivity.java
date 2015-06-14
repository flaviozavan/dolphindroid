package com.nebososo.dolphindroid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class ControllerActivity extends Activity {

    private long lastBackPress = 0;
    private Toast backToast;
    private final int totalBackPresses = 3;
    private final long maxDelayBetweenBackPresses = 500;
    private int backPresses = 0;
    private PowerManager.WakeLock wl;
    private DatagramSocket udpSocket;
    private byte[] sendBuffer = new byte[27];
    private ScheduledExecutorService sendExecutor = Executors.newSingleThreadScheduledExecutor();
    private DatagramPacket sendPacket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);
        backToast = Toast.makeText(getApplicationContext(), null, Toast.LENGTH_SHORT);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "dolphindroid");

        Intent intent = getIntent();
        String serverAddress = intent.getStringExtra("address");
        int serverPort = intent.getIntExtra("port", 0);

        try {
            udpSocket = new DatagramSocket();
        }
        catch (SocketException e) {
            e.printStackTrace();
        }

        sendBuffer[0] = (byte) 0xde;
        sendBuffer[1] = (byte) 0x0;
        /* Accelerometer,buttons and IR */
        sendBuffer[2] = (byte) 0x7;

        try {
            sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length,
                    InetAddress.getByName(serverAddress), serverPort);
        }
        catch (UnknownHostException e) {
            System.out.println(serverAddress);
            e.printStackTrace();
        }

        Runnable sendRunnable = new Runnable() {
            @Override
            public void run() {
                sendBuffer[18] = (byte) 0x1;

                try {
                    udpSocket.send(sendPacket);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        sendExecutor.scheduleAtFixedRate(sendRunnable, 50, 50, TimeUnit.MILLISECONDS);
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
                startActivity(new Intent(this, ConnectionActivity.class));
                finish();
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
}
