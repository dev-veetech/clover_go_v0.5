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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.clover.sdk.gosdk.GoSdk
import com.clover.sdk.gosdk.core.domain.model.ReaderInfo
import com.clover.sdk.gosdk.model.PayRequest
import com.clover.sdk.gosdk.payment.domain.model.CardReaderStatus
import com.example.clover_go.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var goSdk: GoSdk

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 30000 // 30 seconds scan timeout

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val PERMISSION_REQUEST_CODE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Get GoSdk instance from Application class
        goSdk = (application as CloverGoApplication).getGoSdk()

        // Setup UI
        setupDeviceList()
        setupButtonListeners()

        // Observe Card Reader Status
        observeCardReaderStatus()

        // Request Bluetooth Permissions
        requestBluetoothPermissions()
        promptEnableBluetooth()
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter { device ->
            val readerInfo = ReaderInfo(
                bluetoothName = device.name ?: "Unknown Device",
                bluetoothIdentifier = device.address,
                readerType = ReaderInfo.ReaderType.RP450
            )
            connectToCloverReader(readerInfo)
        }

        binding.deviceList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupButtonListeners() {
        binding.scanButton.setOnClickListener {
            scanForCloverReaders()
        }

        binding.processPaymentButton.setOnClickListener {
            processPayment()
        }
    }

    private fun observeCardReaderStatus() {
        lifecycleScope.launch {
            goSdk.observeCardReaderStatus().collectLatest { status ->
                handleCardReaderStatus(status)
            }
        }
    }

    private fun handleCardReaderStatus(status: CardReaderStatus) {
        runOnUiThread {
            Timber.d("Card Reader Status: $status")
            when (status) {
                is CardReaderStatus.Connected -> {
                    binding.statusText.text = "Reader Connected"
                    binding.processPaymentButton.isEnabled = true
                }
                is CardReaderStatus.Disconnected -> {
                    binding.statusText.text = "Reader Disconnected"
                    binding.processPaymentButton.isEnabled = false
                }
                is CardReaderStatus.Ready -> {
                    binding.statusText.text = "Reader Ready"
                    binding.processPaymentButton.isEnabled = true
                }
                is CardReaderStatus.Connecting -> {
                    binding.statusText.text = "Connecting to Reader..."
                }
                is CardReaderStatus.BatteryPercentChanged -> {
                    binding.statusText.text = "Battery: ${status.readerInfo.battery}%"
                }
                else -> {
                    binding.statusText.text = "Reader Status: $status"
                }
            }
        }
    }

    private fun scanForCloverReaders() {
        // Validate prerequisites
        if (!hasRequiredPermissions()) {
            requestBluetoothPermissions()
            return
        }

        // Update UI
        binding.scanButton.text = "Scanning..."
        binding.statusText.text = "Scanning for Clover Go devices..."
        isScanning = true

        // Clear previous device list
        deviceAdapter.updateDevices(emptyList())

        // Launch scanning
        lifecycleScope.launch {
            try {
                goSdk.scanForReaders()
                    .catch { error ->
                        Timber.e(error, "Scan Error Details")
                        runOnUiThread {
                            binding.statusText.text = "Scan Error: ${error.localizedMessage}"
                            binding.scanButton.text = "SCAN FOR DEVICES"
                            isScanning = false
                        }
                    }
                    .collectLatest { reader ->
                        runOnUiThread {
                            Timber.d("Reader Found: ${reader.bluetoothName}")
                            Toast.makeText(
                                this@MainActivity,
                                "Found Reader: ${reader.bluetoothName}",
                                Toast.LENGTH_SHORT
                            ).show()

                            // Optional: Auto-connect to a specific reader
                            if (reader.bluetoothName.contains("110034")) {
                                connectToCloverReader(reader)
                            }
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Scanning Exception")
                runOnUiThread {
                    binding.statusText.text = "Scan Failed: ${e.localizedMessage}"
                    binding.scanButton.text = "SCAN FOR DEVICES"
                    isScanning = false
                }
            }
        }

        // Scan timeout
        handler.postDelayed({
            if (isScanning) {
                runOnUiThread {
                    binding.scanButton.text = "SCAN FOR DEVICES"
                    binding.statusText.text = "Scan Completed"
                    isScanning = false
                }
            }
        }, SCAN_PERIOD)
    }

    private fun connectToCloverReader(reader: ReaderInfo) {
        lifecycleScope.launch {
            try {
                Timber.d("Attempting to connect to reader: ${reader.bluetoothName}")
                goSdk.connect(reader)
                // Note: Connection status will be handled by observeCardReaderStatus()
            } catch (e: Exception) {
                Timber.e(e, "Reader Connection Error")
                runOnUiThread {
                    binding.statusText.text = "Connection Failed: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun processPayment() {
        val amountText = binding.amountInput.text.toString()
        if (amountText.isEmpty()) {
            Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val amountCents = (amountText.toDouble() * 100).toLong()

            val payRequest = PayRequest(
                final = true,
                capture = true,
                amount = amountCents,
                taxAmount = 0L,
                tipAmount = 0L,
                externalPaymentId = "pay-${System.currentTimeMillis()}",
                externalReferenceId = "invoice-${System.currentTimeMillis()}"
            )

            // Format amount for display
            val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
            val amountStr = currencyFormat.format(amountCents / 100.0)
            binding.statusText.text = "Processing payment of $amountStr..."

            lifecycleScope.launch {
                goSdk.chargeCardReader(payRequest)
                    .catch { error ->
                        Timber.e(error, "Payment Processing Error")
                        runOnUiThread {
                            binding.statusText.text = "Payment Error: ${error.localizedMessage}"
                            Toast.makeText(this@MainActivity,
                                "Payment failed: ${error.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .collectLatest { state ->
                        Timber.d("Charge state: $state")
                        runOnUiThread {
                            binding.statusText.text = "Payment Status: $state"
                        }
                    }
            }
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Timber.d("All permissions granted")
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Timber.w("Some permissions were denied")
                Toast.makeText(
                    this,
                    "Some permissions were denied. App may not work properly.",
                    Toast.LENGTH_LONG
                ).show()
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
}