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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel


class WavAudioFile(val fileName: String?) {

    private var outputFile: RandomAccessFile? = RandomAccessFile(fileName!!, "rw")
    private var outputChannel: FileChannel? = outputFile!!.channel

    private val byteBuffer : ByteBuffer = ByteBuffer.allocate(BIG_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    var maxAmplitude : Short = 0

    val maxAmplitudeInt : Int
        get() = (maxAmplitude).toInt()

    var payloadSize:Int = 0

    init{
        byteBuffer.run {
            //RIFF chunk
            putInt(0x46464952) //ChunkID : 'RIFF'
            putInt(0) //ChunkSize : Final file size (Int) not known yet, write 0
            putInt(0x45564157) //Format : 'WAVE'

            //fmt chunk
            putInt(0x20746d66) //Subchunk1ID : 'fmt '
            putInt((16)) //Subchunk1Size , 16 for PCM
            putShort(1.toShort())//AudioFormat : 1 for PCM = 1 (i.e. Linear quantization), Values other than 1 indicate some form of compression.
            putShort(1.toShort()) // Number of channels : 1 for mono, 2 for stereo
            putInt(RECORDER_SAMPLE_RATE) //SampleRate : 8000, 44100, etc.
            putInt((RECORDER_SAMPLE_RATE shl 1))//ByteRate == SampleRate * NumChannels * BitsPerSample/8
            putShort(2.toShort())//BlockAlign == NumChannels * BitsPerSample/8 The number of bytes for one sample including all channels
            putShort(16.toShort())// Bits per sample : 8 bits = 8, 16 bits = 16, etc.

            //Data chunk
            putInt(0x61746164)// Subchunk2ID : 'data'
            putInt(0)//Subchunk2Size == NumSamples * NumChannels * BitsPerSample/8, i.e Data chunk size not known yet, write 0

            flip()
            outputChannel!!.write(this)
            clear()
        }
    }

    @Throws(IOException::class)
    fun writeSamples(
        samples: ShortArray,
        samplesSize: Int
    ) {
        //As i can see, this method will only be used to write sample data
        //So, no access to the WAVE header of bb here
        var remainingShortsToBeWritten : Int = samplesSize
        var sampleIdx : Int = 0
//        Log.d("paySize","before : $payloadSize")
        while (true) {
            //If nothing is to be written, then exit while otherwise :
            if (remainingShortsToBeWritten == 0) {
//                Log.d("LQQQ","I am returning")
                return
            }

            //elements between current position and limit
            //i.e if bb hasRemaining is false, then it means we're in writing mode
            //if bb hasRemaining is true, then it means we're in reading mode

            //so, this specifies that if we're in writing mode then move to read mode and write that in channel
            //and then again reset the bb to be in reading mode
            //Hence, this step will definitely write header to the file channel first
            //and make the bb ready to be filled by short audio samples
            if (!byteBuffer.hasRemaining()) {
                byteBuffer.flip()
                outputChannel?.write(byteBuffer)
                byteBuffer.clear()
            }

            //How many short audio sample, are we going to be putting into the bb? At this stage header has already be written
            var bbPutSize: Int
            //taking putSize to be minimum of 'half of bb's remaining space' and remaining shorts to be written
            //because 1 short is of 2 bytes
            //then, I am just filling the in memory-buffer which is quite bad I think
            while (0 < Math.min(byteBuffer.remaining() / 2, remainingShortsToBeWritten).also {
                    bbPutSize = it
                }){
//                (Log.d("LQQQ","I am writing : started"))
                for (i in 0 until bbPutSize) {
                    byteBuffer.putShort(samples[sampleIdx++])
                }
//                (Log.d("LQQQ","I am writing : next elements"))
                remainingShortsToBeWritten -= bbPutSize
                payloadSize += bbPutSize * 2
            }
//            (Log.d("LQQQ","Writing completed"))
        }
//        Log.d("paySize","after : $payloadSize")

    }

    @Throws(IOException::class)
    fun close() {
        byteBuffer.flip()
        outputFile?.run {
            write(byteBuffer.array(), 0, byteBuffer.remaining())
            seek(4)
            Log.d("paySize","final : $payloadSize")
            writeInt(Integer.reverseBytes(36 + payloadSize))
            seek(40)
            writeInt(Integer.reverseBytes(payloadSize))
            close() // this closes outputChannel implicitly too, so no need of outputChannel.close()
        }

        outputChannel = null
        outputFile = null
    }

    fun setAmplitude(value : Short) {
//        Log.d("RecordingFragment", "setAmplitude() value received from buffer is $value")
        maxAmplitude = value
    }
    companion object{
        const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val RECORDER_SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT
        const val BIG_BUFFER_SIZE = 0x4000 //16kB in hexadecimal
        val BUFFER_SIZE_RECORDING
                by lazy { AudioRecord.getMinBufferSize(RECORDER_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) }
    }

}