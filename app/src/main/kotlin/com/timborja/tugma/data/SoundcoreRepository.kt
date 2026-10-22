package com.timborja.tugma.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.edit
import com.timborja.tugma.bt.soundcore.FakeSoundcoreQ30
import com.timborja.tugma.bt.soundcore.SoundcoreDevice
import com.timborja.tugma.bt.soundcore.SoundcoreModel
import com.timborja.tugma.bt.soundcore.SoundcoreModels
import com.timborja.tugma.bt.soundcore.SoundcoreUuids
import com.timborja.tugma.core.CommandLog
import java.util.UUID

/** A paired device that might be a Soundcore, with Tugma's best model guess. */
data class SoundcoreCandidate(val address: String, val name: String, val guess: SoundcoreModel?, val uuidHint: String?)

/**
 * Finds and connects to Soundcore headphones over RFCOMM. The protocol comes from OpenSCQ30;
 * Tugma hasn't been tested against real Soundcore hardware yet.
 */
class SoundcoreRepository(
    private val context: Context,
    private val log: CommandLog,
) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("soundcore", Context.MODE_PRIVATE)

    val demoCandidate = SoundcoreCandidate(DEMO_ADDRESS, "Soundcore Life Q30 (demo)", SoundcoreModels.byId("A3028"), null)

    @SuppressLint("MissingPermission") // callers check BluetoothRepository.hasPermission()
    fun candidate(address: String, name: String): SoundcoreCandidate {
        if (address == DEMO_ADDRESS) return demoCandidate
        val uuids = runCatching { adapter?.getRemoteDevice(address)?.uuids?.map { it.uuid } }.getOrNull().orEmpty()
        val hint = SoundcoreUuids.modelHint(uuids)
        return SoundcoreCandidate(address, name, SoundcoreModels.guess(name, hint), hint)
    }

    fun isLikelySoundcore(name: String): Boolean = SoundcoreModels.looksLikeSoundcore(name)

    /** The model the user picked for this device, else the guess. */
    fun modelFor(c: SoundcoreCandidate): SoundcoreModel? =
        prefs.getString(c.address, null)?.let(SoundcoreModels::byId) ?: c.guess

    fun rememberModel(address: String, model: SoundcoreModel) = prefs.edit { putString(address, model.id) }

    /** Opens the RFCOMM channel (vendor service if advertised, else SPP) and returns a device session. */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, model: SoundcoreModel): SoundcoreDevice {
        if (address == DEMO_ADDRESS) {
            return SoundcoreDevice(model, FakeSoundcoreQ30(latencyMillis = 80), log, "demo-q30")
        }
        val device = adapter?.getRemoteDevice(address) ?: error("Bluetooth is unavailable")
        val uuid: UUID = SoundcoreUuids.pick(device.uuids?.map { it.uuid }.orEmpty())
        return SoundcoreDevice(model, openRfcomm(context, address, uuid), log, address)
    }

    companion object {
        const val DEMO_ADDRESS = "DEMO:Q30"
    }
}
