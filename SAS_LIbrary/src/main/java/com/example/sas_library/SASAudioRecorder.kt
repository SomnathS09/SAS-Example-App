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
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.net.toUri
import com.vmadalin.easypermissions.EasyPermissions
import kotlinx.coroutines.*
import java.io.File
import java.lang.Math.abs
import java.lang.Math.max
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

private const val amplitudeWhileNotRecording = 0
/**
 * maxDurationInAutoStopMode is just to indicate that [maxDuration] has no significance in case of [SASAudioRecorder.StopMode.Auto]
 */
private const val maxDurationInAutoStopMode = -1

/**
 * SASAudioRecorder is used to record, do lexical analysis and provide Prosody score
 * Used to record audio and video. The recording control is based on a
 * simple state machine (see below).
 *
 * <p><img src="{@docRoot}images/mediarecorder_state_diagram.gif" border="0" />
 * </p>
 *
 * <p>A common case of using MediaRecorder to record audio works as follows:
 *
 * <pre>sasMediaRecorder = SASAudioRecorder(WeakReference(CONTEXT));
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
 * @constructor used to create an new uninitialized instance of [SASAudioRecorder]
 * @param mContext Host Activity ([Context]) creating instance of SASAudioRecorder
 * @property [maxAmplitude] instantaneous maxAmplitude while recording is in progress otherwise 0
 * @property [state] the current state of SASAudioRecorder
 *
 *
 */
class SASAudioRecorder(private val mContext: WeakReference<Context>, private val stopModeParams : StopModeParams) {
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var audioRecord: AudioRecord

    private var headsetConnectionStatusReceiver: HeadsetConnectionStatusReceiver? = null
    private var hostListener: HostListener? = null

    private var currentWavAudioFile : WavAudioFile? = null
    private var writeOutputFileJob : Job? = null
    private var postMessageJob: Job? = null
    private var encodeToM4aJobRef: Job? = null
    // can the same scope be used or not?
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private var recordingDuration: Int = 0
    val maxAmplitude: Int get() = getCurrentAmplitude()

    private var producerAudioBuffer = ShortArray(512)
    //decrease the size of JNIconsumer buffer later
    private var JNIconsumerBuffer = ArrayList<Short>(15000)

    private var state: State = State.Uninitialized
        set(value) {
            hostListener?.onRecordingEvent(RecordingEvent(EventType.StateChanges, value.javaClass.simpleName))
            field = value
        }
    val currentState : State get() = state
    val maxDuration : Int = stopModeParams.durationInSeconds

    private var preInitialized: Boolean = false
    private var hasUserStartedSpeaking : Boolean = false
    init {
        preInitialize()
//        Refactor this when all states are available
//        state = State.ReadyToInitialize
        state = State.Invalid
        System.loadLibrary("sas-library")
    }

    /**
     * currently, registers broadcast receiver for headset event
     */
    private fun preInitialize() {
        Log.d("SASAudioRecorder", "preInitializing \"preInitialized\" = $preInitialized")
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
     * Initialize the SASAudioRecorder with Recording related configurations
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
        //Because Stopped(earlier now replaced with Done, i.e final state) and Invalid, both are valid preconditions
        //If the state was earlier State.Invalid, then don't again put to Invalid, reinitialize
        if (state != State.Done && state!=State.Invalid) {
            stopRecordingIfInvalidStateCalled()
            state = State.Invalid
            return
        }
        Log.d("SASMediaRecorder", "${isPermissionAvailable()}")
        if (!isPermissionAvailable()) {
            state = State.Invalid
            throw SASAudioRecorderException("Permissions Required for Recording Not Granted", null, "Show Permission Dialog to User")
        }

        postRecordingEvents(EventType.Duration, "00 : 00")
        //getting a new instance of audioRecord
        audioRecord =
            WavAudioFile.run {
                val audioRecord =  AudioRecord(AUDIO_SOURCE, RECORDER_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE_RECORDING)
                if(audioRecord.state!= AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Failed to init AudioRecorder")
                else  return@run audioRecord
            }
        state = State.Initialized
    }

    /**
     * This method should remove all UI/context related resources
     * Call this method while UI is being destroyed, i.e in Fragment's onDestroyView
     **/
    fun deInitialize() {
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
            Log.d("SASAudioRecorder", "Please Initialize MediaRecorder first or Reinitialize")
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
                currentWavAudioFile = WavAudioFile(path)
                state = State.FilePathSet
                return true
            }
            Log.d("SASAudioRecorder", File(path).toUri().toString() + File(path).path)
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
            Log.d("SASAudioRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if(state != State.FilePathSet){
            stopRecordingIfInvalidStateCalled()
            state = State.Invalid
            return
        }
        if (!isPermissionAvailable()) {
            Log.d("SASAudioRecorder", "Prepare() failed : Please enable required permissions")
            throw SASAudioRecorderException("Permissions Required for Recording Not Granted", null, "Show Permission Dialog to User")
        }
        state = try {
            //code for any extra preparation on audiorecord similar to mediarecord.prepare()
            State.Prepared
        } catch (error: Exception) {
            Log.d("SASAudioRecorder", "Prepare() failed : MediaRecorder prepare failed : $error")
            State.Invalid
        }
    }

    /**
     * Call this method after [SASAudioRecorder.prepare], to start the recording session
     */
    fun startRecording() {
        if (state == State.Invalid) {
            Log.d("SASAudioRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if (state != State.Prepared) {
            stopRecordingIfInvalidStateCalled()
            state = State.Invalid
            Log.d("SASAudioRecorder", "Can't start, MediaRecorder is not prepared")
            return
        }
        Log.d("SASAudioRecorder", "Starting MediaRecorder Recording")
        producerAudioBuffer = ShortArray(512)
        JNIconsumerBuffer = ArrayList<Short>(15000)
        hasUserStartedSpeaking = false
        audioRecord.startRecording()
        writeOutputFileJob =
            scope.launch(block = if (stopModeParams.autoStopMode) autoStopModeEnabledOutFileJob() else autoStopModeDisabledOutFileJob())
        state = State.Recording
        postMessageJob =
            scope.launch(block = if (stopModeParams.autoStopMode) autoStopModeEnabledMessageJob else autoStopModeDisabledMessageJob)
    }

    /**
     *  Call this method to stop recording session, also don't forget to stop any polling related to session being stopped from host app's side
     *  Called in conjunction before [deInitialize], when Host's UI is being destroyed
     *  After the recording is stopped, the [State] moves to [State.Invalid]
     */
    fun stopRecording( fromInvalid : Boolean = false) {
        if (state == State.Invalid) {
            Log.d("SASAudioRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if (state != State.Recording) {
            Log.d("SASAudioRecorder", "Can't stop, MediaRecorder is not recording")
            state = State.Invalid
            return
        }
        Log.d("SASAudioRecorder", "Stopping MediaRecorder Recording Now")
        //move encodeToM4aJobRef?.cancel() after implementation of stopEncoding()
        postMessageJob?.cancel(); writeOutputFileJob?.cancel(); encodeToM4aJobRef?.cancel()
        postMessageJob = null; writeOutputFileJob = null; encodeToM4aJobRef = null
        currentWavAudioFile?.close()
        recordingDuration = 0
        audioRecord.apply {
            stop()
            release()
        }
        /** when stopRecordingIfInvalidState() calls stopRecording(), the state should set to Invalid
        currently State.Invalid is set by those functions after [stopRecordingIfInvalidState] is called
         */
        state = State.Stopped
        Log.d("JNIconsumerBuffer", "[size : ${JNIconsumerBuffer.size}] "+buildString {
            append(JNIconsumerBuffer.joinToString(" "))
            //a way to not call convertRecordingToM4a() when stopping in [deinitializing] or due to [Invalid] state
            if(!fromInvalid) convertRecordingToM4a()
        })
    }

    private fun convertRecordingToM4a(){
        if(state == State.Invalid){
            Log.d("SASAudioRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if (state != State.Stopped) {
            Log.d("SASAudioRecorder", "Can't encode to m4a, MediaRecorder is not in Stopped state")
            state = State.Invalid
            return
        }
        currentWavAudioFile?.fileName?.let {
            state = State.ConvertingToM4a
            encodeToM4aJobRef = scope.launch { encodeToM4aJob(it) }
        }
    }

    private suspend fun encodeToM4aJob(fileName : String){
        Log.d("AACenc", "Before AACEncoder Status : $state")
        val aacEncoder = AACEncoder(fileName)
        aacEncoder.process()
        state = State.ConvertedToM4a
        Log.d("AACenc", "After AACEncoer Status : $state")
        //this is final step till now, hence setting state to Done
        state = State.Done
    }


    /**
     * used to register listener for receiving [RecordingEvent]
     * @param hostListener Fragment or Activity
     */
    fun setHostListener(hostListener: SASAudioRecorder.HostListener) {
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
        return if (state == State.Recording) currentWavAudioFile?.maxAmplitudeInt?: amplitudeWhileNotRecording else amplitudeWhileNotRecording
    }

    /**check if all required permissions for recording are available
     * @return true if required permissions are available else false
     */
    fun isPermissionAvailable(): Boolean {
        mContext.get()?.let {
            return EasyPermissions.hasPermissions(it, Manifest.permission.RECORD_AUDIO)
        }
        return false
    }

    private fun isInvalidState(): Boolean = (State.Invalid == this.state)

    private fun isValidPreviousState(stateToCheck : State) : Boolean = (stateToCheck == this.state)

    /**
     * While recording is in progress and you call other functions, then recorder has to be stopped first
     */
    private fun stopRecordingIfInvalidStateCalled(){
        if (state==State.Recording) stopRecording(fromInvalid = true)
    }

    /**
     * While encoding is in progress and you call other functions, then encoding has to be stopped first
     * (Need more clarity on the usage of this function, because stopRecording has to call encode in every case)
     */
    private fun stopEncoding(){
        if (state == State.Invalid) {
            Log.d("SASAudioRecorder", "Please Initialize MediaRecorder first or Reinitialize")
            return
        }
        if (state != State.ConvertingToM4a) {
            Log.d("SASAudioRecorder", "Can't stop, MediaRecorder is not recording")
            state = State.Invalid
            return
        }
        encodeToM4aJobRef?.cancel()
        //Should you've another state like StoppedEncoding for new final as Final State
        state = State.Invalid
    }

    //Methods to be implemented
    private fun assess() {
        state = State.Assessing
        Log.d("SASAudioRecorder", "Assessing Recording")
        TODO("Not yet implemented")
    }

    private fun postResult() {
        Log.d("SAS", "Sending result to host")
        state = State.Result
        TODO("Not yet implemented")
    }

    /**
     * States of SASAudioRecorder
     */
    sealed class State {
        /**
         * Invalid state : Some Invalid operation has occurred
         */
        object Invalid : State()

        /**
         * Host is yet to get an instance of SASAudioRecorder
         */
        object Uninitialized : State()

        /**
         * Preinitialize step has been completed or/and SAS is ready to be initialized
         */
        object ReadyToInitialize : State()

        /**
         * Host has successfully got an instance of SASAudioRecorder
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
         * SASAudioRecorder is ready for recording
         */
        object Ready : State()

        /**
         * SASAudioRecorder's recording is in progress
         */
        object Recording : State()

        /**
         * SASAudioRecorder's current recording session completed
         */
        object Stopped : State()

        /**
         * SASAudioRecorder's encoding current recording to m4a
         */
        object ConvertingToM4a : State()

        /**
         * SASAudioRecorder's have successfully converted current recording to m4a
         */
        object ConvertedToM4a : State()

        /**
         * SASAudioRecorder's all task related to current recording session has finished
         */
        object Done : State()

        /**
         * SASAudioRecorder's connecting to SAS API
         */
        object Assessing : State()

        /**
         * SASAudioRecorder's result ready
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

    /**
     * Interface to be implemented by Host Fragment for receiving recorder's state & recording related information
     */
    interface HostListener {
        fun onRecordingEvent(recordingEvent: RecordingEvent<SASAudioRecorder.EventType>?)
    }

    sealed class StopMode{
        object Auto : StopMode()
        data class Duration (val durationInSeconds : Int) : StopMode()
    }

    data class StopModeParams(val durationInSeconds: Int, val autoStopMode : Boolean)

    private val autoStopModeDisabledMessageJob: suspend CoroutineScope.() -> Unit = {
        while (isActive) {
            recordingDuration++
            delay(1000)
            //postRecordingEvents(EventType.Duration, recordingDuration.seconds.toString(DurationUnit.SECONDS, 1))
            postRecordingEvents(EventType.Duration, recordingDuration.seconds.toComponents
            { minutes, seconds, _ -> "${String.format("%02d", minutes)} : ${String.format("%02d", seconds)}" })
            if (recordingDuration == maxDuration) stopRecording()
        }
    }

    private val autoStopModeEnabledMessageJob: suspend CoroutineScope.() -> Unit = {
        while (isActive) {
            recordingDuration++
            delay(1000)
            //postRecordingEvents(EventType.Duration, recordingDuration.seconds.toString(DurationUnit.SECONDS, 1))
            postRecordingEvents(EventType.Duration, recordingDuration.seconds.toComponents
            { minutes, seconds, _ -> "${String.format("%02d", minutes)} : ${String.format("%02d", seconds)}" })
            if (recordingDuration == maxDuration) stopRecording()
        }
    }

    private fun autoStopModeEnabledOutFileJob(): suspend CoroutineScope.() -> Unit =
        {
            while (isActive) {
                val samplesRead: Int =
                    audioRecord.read(producerAudioBuffer, 0, producerAudioBuffer.size)
                //                Log.d("SamplesRead","Sample count: $samplesRead")
                val processedShort = processShortArray(producerAudioBuffer)
                //                Log.d("Array", "$processedShort")
                JNIconsumerBuffer.add(processedShort)
                analyseProcessedArray(JNIconsumerBuffer.toShortArray())
                val renewMaxAmplitude: Int = 0
                //                for (sample in producerAudioBuffer) {
                currentWavAudioFile!!.setAmplitude(max(abs(processedShort.toInt()), renewMaxAmplitude).toShort())
                //                }
                currentWavAudioFile!!.writeSamples(producerAudioBuffer, samplesRead)
                if (processedShort > 20000) stopRecording()
                if (!hasUserStartedSpeaking) {
                    hasUserStartedSpeaking = lookForStart(JNIconsumerBuffer.toShortArray())
                }
                else{
                    if (lookForStop(JNIconsumerBuffer.toShortArray())) stopRecording()
                }
            }
        }
    //NativeState = false
    private fun autoStopModeDisabledOutFileJob(): suspend CoroutineScope.() -> Unit =
        {
            while (isActive) {
                val samplesRead: Int =
                    audioRecord.read(producerAudioBuffer, 0, producerAudioBuffer.size)
                val processedShort = processShortArray(producerAudioBuffer)
                JNIconsumerBuffer.add(processedShort)
                val renewMaxAmplitude: Int = 0
                currentWavAudioFile!!.setAmplitude(max(abs(processedShort.toInt()), renewMaxAmplitude).toShort())
                currentWavAudioFile!!.writeSamples(producerAudioBuffer, samplesRead)
            }
        }

    //Native function defined in native-lib.cpp
    external fun processShortArray(inputshort : ShortArray) : Short

    external fun analyseProcessedArray(inputshort : ShortArray) : Boolean

    external fun lookForStart(inputshort : ShortArray) : Boolean
    external fun lookForStop(inputshort : ShortArray) : Boolean
}