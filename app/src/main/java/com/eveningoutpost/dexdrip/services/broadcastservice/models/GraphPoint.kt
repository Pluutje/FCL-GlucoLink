package com.eveningoutpost.dexdrip.services.broadcastservice.models

import android.os.Parcel
import android.os.Parcelable

/**
 * One point of a graph line sent to WatchDrip+. Same package, class name and
 * parcel layout as in xDrip+ (two floats: x, then y). Do not rename.
 */
class GraphPoint(val x: Float, val y: Float) : Parcelable {

    constructor(parcel: Parcel) : this(parcel.readFloat(), parcel.readFloat())

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeFloat(x)
        parcel.writeFloat(y)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GraphPoint> = object : Parcelable.Creator<GraphPoint> {
            override fun createFromParcel(parcel: Parcel): GraphPoint = GraphPoint(parcel)
            override fun newArray(size: Int): Array<GraphPoint?> = arrayOfNulls(size)
        }
    }
}
