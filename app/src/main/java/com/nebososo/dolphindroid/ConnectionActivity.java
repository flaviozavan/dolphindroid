package com.nebososo.dolphindroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.Vector;


public class ConnectionActivity extends Activity {
    private Button connectButton;
    private Button aboutButton;
    private Button customButton;
    private RadioGroup serverGroup;
    private ActiveServersList activeServers = new ActiveServersList();
    private Map<Integer, UdpwiiServer> localActiveServersList = new TreeMap<>();
    private Timer maintenanceTimer;
    private byte[] buffer = new byte[512];
    private DatagramSocket broadcastSocket;
    private DatagramPacket broadcastPacket = new DatagramPacket(buffer, buffer.length);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection);

        serverGroup = (RadioGroup) findViewById(R.id.server_group);
        connectButton = (Button) findViewById(R.id.connect_button);
        aboutButton = (Button) findViewById(R.id.about_button);
        customButton = (Button) findViewById(R.id.custom_button);

        AlertDialog.Builder aboutDialogBuilder = new AlertDialog.Builder(this);
        aboutDialogBuilder.setMessage(R.string.about_content);
        aboutDialogBuilder.setTitle(R.string.about);
        aboutDialogBuilder.setNegativeButton(R.string.cancel, null);
        final AlertDialog aboutDialog = aboutDialogBuilder.create();

        LayoutInflater inflater = getLayoutInflater();
        View customConnectionView = inflater.inflate(R.layout.custom_connection, null);

        AlertDialog.Builder customDialogBuilder = new AlertDialog.Builder(this);
        customDialogBuilder.setTitle(R.string.custom_connection);
        customDialogBuilder.setNegativeButton(R.string.cancel, null);
        customDialogBuilder.setPositiveButton(R.string.connect, null);
        customDialogBuilder.setView(customConnectionView);
        final AlertDialog customDialog = customDialogBuilder.create();

        aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aboutDialog.show();
            }
        });

        customButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                customDialog.show();
            }
        });

        final Intent controllerIntent = new Intent(this, ControllerActivity.class);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(controllerIntent);
                finish();
            }
        });

        try {
            broadcastSocket = new DatagramSocket(4431);
        } catch (SocketException e) {
            e.printStackTrace();
        }
        try {
            /* Hack for simulating a non-blocking socket */
            broadcastSocket.setSoTimeout(1);
        } catch (SocketException e) {
            e.printStackTrace();
        }

        final Context currentContext = this;

        TimerTask maintainServerListTask = new TimerTask() {
            @Override
            public void run() {
                activeServers.cleanup();

                for (;;) {
                    try {
                        broadcastSocket.receive(broadcastPacket);
                    } catch (SocketTimeoutException e) {
                        break;
                    } catch (IOException e) {
                        e.printStackTrace();
                        break;
                    }
                    if (UdpwiiServer.validatePacket(broadcastPacket)) {
                        activeServers.addServer(new UdpwiiServer(broadcastPacket));
                    }
                }

                 runOnUiThread(new Runnable() {
                     @Override
                     public void run() {
                         if (activeServers.getServerListIfChanged(localActiveServersList)) {
                             int checkedId = serverGroup.getCheckedRadioButtonId();
                             boolean removed = true;
                             serverGroup.removeAllViews();
                             for (Map.Entry<Integer, UdpwiiServer> entry
                                     : localActiveServersList.entrySet()){
                                 RadioButton serverRadio = new RadioButton(currentContext);
                                 serverRadio.setText(entry.getValue().name
                                         + " " + entry.getValue().index);
                                 serverRadio.setId(entry.getValue().id);
                                 serverGroup.addView(serverRadio);
                                 if (entry.getValue().id == checkedId) {
                                     removed = false;
                                 }
                             }
                             serverGroup.clearCheck();
                             if (!removed) {
                                 serverGroup.check(checkedId);
                             }
                         }
                     }
                 });
            }
        };
        maintenanceTimer = new Timer();
        maintenanceTimer.scheduleAtFixedRate(maintainServerListTask, 200, 200);
    }

    @Override
    protected void onDestroy() {
        maintenanceTimer.cancel();
        broadcastSocket.close();
        super.onDestroy();
    }
}

class ActiveServersList {
    private Map<Integer, UdpwiiServer> activeServers = new TreeMap<Integer, UdpwiiServer>();
    private boolean changed = false;

    public synchronized void addServer(UdpwiiServer server) {
        if (activeServers.get(server.id) == null) {
            changed = true;
        }
        activeServers.put(server.id, server);
    }

    public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, UdpwiiServer>> it = activeServers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, UdpwiiServer> entry = it.next();
            long timestamp = entry.getValue().timestamp;
            /* Entry is 5 seconds or older */
            if (now - timestamp >= 5000) {
                it.remove();
                changed = true;
            }
        }
    }

    public synchronized boolean getServerListIfChanged(Map<Integer, UdpwiiServer> servers) {
        if (!changed) {
            return false;
        }

        servers.clear();
        servers.putAll(activeServers);

        changed = false;
        return true;
    }
}

class UdpwiiServer {
    public int port;
    public int id;
    public int index;
    public String name;
    public long timestamp;
    public String address;

    public UdpwiiServer(DatagramPacket packet) {
        byte[] pbuf = packet.getData();
        int name_len = (int) (pbuf[6] & 0xff);

        id = (int) ((pbuf[1] & 0xff) << 8) | (int) (pbuf[2] & 0xff);
        index = (int) pbuf[3] + 1;
        port = (int) ((pbuf[4] & 0xff) << 8) | (int) (pbuf[5] & 0xff);
        name = new String(pbuf, 7, name_len);
        timestamp = System.currentTimeMillis();
        address = packet.getAddress().toString();
    }

    static public boolean validatePacket(DatagramPacket packet) {
        byte[] pbuf = packet.getData();

        if (packet.getLength() < 8) {
            return false;
        }

        int name_len = (int) (pbuf[6] & 0xff);
        if (packet.getLength() != 7 + name_len) {
            return false;
        }

        if (pbuf[0] != (byte) 0xdf) {
            return false;
        }

        if (pbuf[4] == 0 && pbuf[5] == 0) {
            return false;
        }

        if (pbuf[3] < 0 || pbuf[3] > 3) {
            return false;
        }

        return true;
    }
}
