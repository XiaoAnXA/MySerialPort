package com.xa.myserialport;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.进入界面就开始搜索串口（没搜索到就一直显示精度条）
 * 2.显示设备
 * 3.开始通信
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    public final static String TAG = MainActivity.class.getSimpleName();

    public List<Msg> mMsgs = new ArrayList<>();

    private UsbManager mUsbManager;

    public Button mBtnSend;
    public EditText mEtMsg;

    private RecyclerView mRecyclerView;
    private MsgAdapter mMsgAdapter;
    public AlertDialog mAlertDialogSearch;

    public ListView mListView;
    public ArrayAdapter mArrayAdapter;
    public List<String> mSerialPorts = new ArrayList<>();
    public ProgressBar progressBar;

    public static UsbSerialPort ports;
    List<UsbSerialPort> oldResult = new ArrayList<>();
    List<UsbSerialPort> mPorts = new ArrayList<>();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private static final int MESSAGE_REFRESH = 101;
    private static final long REFRESH_TIMEOUT_MILLIS = 5000;

    private SerialInputOutputManager mSerialIoManager;
    public static boolean isReConnection = false;

    /***
     * 接收数据
     * 以及设备出现问题的监听事件
     */
    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "连接中断", Toast.LENGTH_SHORT).show();
                            isReConnection = true;
                            //showSearch();
                        }
                    });
                }

                @Override
                public void onNewData(final byte[] data) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            final String message = "Read " + data.length + " bytes: \n"
                                    + HexDump.dumpHexString(data) + "\n\n";
                            showReceive(message);
                        }
                    });
                }
            };

    /**
     * 不断的刷新
     */
    @SuppressLint("HandlerLeak")
    private final Handler



            mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_REFRESH:
                    Log.e(TAG, "handleMessage: " );
                    refreshDeviceList();
                    mHandler.sendEmptyMessageDelayed(MESSAGE_REFRESH, REFRESH_TIMEOUT_MILLIS);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }

    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu){
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        showSearch();
        initView();
    }

    private void showSearch(){
        progressBar = new ProgressBar(MainActivity.this);
        mListView = new ListView(MainActivity.this);
        mArrayAdapter = new ArrayAdapter(MainActivity.this,android.R.layout.simple_list_item_1,mSerialPorts);
        mListView.setAdapter(mArrayAdapter);
        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                try {
                    if(ports !=null){
                        ports.close();
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
                mPorts.addAll(oldResult);
                ports = mPorts.get(position);
                connectionPort();
            }
        });
        mAlertDialogSearch = new AlertDialog.Builder(MainActivity.this)
                .setTitle("搜索")
                .setMessage("正在搜索串口设备...")
                .setView(mListView)
                .setCancelable(false)
                .show();
    }

    private void initView() {
        mBtnSend = findViewById(R.id.btn_send);
        mBtnSend.setOnClickListener(this);
        mEtMsg = findViewById(R.id.et_msg);
        mRecyclerView = findViewById(R.id.recy_msg);
        mMsgAdapter = new MsgAdapter(mMsgs);
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(linearLayoutManager);
        mRecyclerView.setAdapter(mMsgAdapter);
    }

    @SuppressLint("StaticFieldLeak")
    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.btn_send:
                new AsyncTask<Void, Void, Boolean>(){
                    @Override
                    protected Boolean doInBackground(Void... voids) {
                        String content = mEtMsg.getText().toString();
                        byte[] data = content.getBytes();
                        try {
                            ports.write(data,1000);
                        } catch (IOException e) {
                            e.printStackTrace();
                            return false;
                        }
                        return true;
                    }
                    @Override
                    protected void onPostExecute(Boolean isSent) {
                        if(isSent){
                            String content = mEtMsg.getText().toString();
                            Msg msg = new Msg(content,Msg.TYPE_SENT);
                            mMsgs.add(msg);
                            mMsgAdapter.notifyItemInserted(mMsgs.size()-1);
                            mRecyclerView.scrollToPosition(mMsgs.size()-1);
                            mEtMsg.setText("");
                        }
                    }
                }.execute();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item){
        switch (item.getItemId()) {
            case R.id.action_cart://监听菜单按钮
                showSearch();

                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public void showReceive(String content){
        Msg msg = new Msg(content,Msg.TYPE_RECEIVED);
        mMsgs.add(msg);
        mMsgAdapter.notifyItemInserted(mMsgs.size()-1);
        mRecyclerView.scrollToPosition(mMsgs.size()-1);
    }

    @SuppressLint("StaticFieldLeak")
    private void refreshDeviceList() {
        new AsyncTask<Void, Void, List<UsbSerialPort>>() {
            @Override
            protected List<UsbSerialPort> doInBackground(Void... params) {
                Log.d(TAG, "Refreshing device list ...");
                SystemClock.sleep(1000);
                final List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);
                final List<UsbSerialPort> result = new ArrayList<UsbSerialPort>();
                for (final UsbSerialDriver driver : drivers) {
                    final List<UsbSerialPort> ports = driver.getPorts();
                    Log.d(TAG, String.format("+ %s: %s port%s",
                            driver, Integer.valueOf(ports.size()), ports.size() == 1 ? "" : "s"));
                    result.addAll(ports);
                }
                return result;
            }
            @Override
            protected void onPostExecute(List<UsbSerialPort> result) {
                //Toast.makeText(MainActivity.this,"result"+result.size(),Toast.LENGTH_SHORT).show();
                if(result.size() > 0){
                    mSerialPorts.clear();
                    oldResult.addAll(result);
                    for(UsbSerialPort usbSerialPort: result){
                        final UsbSerialDriver driver = usbSerialPort.getDriver();
                        final UsbDevice device = driver.getDevice();
                        final String subtitle = driver.getClass().getSimpleName();
                        mSerialPorts.add(subtitle);
                    }
                    mArrayAdapter.notifyDataSetChanged();
                    mAlertDialogSearch.setView(mListView);
                }else{
                   mAlertDialogSearch.setView(progressBar);
                }
            }
        }.execute((Void) null);
    }

    protected void connectionPort() {
        super.onResume();
        Log.d(TAG, "Resumed, port=" + ports);
        if (ports == null) {
            Toast.makeText(MainActivity.this,"获得设备失败",Toast.LENGTH_SHORT).show();
        } else {
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            UsbDeviceConnection connection = usbManager.openDevice(ports.getDriver().getDevice());
            if (connection == null) {
                Toast.makeText(MainActivity.this,"连接失败",Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                ports.open(connection);
                ports.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
//                showStatus(mDumpTextView, "CD  - Carrier Detect", sPort.getCD());
//                showStatus(mDumpTextView, "CTS - Clear To Send", sPort.getCTS());
//                showStatus(mDumpTextView, "DSR - Data Set Ready", sPort.getDSR());
//                showStatus(mDumpTextView, "DTR - Data Terminal Ready", sPort.getDTR());
//                showStatus(mDumpTextView, "DSR - Data Set Ready", sPort.getDSR());
//                showStatus(mDumpTextView, "RI  - Ring Indicator", sPort.getRI());
//                showStatus(mDumpTextView, "RTS - Request To Send", sPort.getRTS());
            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                Toast.makeText(MainActivity.this,"打开连接失败",Toast.LENGTH_SHORT).show();
                //showSearch();
                try {
                    ports.close();
                } catch (IOException e2) {

                }
                ports = null;
                return;
            }
        }
        onDeviceStateChange();
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (ports != null) {
            mSerialIoManager = new SerialInputOutputManager(ports, mListener);
            mExecutor.submit(mSerialIoManager);
            Toast.makeText(MainActivity.this,"连接成功",Toast.LENGTH_SHORT).show();
            mAlertDialogSearch.dismiss();
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.sendEmptyMessage(MESSAGE_REFRESH);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeMessages(MESSAGE_REFRESH);
    }
}
