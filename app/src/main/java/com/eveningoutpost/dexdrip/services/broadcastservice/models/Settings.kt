package com.eveningoutpost.dexdrip.services.broadcastservice.models

import android.os.Parcel
import android.os.Parcelable

/**
 * Settings that WatchDrip+ sends with "set_settings" / "update_bg_force".
 *
 * IMPORTANT: this class MUST keep this exact package and class name, and the
 * exact order of fields in the parcel. WatchDrip+ writes a parcel of its own class
 * with the same name (same layout as xDrip+'s BroadcastService). Do not rename it.
 */
class Settings() : Parcelable {
    /** Graph start offset in ms. 0 means "use the default of 2 hours". */
    var graphStart: Long = 0

    /** Graph end offset in ms (time after "now" that must be shown). */
    var graphEnd: Long = 0

    /** Name of the receiving app (display name only). */
    var apkName: String? = null

    /** When true, graph lines must be sent with the glucose value. */
    var displayGraph: Boolean = false

    constructor(parcel: Parcel) : this() {
        apkName = parcel.readString()
        graphStart = parcel.readLong()
        graphEnd = parcel.readLong()
        displayGraph = parcel.readInt() == 1
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(apkName)
        parcel.writeLong(graphStart)
        parcel.writeLong(graphEnd)
        parcel.writeInt(if (displayGraph) 1 else 0)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Settings> = object : Parcelable.Creator<Settings> {
            override fun createFromParcel(parcel: Parcel): Settings = Settings(parcel)
            override fun newArray(size: Int): Array<Settings?> = arrayOfNulls(size)
        }
    }
}
