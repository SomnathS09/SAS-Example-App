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

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
import android.util.Log
import androidx.core.net.toUri
import com.vmadalin.easypermissions.EasyPermissions
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_ENCODING_BIT_RATE = 64000
private const val amplitudeWhileNotRecording = 0

/**
 * SASMediaRecorder is used to record, do lexical analysis and provide rosody score
 * Used to record audio and video. The recording control is based on a
 * simple state machine (see below).
 *
 * <p><img src="{@docRoot}images/mediarecorder_state_diagram.gif" border="0" />
 * </p>
 *
 * <p>A common case of using MediaRecorder to record audio works as follows:
 *
 * <pre>sasMediaRecorder = SASMediaRecorder(WeakReference(CONTEXT));
 *  sasMediaRecorder.initialize();
 *  sasMediaRecorder.setOutputFile(PATH_NAME);
 *  sasMediaRecorder.setHostListener(HOST_LISTENER);
 *  sasMediaRecorder.prepare();
 *  sasMediaRecorder.startRecording();   // Recording is now started
 *  ...
 *  sasMediaRecorder.stop();
 *  sasMediaRecorder.reset();   // You can reuse the object by going back to setOutputFile(PATH_NAME) step
 *  sasMediaRecorder.deInitialize(); // Now the object cannot be reused
 *  </pre>
 *
 * <p>Applications may want to register for informational and error
 * events in order to be informed of some internal update and possible
 * runtime errors during recording. Registration for such events is
 * done by setting the appropriate listeners (via calls
 * (to {@link #setHostListener(hostListener)}setHostListener and/or
 * {@link #setOnErrorListener(OnErrorListener)}setOnErrorListener).
 *
 * In order to receive the respective callback associated with these listeners,
 * applications are required to create MediaRecorder objects on threads with a
 * Looper running (the main UI thread by default already has a Looper running).
 * @constructor used to create an new uninitialized instance of [SASMediaRecorder]
 * @param mContext Host Activity ([Context]) creating instance of SASMediaRecorder
 * @property [maxAmplitude] instantaneous maxAmplitude while recording is in progress otherwise 0
 * @property [state] the current state of SASMediaRecorder
 *
 *
 */
class SASMediaRecorder(private val mContext: WeakReference<Context>, private val mediaParams: MediaParams) {
    private lateinit var mediaRecorder: MediaRecorder
    private var headsetConnectionStatusReceiver: HeadsetConnectionStatusReceiver? = null
    private var hostListener: HostListener? = null

    private var postMessageJob: Job? = null
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private var recordingDuration: Int = 0
    val maxAmplitude: Int get() = getCurrentAmplitude()
    private var state: State = State.Uninitialized
        set(value) {
            hostListener?.onRecordingEvent(RecordingEvent(EventType.StateChanges, value.javaClass.simpleName))
            field = value
        }
    val currentState : State get() = state

    private var preInitialized: Boolean = false

    init {
        preInitialize()
//        Refactor this when all states are available
//        state = State.ReadyToInitialize
          state = State.Invalid
    }

    /**
     * currently, registers broadcast receiver for headset event
     */
    private fun preInitialize() {
        Log.d("SASMediaRecorder", "preInitializing \"preInitialized\" = $preInitialized")
        headsetConnectionStatusReceiver =
            HeadsetConnectionStatusReceiver { isHeadPhoneConnected: Boolean ->
                postRecordingEvents(EventType.HeadSetEvent, "$isHeadPhoneConnected")
            }
        mContext.get()?.registerReceiver(
            headsetConnectionStatusReceiver,
            IntentFilter(AudioManager.ACTION_HEADSET_PLUG)
        )
        preInitialized = true
    }

    /**
     * Initialize the SASMediaRecorder with Recording related configurations
     *
     * Call this for every new recording session or if in a [State.Invalid] state
     **/
    fun initialize() {
        if (!preInitialized) preInitialize()
        if (state == State.Initialized) return
        /*
        What should be the state, when you call initialize() after State.Initialized state?
        Ans.    Then I need to check if recording was in progress or not, so have decided to put state = Invalid
                This means the valid pre-condition for initialize() function is State.Invalid and State.Final
                Don't make the state Invalid if SAS was in State.Final
        To be implemented later, after verification of every state:
        If you call [initialize] from states after initialization for e.g. prepare()
        I think in all cases other than State.Recording, we can directly reinitialize SAS to Initialized state without going through State.Invalid
        Also, If the recorder is already in Invalid state, it means that we've already performed stopRecordingIfInvalidStateCalled()
        */
        //Because Stopped and Invalid, both are valid preconditions
        //If the state was earlier State.Invalid, then don't again put to Invalid, reinitialize
         if (state != State.Stopped && state!=State.Invalid) {
             stopRecordingIfInvalidStateCalled()
             state = State.Invalid
             return
         }
        Log.d("SASMediaRecorder", "${isPermissionAvailable()}")
        if (!isPermissionAvailable()) {
            state = State.Invalid
            throw SASMediaRecorderException("Permissions Required for Recording Not Granted", null, "Show Permission Dialog to User")
        }

        postRecordingEvents(EventType.Duration, "00 : 00")

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            setMaxDuration(mediaParams.durationInSeconds*1000)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(mediaParams.encodingBitRate)
            setOnInfoListener { _, what, _ ->
//                Log.d("RecordingFragment", "OnInfoListener called")
                if (what == MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.d("RecordingFragment", "Max Duration Reached")
                    stopRecording()
                }
            }
        }
        state = State.Initialized
    }

    /**
     * This method should remove all UI/context related resources
     * Call this method while UI is being destroyed, i.e in Fragment's onDestroyView
     **/
    fun deInitialize() {
//        Log.d("SASMediaRecorder", "deInitialize was called")
        if(this::mediaRecorder.isInitialized) mediaRecorder.release()
        removeHostListener()
        mContext.get()?.unregisterReceiver(headsetConnectionStatusReceiver)
        mContext.clear()
        preInitialized = false
    }

    /**
     * used to set file path for recording, call this after [State.Initialized]
     * @param path String
     * @return successful Boolean
     */
    fun setOutfilePath(path: String?) : Boolean {
        if (state == State.Invalid) {
            Log.d("SASMediaRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return false
        }
        if(state != State.Initialized){
            stopRecordingIfInvalidStateCalled()
            state = State.Invalid
            return false
        }
        if (!path!!.endsWith("/")) {
            val extractedDirectoryPath = path.substringBeforeLast("/")
            File(extractedDirectoryPath).mkdirs()
            if (File(extractedDirectoryPath).exists()){
                mediaRecorder.setOutputFile(path)
                state = State.FilePathSet
                return true
            }
            Log.d("SASMediaRecorder", File(path).toUri().toString() + File(path).path)
            state = State.Invalid
            return false
        }
        else{
            state = State.Invalid
            return false
        }
    }

    /**
     * used to prepare the recorder for current session, should be called after [startRecording]
     * @sample com.example.sas_library.Example
     */
    fun prepare() {
        if (state == State.Invalid) {
            Log.d("SASMediaRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if(state != State.FilePathSet){
            stopRecordingIfInvalidStateCalled()
            state = State.Invalid
            return
        }
        if (!isPermissionAvailable()) {
            Log.d("SASMediaRecorder", "Prepare() failed : Please enable required permissions")
            throw SASMediaRecorderException("Permissions Required for Recording Not Granted", null, "Show Permission Dialog to User")
        }
        state = try {
            mediaRecorder.prepare()
            State.Prepared
        } catch (error: Exception) {
            Log.d("SASMediaRecorder", "Prepare() failed : MediaRecorder prepare failed : $error")
            State.Invalid
        }
    }

    /**
     * Call this method after [SASMediaRecorder.prepare], to start the recording session
     */
    fun startRecording() {
        if (state == State.Invalid) {
            Log.d("SASMediaRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if (state != State.Prepared) {
            stopRecordingIfInvalidStateCalled()
            state = State.Invalid
            Log.d("SASMediaRecorder", "Can't start, MediaRecorder is not prepared")
            return
        }
        Log.d("SASMediaRecorder", "Starting MediaRecorder Recording")
        mediaRecorder.start()
        state = State.Recording
        postMessageJob = scope.launch {
            while (isActive) {
                recordingDuration++
                delay(1000)
//                postRecordingEvents(EventType.Duration, recordingDuration.seconds.toString(DurationUnit.SECONDS, 1))
                postRecordingEvents(EventType.Duration, recordingDuration.seconds.toComponents
                    { minutes, seconds, _ -> "${String.format("%02d", minutes)} : ${String.format("%02d", seconds)}" })
            }
        }
    }

    /**
     *  Call this method to stop recording session, also don't forget to stop any polling related to session being stopped from host app's side
     *  After the recording is stopped, the [State] moves to [State.Invalid]
     */
    fun stopRecording() {
        if (state == State.Invalid) {
            Log.d("SASMediaRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if (state != State.Recording) {
            Log.d("SASMediaRecorder", "Can't stop, MediaRecorder is not recording")
            state = State.Invalid
            return
        }
        Log.d("SASMediaRecorder", "Stopping MediaRecorder Recording Now")
        postMessageJob?.cancel()
        postMessageJob = null
        recordingDuration = 0
        mediaRecorder.apply {
            //try to get state on callbacks from MediaRecorder, if you don't want to try-catch
            try{ stop() }
            catch (_: IllegalStateException){}
            catch (_: RuntimeException){}
            finally { release() }
        }
        /* when stopRecordingIfInvalidState() calls stopRecording(), the state should set to Invalid
         */
        state = State.Stopped
    }

    /**
     * used to register listener for receiving [RecordingEvent]
     * @param hostListener Fragment or Activity
     */
    fun setHostListener(hostListener: SASMediaRecorder.HostListener) {
        this.hostListener = hostListener
        postRecordingEvents(EventType.StateChanges, state.javaClass.simpleName)
    }

    /**
     * removes listener for events, should be called while UI is being destroyed, internally called on [deInitialize]
     * hence, set listener again after [deInitialize] - [initialize] step
     **/
    fun removeHostListener() {
        this.hostListener = null
    }

    /**
     * Wrapper function to post recording events to hostListener, creates [RecordingEvent] object
     * In future, more additional checks could be added
     */
    private fun postRecordingEvents(type: EventType, message: String) {
//        Log.d("SASMediaRecorderEvent","$type, $message")
        hostListener?.onRecordingEvent(RecordingEvent(type, message))
    }

    /**
     * @return instantaneous amplitude of ongoing recording session; 0 otherwise
     */
    private fun getCurrentAmplitude(): Int {
        return if (state == State.Recording) mediaRecorder.maxAmplitude else amplitudeWhileNotRecording
    }

    /**check if all required permissions for recording are available
     * @return true if required permissions are available else false
     */
    fun isPermissionAvailable(): Boolean {
        mContext.get()?.let {
//        Log.d("SASMediaRecorder", "Context was not null isPermissionAvailable()")
            return EasyPermissions.hasPermissions(it, Manifest.permission.RECORD_AUDIO)
        }
//        Log.d("SASMediaRecorder", "Context was null isPermissionAvailable()")
        return false
    }

    private fun isInvalidState(): Boolean = (State.Invalid == this.state)

    private fun isValidPreviousState(stateToCheck : State) : Boolean = (stateToCheck == this.state)

    /**
     * While recording is in progress and you call other functions, then recorder has to be stopped first
     */
    private fun stopRecordingIfInvalidStateCalled(){
        if (state==State.Recording) stopRecording()
    }

    //Methods to be implemented
    private fun assess() {
        state = State.Assessing
        Log.d("SASMediaRecorder", "Assessing Recording")
        TODO("Not yet implemented")
    }

    private fun postResult() {
        Log.d("SAS", "Sending result to host")
        state = State.Result
        TODO("Not yet implemented")
    }

    /**
     * States of SASMediaRecorder
     */
    sealed class State {
        /**
         * Invalid state : Some Invalid operation has occurred
         */
        object Invalid : State()

        /**
         * Host is yet to get an instance of SASMediaRecorder
         */
        object Uninitialized : State()

        /**
         * Preinitialize step has been completed or/and SAS is ready to be initialized
         */
        object ReadyToInitialize : State()

        /**
         * Host has successfully got an instance of SASMediaRecorder
         */
        object Initialized : State()

        /**
         * File path for recording has been set successfully
         */
        object FilePathSet : State()

        /**
         * All parameters have been set
         */
        object Prepared : State()

        /**
         * Credentials of client has been validated
         */
        object Validated : State()

        /**
         * SASMediaRecorder is ready for recording
         */
        object Ready : State()

        /**
         * SASMediaRecorder's recording is in progress
         */
        object Recording : State()

        /**
         * SASMediaRecorder's waiting for [DelayBeforeStop] seconds before stopping Recording
         * TODO : Not yet implemented nor used by client App
         */
        object Stopping : State()

        /**
         * SASMediaRecorder's current recording session completed
         */
        object Stopped : State()

        /**
         * SASMediaRecorder's connecting to SAS API
         */
        object Assessing : State()

        /**
         * SASMediaRecorder's result ready
         */
        object Result : State()
    }

    /**
     * Types of events wrapped inside [RecordingEvent]
     */
    sealed class EventType {
        /**
         * Recording duration
         */
        object Duration : EventType()

        /**
         * Mediarecorder state changes
         */
        object StateChanges : EventType()

        /**
         * Headset connection status
         */
        object HeadSetEvent : EventType()
    }

    data class MediaParams(val durationInSeconds: Int = 60, val encodingBitRate : Int= DEFAULT_ENCODING_BIT_RATE)

    /**
     * Interface to be implemented by Host Fragment for receiving recorder's state & recording related information
     */
    interface HostListener {
        fun onRecordingEvent(recordingEvent: RecordingEvent<SASMediaRecorder.EventType>?)
    }
}



