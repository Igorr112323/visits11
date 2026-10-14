"""Source-level contract checks; do not claim radio/hardware coverage."""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[1]
def read(path): return (ROOT/path).read_text()
class ContractTests(unittest.TestCase):
    def test_service_and_characteristic_match(self):
        paths = ['android/student/src/main/java/ru/visits11/student/BleStudent.java',
                 'android/app/src/main/java/ru/visits11/teacher/MainActivity.java',
                 'ios/Sources/ContentView.swift', 'firmware/Visits11Ble/Visits11Ble.ino']
        for path in paths:
            text=read(path)
            for uuid in ['F0391101-0203-0405-0607-08090A0B0C0D','F0391102-0203-0405-0607-08090A0B0C0D']:
                self.assertIn(uuid,text,path)
    def test_no_ios_nfc_usage_description(self):
        self.assertNotIn("NFCReader", read("ios/Sources/Info.plist"))

    def test_no_android_nfc_permissions_or_service(self):
        for module in ['app','student']:
            path=f'android/{module}/src/main/AndroidManifest.xml'
            ET.fromstring(read(path))
            self.assertNotIn('NFC',read(path))
            self.assertNotIn('HOST_APDU',read(path))
        for file in (ROOT/'android').rglob('*.java'):
            self.assertNotIn('import android.nfc',file.read_text())
    def test_board_handshake_and_power(self):
        self.assertIn('V11ESP32\\t1',read('Services/BoardLink.cs'))
        self.assertIn('V11ESP32\\t1',read('firmware/Visits11Ble/Visits11Ble.ino'))
        self.assertIn('id==activeId',read('firmware/Visits11Ble/Visits11Ble.ino'))
        self.assertIn('lastPcAt>4500',read('firmware/Visits11Ble/Visits11Ble.ino'))
    def test_same_proximity_filter(self):
        # Телефоны читают порог из advertising-пакета терминала;
        # без него (старая прошивка) оба приложения откатываются на строгий -46.
        android=read('android/student/src/main/java/ru/visits11/student/BleStudent.java')
        self.assertIn('getManufacturerSpecificData((short) 0xA111)',android)
        self.assertIn('result.getRssi() < gateFor(result)',android)
        self.assertIn('return -46',android)
        ios=read('ios/Sources/ContentView.swift')
        self.assertIn('touchOnlyRssiThreshold = -46',ios)
        self.assertIn('CBAdvertisementData.manufacturerDataKey',ios)
        self.assertIn('rssiValue >= gate',ios)
    def test_late_account_response_guard(self):
        self.assertIn('Objects.equals(exchangeToken,token.get())',read('android/student/src/main/java/ru/visits11/student/BleStudent.java'))
        self.assertIn('exchangeToken == prefs.string(forKey: keyToken)',read('ios/Sources/ContentView.swift'))
    def test_two_explicit_logout_sites_only(self):
        android=read('android/student/src/main/java/ru/visits11/student/MainActivity.java')
        self.assertEqual(android.count('.remove(KEY_TOKEN)'),2)
        ios=read('ios/Sources/ContentView.swift')
        self.assertEqual(ios.count('prefs.removeObject(forKey: keyToken)'),1)
        self.assertEqual(ios.count('savedToken = ""'),2) # declaration and long press
    def test_ios_no_double_dismiss(self):
        self.assertNotIn('picker.dismiss',read('ios/Sources/QrLogin.swift'))
        self.assertEqual(read('ios/Sources/ContentView.swift').count('onDismiss: pickerDismissed'),2)
    def test_student_tap_never_opens_system_settings(self):
        android = read('android/student/src/main/java/ru/visits11/student/BleStudent.java')
        self.assertNotIn('ACTION_BLUETOOTH_SETTINGS', android)
        handler = android.split('// Never throw the student into the phone')[1].split('}')[0]
        self.assertIn('wanted = true', handler)
        self.assertIn('ui.post(tick)', handler)

    def test_success_releases_board_and_readies_next_mark(self):
        # Плата освобождается мгновенно; «Вы отмечены» 5 с (внутри, без таймера на экране),
        # потом приложение само готово к повторной отметке — без закрытия.
        android = read('android/student/src/main/java/ru/visits11/student/BleStudent.java')
        finish = android.split('private void finish(int code,String name)')[1].split('\n    private void close')[0]
        self.assertIn('close()', finish)
        self.assertIn('code==0?5000:2500', finish)
        self.assertNotIn('wanted = false', finish)
        self.assertNotIn('removeCallbacks', finish)
        tick = android.split('private final Runnable tick')[1].split('private final ScanCallback')[0]
        self.assertIn('Поднесите телефон к терминалу', tick)
        ios = read('ios/Sources/ContentView.swift')
        case0 = ios.split('        case 0:')[1].split('        case 2:')[0]
        self.assertIn('awaitingReturnToScan = true', case0)
        self.assertIn('cooldownUntil = Date().addingTimeInterval(5.0)', case0)
        self.assertNotIn('scanningWanted = false', case0)
        tail = ios.split('let delay = max(0.5, cooldownUntil.timeIntervalSinceNow)')[1]
        self.assertIn('self.statusOverride = ""', tail)
        self.assertIn('self.beginScanIfNeeded()', tail)

    def test_active_rollcall_survives_exe_restart(self):
        vm = read('ViewModels/LessonViewModel.cs')
        self.assertIn('_database.BeginLesson(', vm)
        self.assertIn('_database.SaveMark(', vm)
        self.assertIn('_database.RemoveMark(', vm)
        self.assertIn('_database.FinishLesson(', vm)
        self.assertIn('ResumePendingLesson()', vm)
        self.assertIn('RollcallState = StateActive', vm.split('private void ResumePendingLesson')[1])
        db = read('Services/DatabaseService.cs')
        self.assertIn('WHERE FinishedAt IS NULL', db)
        self.assertIn('UPDATE Lessons SET FinishedAt=StartedAt WHERE Id=@l', db)

    def test_power_selector_uses_app_theme(self):
        xaml = read('Views/MainWindow.xaml')
        self.assertIn('Style="{StaticResource Widget.PowerCombo}"', xaml)
        controls = read('Styles/Controls.xaml')
        self.assertIn('x:Key="Widget.PowerCombo"', controls)
        self.assertIn('DynamicResource Brush.TextDim', controls.split('x:Key="Widget.PowerCombo"')[1].split('<Style x:Key="Widget.')[0])

    def test_terminal_checks_phone_rssi_before_queueing(self):
        board = read('firmware/Visits11Ble/Visits11Ble.ino')
        write = board.split('void onWrite(')[1].split('static void gapEvent(')[0]
        self.assertNotIn('xQueueSend', write)
        self.assertIn('checkingProximity = true', write)
        self.assertIn('REQUIRED_SAMPLES = 3', board)
        self.assertIn('rssiRequestId == activeId', board)
        self.assertIn('rssi < phoneRssiLimit()', board)
        self.assertIn('static const int limits[4] = { MIN_PHONE_RSSI, -50, -54, -62 }', board)
        self.assertIn('phoneRssiLimit() + txDbm(powerLevel) + 12', board)
        self.assertIn('terminal->disconnect(dropId)', board)

    def test_board_advertises_distance_gate(self):
        # Плата публикует порог расстояния для телефонов (manufacturer data 0x11 0xA1)
        # и сразу перепубликует его, когда учитель меняет уровень: телефоны подключаются
        # не дальше, чем плата может отметить, — без «слишком далеко» на дальних уровнях.
        board=read('firmware/Visits11Ble/Visits11Ble.ino')
        self.assertIn('adv.setCompleteServices(BLEUUID(SERVICE))', board)
        self.assertIn('adv.setManufacturerData', board)
        self.assertIn('0xA1', board)
        self.assertIn('a->setAdvertisementData(adv)', board)
        power=board.split('static void power(int level)')[1].split('static void command(')[0]
        self.assertIn('refreshAdvertising()', power)
        self.assertIn('BLEDevice::stopAdvertising()', power)
        self.assertIn('BLEDevice::startAdvertising()', power)
        android=read('android/student/src/main/java/ru/visits11/student/BleStudent.java')
        self.assertIn('gateFor(ScanResult result)', android)
        ios=read('ios/Sources/ContentView.swift')
        self.assertIn('Int8(bitPattern: mfg[mfg.startIndex+2])', ios)

    def test_standalone_exe_defaults_to_board_without_marker(self):
        project = ET.fromstring(read('Visits11.csproj'))
        mode = project.find('./PropertyGroup/TerminalMode')
        self.assertEqual(mode.text, 'Board')
        source = read('Services/PhoneLink.cs')
        self.assertNotIn('board.mode', source)
        default = source.split('#else')[1].split('#endif')[0]
        self.assertIn('new BoardLink', default)
        self.assertIn('_board.Start()', default)
        workflow = read('.github/workflows/ble.yml')
        self.assertIn('-p:TerminalMode=Board', workflow)
        self.assertIn('-p:TerminalMode=Phone', workflow)
        self.assertNotIn('touch delivery/ESP32/board.mode', workflow)
        self.assertIn('cp delivery/ESP32/KubGAU-Journal.exe .', workflow)

    def test_search_watchdog_and_honest_status(self):
        android = read('android/student/src/main/java/ru/visits11/student/BleStudent.java')
        tick = android.split('private final Runnable tick')[1].split('private final ScanCallback')[0]
        self.assertIn('elapsedRealtime()-scanSince>10000', tick)
        self.assertIn('Терминал не найден — поднесите телефон ближе', tick)
        self.assertIn('scanSince=SystemClock.elapsedRealtime()', tick)
        self.assertIn('perm && !wasPermitted', tick)
        ios = read('ios/Sources/ContentView.swift')
        self.assertIn('scanStartedAt = Date()', ios)
        self.assertIn('Терминал не найден — поднесите телефон ближе', ios)
        self.assertIn('searchHintWork?.cancel()', ios)

    def test_first_launch_fresh_scan(self):
        android = read('android/student/src/main/java/ru/visits11/student/BleStudent.java')
        self.assertIn('void rescanNow()', android)
        self.assertIn('wasPermitted = perm', android)
        activity = read('android/student/src/main/java/ru/visits11/student/MainActivity.java')
        handler = activity.split('public void onRequestPermissionsResult')[1].split('pickQrImage')[0]
        self.assertIn('requestCode == 78', handler)
        self.assertIn('ble.rescanNow()', handler)

    def test_vibration_only_on_recorded_mark(self):
        # Вход (камера/галерея) и неудачные попытки не вибрируют; вибрирует только запись в журнал.
        activity = read('android/student/src/main/java/ru/visits11/student/MainActivity.java')
        login = activity.split('applyQrPayload')[1].split('private static final class Credentials')[0]
        self.assertNotIn('vibrate()', login)
        marked = activity.split('private void handleReply')[1].split('private void vibrate')[0]
        self.assertIn('vibrate()', marked.split('REPLY_UNKNOWN_QR')[0])
        self.assertNotIn('vibrate()', marked.split('REPLY_UNKNOWN_QR')[1])
        camera = read('ios/Sources/CameraScanner.swift')
        self.assertNotIn('kSystemSoundID_Vibrate', camera)
        ios = read('ios/Sources/ContentView.swift')
        login = ios.split('private func apply(_ text: String?)')[1].split('}')[0]
        self.assertNotIn('notificationOccurred', login)
        case0 = ios.split('        case 0:')[1].split('        case 2:')[0]
        self.assertIn('kSystemSoundID_Vibrate', case0)
        # только область finishExchange после case 0: там вибраций не должно быть вовсе
        rest = ios.split('        case 2:')[1].split('let delay = max(0.5, cooldownUntil.timeIntervalSinceNow)')[0]
        self.assertNotIn('notificationOccurred', rest)

    def test_board_connection_indicator_and_com_path(self):
        board = read('Services/BoardLink.cs')
        self.assertIn('public event Action<bool>? StateChanged', board)
        self.assertIn('StateChanged?.Invoke(true)', board)
        self.assertIn('StateChanged?.Invoke(false)', board)
        self.assertIn('int.TryParse(com.AsSpan(3), out var n) && n >= 10', board)
        self.assertIn('VID_067B', board)
        phone = read('Services/PhoneLink.cs')
        self.assertIn('public event Action<bool>? BoardConnected', phone)
        self.assertIn('_board.StateChanged += state => BoardConnected?.Invoke(state)', phone)
        vm = read('ViewModels/MainViewModel.cs')
        self.assertIn('ShowBoardIndicator = phone.UsesBoard', vm)
        self.assertIn('public bool BoardConnected', vm)
        xaml = read('Views/MainWindow.xaml')
        top = xaml.split('Text="Bluetooth"')[0].split('HorizontalAlignment="Right"')[1]
        self.assertIn('Ellipse', top)
        self.assertIn('BoardConnected', top)
        self.assertIn('Brush.Green', top)
        self.assertIn('Brush.Red', top)
        self.assertIn('ShowBoardIndicator', top)

    def test_no_release_asset_deletion(self):
        for path in (ROOT/'.github/workflows').glob('*.yml'):
            self.assertNotIn('api -X DELETE',path.read_text())
if __name__=='__main__': unittest.main()
