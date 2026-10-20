package com.eveningoutpost.dexdrip.services.broadcastservice.models

import android.os.Parcel
import android.os.Parcelable

/**
 * One graph line sent to WatchDrip+: a list of [GraphPoint] and a colour.
 * Same package, class name and parcel layout as in xDrip+ (a list of points,
 * then the colour as an Int). Do not rename.
 */
class GraphLine(
    val values: List<GraphPoint> = emptyList(),
    val color: Int = 0
) : Parcelable {

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    constructor(parcel: Parcel) : this(
        (parcel.readArrayList(GraphPoint::class.java.classLoader) as? ArrayList<GraphPoint>) ?: ArrayList(),
        parcel.readInt()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeList(values)
        parcel.writeInt(color)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GraphLine> = object : Parcelable.Creator<GraphLine> {
            override fun createFromParcel(parcel: Parcel): GraphLine = GraphLine(parcel)
            override fun newArray(size: Int): Array<GraphLine?> = arrayOfNulls(size)
        }
    }
}
