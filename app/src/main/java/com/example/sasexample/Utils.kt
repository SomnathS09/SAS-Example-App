package com.example.sasexample

import android.content.Context
import com.vmadalin.easypermissions.dialogs.SettingsDialog

class Utils {
    companion object{
        fun buildSettingsDialog(mContext : Context): SettingsDialog {
            return SettingsDialog.Builder(mContext)
                .rationale(
                    mContext.getString(R.string.permission_rationale)
                            + mContext.getString(R.string.permission_rationale_extended)
                )
                .positiveButtonText("Settings")
                .negativeButtonText("Cancel")
                .build()
        }
    }
}