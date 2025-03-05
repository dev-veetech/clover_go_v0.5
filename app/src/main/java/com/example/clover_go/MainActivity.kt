package com.example.clover_go

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.clover_go.databinding.ActivityMainBinding
import timber.log.Timber
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceAdapter

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false
    private var connectedDevice: BluetoothDevice? = null

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 10000 // Scan timeout (10 sec)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        promptEnableBluetooth()

        // Request permissions at startup
        requestBluetoothPermissions()

        // Setup device list
        deviceAdapter = DeviceAdapter { device ->
            connectToDevice(device)
        }

        binding.deviceList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        binding.scanButton.setOnClickListener {
            requestBluetoothPermissions()
            startBluetoothDiscovery()
            startBluetoothScan()
        }

        binding.processPaymentButton.setOnClickListener {
            val amountText = binding.amountInput.text.toString()
            if (amountText.isNotEmpty()) {
                try {
                    val amount = amountText.toDouble() * 100 // Convert to cents
                    processPayment(amount.toInt())
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()

        // Standard Bluetooth permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // Location permissions (required for Bluetooth scanning)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun promptEnableBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to enable Bluetooth", Toast.LENGTH_SHORT).show()
                Timber.e(e, "Error enabling Bluetooth")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous device list if not already scanning
        if (!isScanning) {
            deviceAdapter.updateDevices(emptyList())
        }

        // Update UI
        binding.scanButton.text = "Scanning..."
        binding.statusText.text = "Scanning for devices..."
        isScanning = true

        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
            Timber.d("BLE scan started")
        } catch (e: Exception) {
            Timber.e(e, "Error starting BLE scan")
        }

        // Stop scanning after SCAN_PERIOD
        handler.postDelayed({
            stopBluetoothScan()
        }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return

        // Register for discoveries
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(bluetoothReceiver, filter)

        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()
            Timber.d("Classic Bluetooth discovery started")
        } catch (e: Exception) {
            Timber.e(e, "Error starting Bluetooth discovery")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            runOnUiThread {
                Timber.d("BLE device found: ${device.address}, name: ${device.name ?: "Unknown"}")
                deviceAdapter.addDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("Scan failed with error code: $errorCode")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Scan failed: Error $errorCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    device?.let {
                        Timber.d("Classic Bluetooth device found: ${it.address}, name: ${it.name ?: "Unknown"}")
                        runOnUiThread {
                            deviceAdapter.addDevice(it)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Timber.d("Classic Bluetooth discovery finished")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        // Stop scanning if needed
        stopBluetoothScan()

        // Update UI
        binding.statusText.text = "Connecting to ${device.name ?: "Unknown Device"}..."

        // In a real implementation, you would use the Clover Go SDK to connect to the device
        // This is simplified for illustration

        // Simulate connection success after a delay
        handler.postDelayed({
            connectedDevice = device
            binding.statusText.text = "Connected to ${device.name ?: "Unknown Device"}"
            binding.processPaymentButton.isEnabled = true

            Toast.makeText(this, "Connected to ${device.name ?: "device"}", Toast.LENGTH_SHORT).show()
        }, 2000) // Simulate 2-second connection time
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        if (isScanning) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                Timber.d("BLE scan stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping BLE scan")
            }
        }

        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
                Timber.d("Classic Bluetooth discovery stopped")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping Bluetooth discovery")
        }

        binding.scanButton.text = "SCAN FOR DEVICES"
        isScanning = false
    }

    private fun processPayment(amountCents: Int) {
        if (connectedDevice == null) {
            Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show()
            return
        }

        // Format amount for display
        val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
        val amountStr = currencyFormat.format(amountCents / 100.0)

        binding.statusText.text = "Status: Processing payment of $amountStr..."

        // In a real implementation, you would use the Clover Go SDK to process the payment
        // This is simplified for illustration

        // Simulate payment processing
        handler.postDelayed({
            binding.statusText.text = "Status: Payment of $amountStr completed successfully!"
            Toast.makeText(this, "Payment processed successfully!", Toast.LENGTH_LONG).show()

            // Clear amount input
            binding.amountInput.text.clear()
        }, 3000) // Simulate 3-second payment processing
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // All permissions granted, we can proceed
                Timber.d("All requested permissions granted")
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                // Some permissions were denied
                Toast.makeText(this, "Some permissions were denied. App may not work properly.", Toast.LENGTH_LONG).show()
                Timber.w("Some permissions were denied")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isScanning) {
            stopBluetoothScan()
        }

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.w("Receiver not registered")
        }
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val PERMISSION_REQUEST_CODE = 2
    }
}