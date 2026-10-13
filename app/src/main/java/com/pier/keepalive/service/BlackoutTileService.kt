package com.pier.keepalive.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class BlackoutTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (BlackoutOverlayService.isOverlayActive) {
            BlackoutOverlayService.stopBlackout(this)
        } else {
            BlackoutOverlayService.startBlackout(this)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isActive = BlackoutOverlayService.isOverlayActive

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "最黑省电(运行中)" else "一键最黑"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_power_off)
        tile.updateTile()
    }
}
