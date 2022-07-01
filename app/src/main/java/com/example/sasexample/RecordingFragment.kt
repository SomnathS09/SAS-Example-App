/*
 * Copyright 2018 Google LLC
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

package com.example.sasexample

import android.Manifest.permission.RECORD_AUDIO
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.sas_library.RecordingEvent
import com.example.sas_library.SASAudioRecorder
import com.example.sasexample.databinding.FragmentRecordingBinding
import com.vmadalin.easypermissions.EasyPermissions
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

const val PERMISSION_RECORD_REQUEST_CODE: Int = 1000
const val DEFAULT_AUDIO_SAMPLE_DELAY: Int = 200
class RecordingFragment : Fragment(),EasyPermissions.PermissionCallbacks, SASAudioRecorder.HostListener {

    private var _binding: FragmentRecordingBinding? = null
    private val binding get() = _binding!!
    private lateinit var sasAudioRecorder : SASAudioRecorder

    private var player: MediaPlayer? = null
    private var isPlaying = false

    private var isRecording = false
    private var showLevelJob: Job? = null

    private val downloadDirectory: String? by lazy{
        requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
    }
    private var fileName: String? = null
    private var tempPlayFilePath : String? = null

    //Create an instance of SASMediaRecorder here
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sasAudioRecorder = SASAudioRecorder(WeakReference(requireContext()),
            SASAudioRecorder.StopModeParams(60, true))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sasAudioRecorder.setHostListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRecordingBinding.inflate(inflater, container, false)

        //File name is timestamp, when onCreateView is called
        fileName = downloadDirectory.plus("/${System.currentTimeMillis()}")

        binding.apply {
            closeBtn.setOnClickListener { findNavController().navigateUp() }
            editAudioPath.setText(fileName)
            editRecordingLevelSampleRate.setText(DEFAULT_AUDIO_SAMPLE_DELAY.toString())

            initializeBtn.setOnClickListener {
                if (!sasAudioRecorder.isPermissionAvailable()) { requestNeededPermissions(); return@setOnClickListener }
                sasAudioRecorder.initialize()
            }
            setPathBtn.setOnClickListener {
                val pathFromInput = binding.editAudioPath.text.toString().plus(".mp4")
                tempPlayFilePath = pathFromInput
                if(sasAudioRecorder.setOutfilePath(pathFromInput)){
                    Toast.makeText(requireContext(), getString(R.string.path_set), Toast.LENGTH_SHORT).show()
                } else
                {
                    Toast.makeText(requireContext(), getString(R.string.path_set_failed), Toast.LENGTH_SHORT).show()
                }
            }
            prepareBtn.setOnClickListener { sasAudioRecorder.prepare() }

            startRecordingBtn.setOnClickListener {
                if (isPlaying) stopPlaying()
                sasAudioRecorder.startRecording()
                if(sasAudioRecorder.currentState == SASAudioRecorder.State.Recording){
                    isRecording = true
                    showLevel()
                }
            }

            stopRecordingBtn.setOnClickListener {
                /*
                if(!isRecording) return@setOnClickListener
                Maintaining your own isRecording boolean is not suggested, because in case you'd set SAS to invalid state,
                then you need to make sure to update your isRecording boolean accordingly by checking the current state of SAS
                */
                sasAudioRecorder.stopRecording()
                if(sasAudioRecorder.currentState == SASAudioRecorder.State.Recording){
                    sasAudioRecorder.stopRecording()
                    isRecording = false
                }
            }

            //Mediaplayer related
            playRecordingBtn.setOnClickListener {
                if(isRecording) return@setOnClickListener
                playRecording(tempPlayFilePath) }
            stopPlayingBtn.setOnClickListener { stopPlaying() }
        }

        return binding.root
    }

    private fun stopPlaying() {
        if (isPlaying){
            Log.d("RecordingFragment","Stopping playback")
            player?.apply {
                stop()
                release()
            }
            binding.playRecordingBtn.isEnabled = true
            binding.stopPlayingBtn.isEnabled = false
            isPlaying = false
        }
    }

    private fun playRecording(tempPlayFilePath: String?) {
        if (isPlaying) stopPlaying()
        if (tempPlayFilePath != null) {
            val fileToPlay = File(tempPlayFilePath)
            if (fileToPlay.exists()) {
                Log.d("RecordingFragment", "Playing recording : ${fileToPlay.toUri()}")
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA).build()
                    )
                    setOnCompletionListener {
                        this@RecordingFragment.isPlaying = false
                        binding.apply {
                            playRecordingBtn.isEnabled = true
                            stopPlayingBtn.isEnabled = false
                        }
                    }
                    try{
                        setDataSource(requireContext(), fileToPlay.toUri())
                        prepare()
                        start().also { this@RecordingFragment.isPlaying = true }
                        binding.apply {
                            playRecordingBtn.isEnabled = false
                            stopPlayingBtn.isEnabled = true
                        }
                    }catch (e : IOException){
                        Toast.makeText(requireContext(), getString(R.string.mediaplayer_file_is_blank_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onRecordingEvent(recordingEvent: RecordingEvent<SASAudioRecorder.EventType>?) {
        when (recordingEvent?.type) {
            is SASAudioRecorder.EventType.Duration ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    binding.recordingTimeText.text = recordingEvent.message
                }
            is SASAudioRecorder.EventType.StateChanges -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    binding.stateTextView.text = "SAS state: ${recordingEvent.message}"
                    if (recordingEvent.message=="Recording") isRecording = true
                    if (recordingEvent.message=="Stopped") isRecording = false
                }
            }
            is SASAudioRecorder.EventType.HeadSetEvent -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    binding.iconHeadsetState.isEnabled = recordingEvent.message == "true"
                }
            }
            else -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) sasAudioRecorder.stopRecording()
        if (isPlaying) stopPlaying()
        Log.d("RecordingFragment","onPause was called")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sasAudioRecorder.deInitialize()
    }

    private fun showLevel() {
        showLevelJob?.cancel()
        val delayFrequency = 30.toLong()
        if (binding.editRecordingLevelSampleRate.text.toString().isNotBlank()){
            (binding.editRecordingLevelSampleRate.text.toString()).toLong()
        }
        else{
            Toast.makeText(requireContext(), getString(R.string.path_set), Toast.LENGTH_SHORT).show()
            DEFAULT_AUDIO_SAMPLE_DELAY.toLong()
        }
        showLevelJob = viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            while (isActive && sasAudioRecorder.currentState == SASAudioRecorder.State.Recording) {
                val amplitude: Int = 100 * sasAudioRecorder.maxAmplitude / 32768
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        binding.audioLevelSeekBar.setProgress(amplitude, true)
                    } else binding.audioLevelSeekBar.progress = amplitude
                    delay(delayFrequency)
                }
            }
            cancel()
        }
    }

    //Related to Permissions

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            Utils.buildSettingsDialog(requireActivity()).show()
        }
        else {
            requestNeededPermissions()
        }
    }

    private fun requestNeededPermissions() {
        EasyPermissions.requestPermissions(
            this,
            getString(R.string.permission_rationale), PERMISSION_RECORD_REQUEST_CODE, RECORD_AUDIO
        )
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        Toast.makeText(
            requireContext(), getString(R.string.permission_granted), Toast.LENGTH_SHORT
        ).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}

