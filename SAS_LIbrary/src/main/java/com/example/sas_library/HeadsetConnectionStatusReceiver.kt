/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sas_library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.View
import androidx.appcompat.widget.AppCompatImageView

internal class HeadsetConnectionStatusReceiver(val onHeadPhoneStatusChange : (isHeadPhoneConnected : Boolean)->Unit): BroadcastReceiver() {
    val action : String = AudioManager.ACTION_HEADSET_PLUG
    override fun onReceive(context: Context?, intent: Intent?) {
        if(action == intent?.action){
            Log.d("HeadSetBroadcast","Headset event occurred $this")
            //0:false and 1:true
            val connectionState = intent.getIntExtra("state",0)
            val isMicrophoneAvailable = intent.getIntExtra("microphone",0)
            val headSetEnabled : Boolean = connectionState==1 && isMicrophoneAvailable==1
            onHeadPhoneStatusChange(headSetEnabled)
        }
    }
}