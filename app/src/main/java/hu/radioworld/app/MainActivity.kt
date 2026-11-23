package hu.radioworld.app

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvHeadsetStatus: TextView
    private lateinit var tvSdrStatus: TextView
    private lateinit var usbManager: UsbManager
    private lateinit var adapter: ArrayAdapter<String>
    private val stationList = mutableListOf<String>()

    private val ACTION_USB_PERMISSION = "hu.radioworld.app.USB_PERMISSION"
    private val PREFS_NAME = "RadioWorldOfflinePrefs"
    private val KEY_STATIONS = "saved_stations"

    private val defaultStations = listOf(
        "90,9 MHz – Tesztelt / Helyi adó",
        "88,3 MHz – Dankó Rádió (Tokaj)",
        "89,5 MHz – Rádió 1 (Budapest)",
        "92,7 MHz – Petőfi Rádió (Tokaj)",
        "94,8 MHz – Petőfi Rádió (Budapest)",
        "95,8 MHz – Sláger FM (Budapest)",
        "97,5 MHz – Kossuth Rádió (Tokaj)",
        "103,3 MHz – Retro Rádió (Budapest)",
        "105,3 MHz – Bartók Rádió (Budapest)",
        "107,8 MHz – Kossuth Rádió (Budapest)"
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            tvSdrStatus.text = "KAPCSOLÓDVA: ${it.productName ?: "RTL-SDR"} (VID:${it.vendorId})"
                            tvSdrStatus.setTextColor(0xFF00E676.toInt())
                        }
                    } else {
                        tvSdrStatus.text = "USB engedély megtagadva."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHeadsetStatus = findViewById(R.id.tvHeadsetStatus)
        tvSdrStatus = findViewById(R.id.tvSdrStatus)
        val btnLaunchXiaomiFm: Button = findViewById(R.id.btnLaunchXiaomiFm)
        val btnScanUsb: Button = findViewById(R.id.btnScanUsb)
        val etFreqInput: EditText = findViewById(R.id.etFreqInput)
        val btnAddFavorite: Button = findViewById(R.id.btnAddFavorite)
        val listStations: ListView = findViewById(R.id.listStations)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        loadStations()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, stationList)
        listStations.adapter = adapter

        // Rövid érintés: frekvencia másolása
        listStations.setOnItemClickListener { _, _, position, _ ->
            val selected = stationList[position]
            val freq = selected.substringBefore(" MHz").trim()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FM Frekvencia", freq))
            Toast.makeText(this, "Másolva: $freq – Indítsd a gyári rádiót!", Toast.LENGTH_SHORT).show()
        }

        // Hosszú nyomás: állomás törlése
        listStations.setOnItemLongClickListener { _, _, position, _ ->
            val item = stationList[position]
            AlertDialog.Builder(this)
                .setTitle("Állomás törlése")
                .setMessage("Biztosan törlöd ezt a listából?\n$item")
                .setPositiveButton("Törlés") { _, _ ->
                    stationList.removeAt(position)
                    saveStations()
                    adapter.notifyDataSetChanged()
                }
                .setNegativeButton("Mégse", null)
                .show()
            true
        }

        // Új kedvenc mentése
        btnAddFavorite.setOnClickListener {
            val text = etFreqInput.text.toString().trim()
            if (text.isNotEmpty()) {
                stationList.add(0, text)
                saveStations()
                adapter.notifyDataSetChanged()
                etFreqInput.text.clear()
                Toast.makeText(this, "Mentve a kedvencek közé!", Toast.LENGTH_SHORT).show()
            }
        }

        btnLaunchXiaomiFm.setOnClickListener { launchXiaomiFmRadio() }
        btnScanUsb.setOnClickListener { scanForRtlSdr() }

        checkHeadsetAntenna()
        scanForRtlSdr()
    }

    private fun loadStations() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_STATIONS, null)
        stationList.clear()
        if (saved != null && saved.isNotEmpty()) {
            stationList.addAll(saved.split("|||"))
        } else {
            stationList.addAll(defaultStations)
            saveStations()
        }
    }

    private fun saveStations() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATIONS, stationList.joinToString("|||")).apply()
    }

    override fun onResume() {
        super.onResume()
        checkHeadsetAntenna()
    }

    private fun checkHeadsetAntenna() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasWiredHeadset = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (hasWiredHeadset) {
            tvHeadsetStatus.text = "ANTENNA OK: 3,5 mm-es vezetékes fülhallgató csatlakoztatva."
            tvHeadsetStatus.setTextColor(0xFF00E676.toInt())
        } else {
            tvHeadsetStatus.text = "FIGYELEM: Nincs bedugva 3,5 mm-es fülhallgató (FM antenna)!"
            tvHeadsetStatus.setTextColor(0xFFFF5252.toInt())
        }
    }

    private fun launchXiaomiFmRadio() {
        val packagesToTry = listOf("com.miui.fm", "com.mediatek.fmradio")
        for (pkg in packagesToTry) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }
        }
        Toast.makeText(this, "A gyári FM rádió (com.miui.fm) nem található.", Toast.LENGTH_LONG).show()
    }

    private fun scanForRtlSdr() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            tvSdrStatus.text = "Nincs USB OTG eszköz csatlakoztatva."
            tvSdrStatus.setTextColor(0xFFBDBDBD.toInt())
            return
        }
        for (device in deviceList.values) {
            if (device.vendorId == 3034) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(device, permissionIntent)
                tvSdrStatus.text = "RTL-SDR felismerve! Engedély kérése..."
                return
            }
        }
        tvSdrStatus.text = "USB eszköz észlelve, de nem RTL-SDR vevő."
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
