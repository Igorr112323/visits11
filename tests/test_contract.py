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
        self.assertIn('result.getRssi() < -46',read('android/student/src/main/java/ru/visits11/student/BleStudent.java'))
        self.assertIn('touchOnlyRssiThreshold = -46',read('ios/Sources/ContentView.swift'))
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
    def test_success_stops_automatic_reconnection(self):
        android = read('android/student/src/main/java/ru/visits11/student/BleStudent.java')
        success = android.split('if (valid && code == 0) {')[1].split('}')[0]
        self.assertIn('wanted = false', success)
        self.assertIn('ui.removeCallbacks(tick)', success)
        ios = read('ios/Sources/ContentView.swift').split('        case 0:')[1].split('        case 2:')[0]
        self.assertIn('scanningWanted = false', ios)
        self.assertIn('central?.stopScan()', ios)

    def test_terminal_checks_phone_rssi_before_queueing(self):
        board = read('firmware/Visits11Ble/Visits11Ble.ino')
        write = board.split('void onWrite(')[1].split('static void gapEvent(')[0]
        self.assertNotIn('xQueueSend', write)
        self.assertIn('checkingProximity = true', write)
        self.assertIn('REQUIRED_SAMPLES = 3', board)
        self.assertIn('rssiRequestId == activeId', board)
        self.assertIn('rssi < MIN_PHONE_RSSI', board)
        self.assertIn('terminal->disconnect(dropId)', board)

    def test_no_release_asset_deletion(self):
        for path in (ROOT/'.github/workflows').glob('*.yml'):
            self.assertNotIn('api -X DELETE',path.read_text())
if __name__=='__main__': unittest.main()
