package com.timborja.tugma.data

import android.content.Context
import androidx.core.content.edit
import com.timborja.tugma.bt.redmibuds.FakeRedmiBuds
import com.timborja.tugma.bt.redmibuds.RedmiBudsDevice
import com.timborja.tugma.bt.redmibuds.RedmiBudsModel
import com.timborja.tugma.bt.redmibuds.RedmiBudsModels
import com.timborja.tugma.bt.redmibuds.RedmiBudsUuids
import com.timborja.tugma.core.CommandLog

/**
 * Xiaomi / Redmi Buds over RFCOMM. The protocol (including the challenge handshake) comes from
 * Gadgetbridge; Tugma hasn't been tested against real Redmi Buds yet.
 */
class RedmiBudsRepository(private val context: Context, private val log: CommandLog) {
    private val prefs = context.getSharedPreferences("redmibuds", Context.MODE_PRIVATE)

    fun isLikely(name: String): Boolean = RedmiBudsModels.looksLikeRedmiBuds(name)

    fun modelFor(address: String, name: String): RedmiBudsModel? =
        prefs.getString(address, null)?.let(RedmiBudsModels::byName)
            ?: if (address == DEMO_ADDRESS) RedmiBudsModels.byName("Redmi Buds 5 Pro") else RedmiBudsModels.guess(name)

    fun rememberModel(address: String, model: RedmiBudsModel) = prefs.edit { putString(address, model.name) }

    suspend fun connect(address: String, model: RedmiBudsModel): RedmiBudsDevice =
        if (address == DEMO_ADDRESS) {
            RedmiBudsDevice(model, FakeRedmiBuds(latencyMillis = 60), log, "demo-redmi")
        } else {
            RedmiBudsDevice(model, openRfcomm(context, address, RedmiBudsUuids.CONTROL), log, address)
        }

    companion object {
        const val DEMO_ADDRESS = "DEMO:REDMI"
        const val DEMO_NAME = "Redmi Buds 5 Pro (demo)"
    }
}
