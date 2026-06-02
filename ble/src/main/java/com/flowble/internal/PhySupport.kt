package com.flowble.internal

import android.os.Build
import androidx.annotation.RequiresApi
import com.flowble.model.BlePhy
import com.flowble.model.PhyRequest
import com.flowble.model.PhyType

@RequiresApi(Build.VERSION_CODES.O)
internal fun PhyRequest.txMask(): Int = txPhys.toAndroidMask()

@RequiresApi(Build.VERSION_CODES.O)
internal fun PhyRequest.rxMask(): Int = rxPhys.toAndroidMask()

@RequiresApi(Build.VERSION_CODES.O)
internal fun Set<PhyType>.toAndroidMask(): Int {
    return fold(0) { mask, phy -> mask or phy.toMask() }
}

@RequiresApi(Build.VERSION_CODES.O)
internal fun blePhyFromAndroid(txPhy: Int, rxPhy: Int): BlePhy {
    return BlePhy(
        txPhy = PhyType.fromAndroid(txPhy),
        rxPhy = PhyType.fromAndroid(rxPhy)
    )
}
