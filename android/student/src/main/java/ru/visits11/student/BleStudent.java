package ru.visits11.student;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** All connection state belongs to the main thread. Old callbacks cannot log out a new account. */
final class BleStudent {
    private static final UUID SERVICE = UUID.fromString("F0391101-0203-0405-0607-08090A0B0C0D");
    private static final UUID CHAR = UUID.fromString("F0391102-0203-0405-0607-08090A0B0C0D");
    private final Activity activity;
    private final Supplier<String> token, device;
    private final BiConsumer<Integer,String> reply;
    private final Consumer<String> status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private boolean wanted, scanning, asked;
    private String exchangeToken;
    private byte[] payload;
    private int offset;
    private long retryAt, deadline, scanSince, lastSuccessAt;
    private boolean wasPermitted = false;

    BleStudent(Activity a, Supplier<String> t, Supplier<String> d, BiConsumer<Integer,String> r, Consumer<String> s) {
        activity=a; token=t; device=d; reply=r; status=s;
    }
    private BluetoothAdapter adapter() {
        BluetoothManager m = activity.getSystemService(BluetoothManager.class);
        return m == null ? null : m.getAdapter();
    }
    private boolean permitted() {
        if (Build.VERSION.SDK_INT >= 31) return activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
            && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    void requestEnable() {
        if (token.get() == null) return;
        if (!permitted()) {
            activity.requestPermissions(Build.VERSION.SDK_INT>=31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 78);
        } else {
            // Never throw the student into the phone's system settings: just resume the search.
            wanted = true;
            stopScan(); close();
            retryAt = SystemClock.elapsedRealtime() + 1500;
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
    }
    void start() {
        if (wanted || token.get()==null) return;
        wanted=true;
        if (!permitted() && !asked) { asked=true; requestEnable(); }
        ui.post(tick);
    }
    void stop() {
        wanted=false;
        ui.removeCallbacks(tick);
        stopScan();
        close();
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!wanted || token.get()==null) return;
            try {
                BluetoothAdapter a=adapter();
                if (a==null) {
                    status.accept("Bluetooth на этом телефоне недоступен");
                } else {
                    boolean perm = permitted();
                    if (perm && !wasPermitted) {
                        // Разрешение только что дали: первый поиск мог не стартовать — начинаем заново
                        stopScan(); close(); retryAt=0;
                    }
                    wasPermitted = perm;
                    if (!perm) status.accept("Разрешите Bluetooth — нажмите на надпись");
                    else if (!a.isEnabled()) { stopScan(); close(); status.accept("Включите Bluetooth в настройках телефона"); }
                    else if (Build.VERSION.SDK_INT<31 && !activity.getSystemService(LocationManager.class).isProviderEnabled(LocationManager.GPS_PROVIDER))
                        status.accept("Включите геолокацию для поиска Bluetooth (Android 8–11)");
                    else if (gatt!=null && SystemClock.elapsedRealtime()>deadline) finish(4, "");
                    else if (scanning && gatt==null && SystemClock.elapsedRealtime()-scanSince>10000) {
                        // Android умеет молча останавливать поиск без callbacks — принудительно перезапускаем
                        stopScan();
                        retryAt=SystemClock.elapsedRealtime()+300;
                    }
                    else if (scanning && gatt==null && SystemClock.elapsedRealtime()-scanSince>8000 && SystemClock.elapsedRealtime()-lastSuccessAt>8000)
                        status.accept("Терминал не найден — поднесите телефон ближе");
                    else if (gatt==null && !scanning && SystemClock.elapsedRealtime()>=retryAt) {
                        scanner=a.getBluetoothLeScanner();
                        if (scanner!=null) {
                            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
                                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
                            scanning=true;
                            scanSince=SystemClock.elapsedRealtime();
                            status.accept("Поднесите телефон к терминалу");
                        }
                    }
                }
            } catch (Exception e) { stopScan(); close(); retryAt=SystemClock.elapsedRealtime()+2000; }
            if (wanted) ui.postDelayed(this, 600);
        }
    };

    /** Свежий поиск: после выдачи разрешения и при входе. */
    void rescanNow() {
        if (!wanted || token.get() == null) return;
        stopScan();
        close();
        retryAt = 0;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }
    private final ScanCallback scan=new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            ui.post(() -> {
                if (!wanted || gatt!=null || token.get()==null || result.getRssi() < -46 || result.getRssi()>=0 || SystemClock.elapsedRealtime()<retryAt) return;
                stopScan(); exchangeToken=token.get(); deadline=SystemClock.elapsedRealtime()+8000;
                try { gatt=result.getDevice().connectGatt(activity,false,callback,BluetoothDevice.TRANSPORT_LE); }
                catch (Exception e) { close(); }
            });
        }
        @Override public void onScanFailed(int error) { ui.post(() -> { scanning=false; retryAt=SystemClock.elapsedRealtime()+2500; }); }
    };
    private void stopScan() {
        try { if(scanner!=null && scanning) scanner.stopScan(scan); } catch (Exception ignored) {}
        scanning=false; scanner=null; scanSince=0;
    }
    private boolean current(BluetoothGatt g) { return wanted && gatt==g && Objects.equals(exchangeToken,token.get()); }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) { ui.post(() -> {
            if (!current(g)) return;
            if(status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) { if(!g.discoverServices()) finish(4,""); }
            else if(state==BluetoothProfile.STATE_DISCONNECTED || status!=0) finish(4,"");
        }); }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) { ui.post(() -> {
            if(!current(g)) return;
            BluetoothGattService s=g.getService(SERVICE);
            characteristic=s==null ? null : s.getCharacteristic(CHAR);
            if(status!=0 || characteristic==null) { finish(4,""); return; }
            byte[] text=(exchangeToken+"\n"+device.get()).getBytes(StandardCharsets.UTF_8);
            payload=new byte[text.length+2]; payload[0]=1; System.arraycopy(text,0,payload,1,text.length); offset=0;
            writeNext(g);
        }); }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) { ui.post(() -> {
            if(!current(g)) return;
            if(status!=0) finish(4,""); else writeNext(g);
        }); }
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status) {
            byte[] value=c.getValue();
            ui.post(() -> {
                if(!current(g)) return;
                if(status!=0 || value==null || value.length==0) { finish(4,""); return; }
                int code=value[0]&255;
                if(code==255) poll(g);
                else if(code==0 || code==2 || code==4 || code==5 || code==6) finish(code,new String(value,1,value.length-1,StandardCharsets.UTF_8));
                else finish(4,"");
            });
        }
    };
    private void writeNext(BluetoothGatt g) {
        if(offset>=payload.length) { poll(g); return; }
        int end=Math.min(offset+20,payload.length);
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(Arrays.copyOfRange(payload,offset,end)); offset=end;
        if(!g.writeCharacteristic(characteristic)) finish(4,"");
    }
    private void poll(BluetoothGatt g) { ui.postDelayed(() -> {
        if(current(g) && !g.readCharacteristic(characteristic)) finish(4,"");
    },300); }
    private void finish(int code,String name) {
        boolean valid=wanted && Objects.equals(exchangeToken,token.get());
        // Связь с платой освобождается мгновенно — она сразу готова принять следующего студента.
        close();
        if (valid && code==0) lastSuccessAt=SystemClock.elapsedRealtime();
        // Успех: «Вы отмечены» висит 5 секунд (считает сама программа, таймера на экране нет),
        // затем обычный тик возобновляет поиск — повторная отметка без закрытия приложения.
        retryAt=SystemClock.elapsedRealtime()+(code==0?5000:2500);
        if(valid) reply.accept(code,name);
    }
    private void close() {
        BluetoothGatt old=gatt; gatt=null; characteristic=null; exchangeToken=null;
        if(old!=null) { try { old.disconnect(); old.close(); } catch(Exception ignored) {} }
    }
}
