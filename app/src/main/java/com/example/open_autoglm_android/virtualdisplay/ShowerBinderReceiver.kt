package com.example.open_autoglm_android.virtualdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.shower.IShowerService
import com.ai.assistance.shower.ShowerBinderContainer
import com.ai.assistance.showerclient.ShowerBinderRegistry

class ShowerBinderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOWER_BINDER_READY) return

        val container = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BINDER_CONTAINER, ShowerBinderContainer::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BINDER_CONTAINER) as? ShowerBinderContainer
        } ?: return

        val service = IShowerService.Stub.asInterface(container.binder) ?: return
        ShowerBinderRegistry.setService(service)
    }

    companion object {
        const val ACTION_SHOWER_BINDER_READY = "com.ai.assistance.operit.action.SHOWER_BINDER_READY"
        const val EXTRA_BINDER_CONTAINER = "binder_container"
    }
}

