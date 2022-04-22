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

import android.Manifest
import android.Manifest.permission.RECORD_AUDIO
import android.content.Intent
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
import com.example.sas_library.SASMediaRecorder
import com.example.sasexample.databinding.FragmentRecordingBinding
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.dialogs.SettingsDialog
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.WeakReference

const val PERMISSION_RECORD_REQUEST_CODE: Int = 1000
class RecordingFragment : Fragment(),EasyPermissions.PermissionCallbacks, SASMediaRecorder.HostListener {

    private var _binding: FragmentRecordingBinding? = null
    private val binding get() = _binding!!
    private lateinit var sasMediaRecorder : SASMediaRecorder

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
        sasMediaRecorder = SASMediaRecorder(WeakReference(requireContext()) )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sasMediaRecorder.setHostListener(this)
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

            initializeBtn.setOnClickListener {
                if (!sasMediaRecorder.isPermissionAvailable()) { requestNeededPermissions(); return@setOnClickListener }
                sasMediaRecorder.initialize()
            }
            setPathBtn.setOnClickListener {
                val pathFromInput = binding.editAudioPath.text.toString().plus(".wav")
                tempPlayFilePath = pathFromInput
                if(sasMediaRecorder.setOutfilePath(pathFromInput)){
                    Toast.makeText(requireContext(), getString(R.string.path_set), Toast.LENGTH_SHORT).show()
                } else
                {
                    Toast.makeText(requireContext(), getString(R.string.path_set_failed), Toast.LENGTH_SHORT).show()
                }
            }
            prepareBtn.setOnClickListener { sasMediaRecorder.prepare() }

            startRecordingBtn.setOnClickListener {
                if (isPlaying) stopPlaying()
                sasMediaRecorder.startRecording()
                if(sasMediaRecorder.currentState == SASMediaRecorder.State.Recording){
                    isRecording = true
                    showLevel()
                }
            }

            stopRecordingBtn.setOnClickListener {
                if(!isRecording) return@setOnClickListener
                if(sasMediaRecorder.currentState == SASMediaRecorder.State.Recording){
                    sasMediaRecorder.stopRecording()
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
                    setDataSource(requireContext(), fileToPlay.toUri())
                    setOnCompletionListener { this@RecordingFragment.isPlaying = false }
                    prepare()
                    start().also { this@RecordingFragment.isPlaying = true }
                }
            }
        }
    }

    override fun onRecordingEvent(recordingEvent: RecordingEvent?) {
        when (recordingEvent?.type) {
            is SASMediaRecorder.EventType.Duration ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    binding.recordingTimeText.text = recordingEvent.message
                }
            is SASMediaRecorder.EventType.StateChanges -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    binding.stateTextView.text = "SAS state: ${recordingEvent.message}"
                }
            }
            is SASMediaRecorder.EventType.HeadSetEvent -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    binding.iconHeadsetState.isEnabled = recordingEvent.message == "true"
                }
            }
            else -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) sasMediaRecorder.stopRecording()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sasMediaRecorder.deInitialize()
    }

    private fun showLevel() {
        showLevelJob?.cancel()
        val delayFrequency = (binding.editRecordingLevelSampleRate.text.toString()).toLong()
        showLevelJob = viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            while (isActive && sasMediaRecorder.currentState == SASMediaRecorder.State.Recording) {
                val amplitude: Int = 100 * sasMediaRecorder.maxAmplitude / 32768
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
