package com.example.clover_go

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import timber.log.Timber

class DeviceAdapter(
    private val devices: MutableList<BluetoothDevice> = mutableListOf(),
    private val onDeviceClicked: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.deviceName)
        val deviceAddress: TextView = view.findViewById(R.id.deviceAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.device_item, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]

        try {
            holder.deviceName.text = device.name ?: "Unknown Device"
        } catch (e: Exception) {
            holder.deviceName.text = "Unknown Device"
            Timber.e(e, "Error getting device name")
        }

        holder.deviceAddress.text = device.address

        holder.itemView.setOnClickListener {
            onDeviceClicked(device)
        }
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<BluetoothDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    fun addDevice(device: BluetoothDevice) {
        // Check if device is already in the list by address
        val existingDevice = devices.find { it.address == device.address }
        if (existingDevice == null) {
            Timber.d("Adding device to list: ${device.address}")
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }
}