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
import com.clover.sdk.gosdk.GoSdkConfiguration
import com.clover.sdk.gosdk.GoSdkCreator
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
    private var connectedDevice: BluetoothDevice? = null

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 10000 // Scan timeout (10 sec)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize GoSdk using the method from the documentation
        initializeGoSdk()

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
            scanForCloverReaders()
        }

        binding.processPaymentButton.setOnClickListener {
            val amountText = binding.amountInput.text.toString()
            if (amountText.isNotEmpty()) {
                try {
                    val amount = amountText.toDouble() * 100 // Convert to cents
                    processPayment(amount.toLong())
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
            }
        }

        // Observe card reader status
        lifecycleScope.launch {
            goSdk.observeCardReaderStatus().collectLatest { status ->
                handleCardReaderStatus(status)
            }
        }
    }

    private fun initializeGoSdk() {
        // Create GoSdk configuration using the provided parameters
        val config = GoSdkConfiguration.Builder(
            context = applicationContext,
            appId = packageName,
            appVersion = "1.0.0",
            apiKey = "YOUR_API_KEY", // Replace with your actual API key
            apiSecret = "YOUR_API_SECRET", // Replace with your actual API secret
            oAuthFlowAppSecret = "YOUR_OAUTH_FLOW_APP_SECRET", // Replace with your actual OAuth app secret
            oAuthFlowRedirectURI = "YOUR_OAUTH_FLOW_REDIRECT_URI", // Replace with your actual redirect URI
            oAuthFlowAppID = "YOUR_OAUTH_FLOW_APP_ID", // Replace with your actual OAuth app ID
            environment = GoSdkConfiguration.Environment.SANDBOX,
            reconnectLastConnectedReader = true
        ).build()

        // Initialize the GoSdk instance
        goSdk = GoSdkCreator.create(config)
    }

    private fun handleCardReaderStatus(status: CardReaderStatus) {
        Timber.d("Card reader status: $status")

        when (status) {
            is CardReaderStatus.BatteryPercentChanged -> {
                binding.statusText.text = "Battery: ${status.readerInfo.battery}%"
            }
            is CardReaderStatus.Connected -> {
                binding.statusText.text = "Status: Connected"
            }
            is CardReaderStatus.Connecting -> {
                binding.statusText.text = "Status: Connecting..."
            }
            is CardReaderStatus.Disconnected -> {
                binding.statusText.text = "Status: Disconnected"
                binding.processPaymentButton.isEnabled = false
            }
            is CardReaderStatus.Ready -> {
                binding.statusText.text = "Status: Ready to process payments"
                binding.processPaymentButton.isEnabled = true
            }
            else -> {
                binding.statusText.text = "Status: $status"
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

    private fun scanForCloverReaders() {
        // Update UI
        binding.scanButton.text = "Scanning..."
        binding.statusText.text = "Scanning for Clover Go devices..."
        isScanning = true

        // Clear previous device list
        deviceAdapter.updateDevices(emptyList())

        // Launch in a coroutine scope
        lifecycleScope.launch {
            goSdk.scanForReaders()
                .catch { error ->
                    Timber.e(error, "Error scanning for readers")
                    binding.statusText.text = "Scan error: ${error.message}"
                    binding.scanButton.text = "SCAN FOR DEVICES"
                    isScanning = false
                }
                .collectLatest { reader ->
                    Timber.d("Reader found: ${reader.bluetoothName}")

                    // Show a toast with the reader info
                    Toast.makeText(this@MainActivity,
                        "Found reader: ${reader.bluetoothName}",
                        Toast.LENGTH_SHORT).show()

                    // Connect to the reader if it matches a specific pattern
                    // Uncomment and modify this to auto-connect to a specific reader
                     if (reader.bluetoothName.contains("XXXXXX")) { // Last 6 digits of device serial
                         connectToCloverReader(reader)
                     }
                }
        }

        // Set a timeout to stop scanning
        handler.postDelayed({
            if (isScanning) {
                binding.scanButton.text = "SCAN FOR DEVICES"
                binding.statusText.text = "Scan completed"
                isScanning = false
            }
        }, SCAN_PERIOD)
    }

    private fun connectToCloverReader(reader: ReaderInfo) {
        binding.statusText.text = "Connecting to reader..."

        lifecycleScope.launch {
            try {
                // This assumes connect returns a Unit or similar non-Flow result
                goSdk.connect(reader)
                Timber.d("Connect request initiated")

                // The actual connection status will come through observeCardReaderStatus()
                // which we're already monitoring in onCreate
            } catch (e: Exception) {
                Timber.e(e, "Connection error")
                binding.statusText.text = "Connection failed: ${e.message}"
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

    private fun processPayment(amountCents: Long) {
        // Check if we have a CardReaderStatus.Ready state
        // For this example, we'll just proceed with the payment

        // Create a payment request
        val request = PayRequest(
            final = true, // true for Sales, false for Auth or PreAuth Transactions
            capture = true, // true for Sales, true for Auth, false for PreAuth Transactions
            amount = amountCents,
            taxAmount = 0L,
            tipAmount = 0L,
            externalPaymentId = "pay-${System.currentTimeMillis()}", // Unique ID
            externalReferenceId = "invoice-${System.currentTimeMillis()}" // Invoice number
        )

        // Format amount for display
        val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
        val amountStr = currencyFormat.format(amountCents / 100.0)
        binding.statusText.text = "Processing payment of $amountStr..."

        // Process the payment
        lifecycleScope.launch {
            goSdk.chargeCardReader(request)
                .catch { error ->
                    Timber.e(error, "Payment error")
                    binding.statusText.text = "Payment failed: ${error.message}"
                    Toast.makeText(this@MainActivity, "Payment failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                .collectLatest { state ->
                    Timber.d("Charge state: $state")
                    updateUIWithCardReaderState(state)
                }
        }
    }

    private fun updateUIWithCardReaderState(state: Any) {
        // This would be implemented based on the actual ChargeCardReaderState class structure
        binding.statusText.text = "Payment status: $state"

        // In a real implementation, you would handle different states appropriately
        // For example:
        // when (state) {
        //     is ChargeCardReaderState.OnPaymentComplete -> {
        //         binding.statusText.text = "Payment complete: ${state.response}"
        //         binding.amountInput.text.clear()
        //     }
        //     is ChargeCardReaderState.OnPaymentError -> {
        //         binding.statusText.text = "Payment error: ${state.error}"
        //     }
        //     is ChargeCardReaderState.OnReaderPaymentProgress -> {
        //         binding.statusText.text = "Payment progress: ${state.event}"
        //     }
        // }
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