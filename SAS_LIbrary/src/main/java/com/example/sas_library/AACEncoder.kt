package com.example.sas_library

import android.media.*
import android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.suspendCoroutine


class AACEncoder(filePathToEncode : String) {

    private val mediaCodec: MediaCodec? = null
    private val audioRecord: AudioRecord? = null
    private val inputStream : InputStream? = null
    private val outputStream: OutputStream? = null
    private val inputFile : File = File(filePathToEncode)
    private val outputFile : File = File(filePathToEncode.substringBeforeLast(".")+".m4a")
    private val codec = MediaCodec.createEncoderByType(COMPRESSED_AUDIO_FILE_MIME_TYPE)
    private var outputFormat: MediaFormat =
        MediaFormat.createAudioFormat(COMPRESSED_AUDIO_FILE_MIME_TYPE, SAMPLING_RATE, 1)

    init{
        Log.d("AACEncoder", "Output File ${outputFile.absolutePath}")
        outputFormat.apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, COMPRESSED_AUDIO_FILE_BIT_RATE);
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        }

        codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    suspend fun process(){
        withContext(Dispatchers.IO){
            if (outputFile.exists()) outputFile.delete()

            val mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val fis = FileInputStream(inputFile)
            //skipping 44bytes WAV file header
            fis.skip(44)

            codec.start()
            val codecOutputBuffers: Array<ByteBuffer> = codec.outputBuffers

            val outBuffInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
            val tempBuffer = ByteArray(TEMP_BUFFER_SIZE)
            var totalBytesRead : Int = 0
            var audioTrackIdx : Int = 0
            var presentationTimeUs : Double = 0.0
            var percentComplete : Int = 0

            var hasMoreData : Boolean = true
            do {
                //input buffer later updated with index returned from codec.dequeue
                var inputBufIndex : Int = 0

                while (inputBufIndex != -1 && hasMoreData) {
                    //
                    inputBufIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS.toLong())

                    if (inputBufIndex >= 0) {
                        //access elements at input buffer
                        val dstBuf = codec.getInputBuffer(inputBufIndex)
                        dstBuf?.clear()

                        //reading in from input file stream of size dstBuf.limit()
                        val bytesRead = fis.read(tempBuffer, 0, dstBuf!!.limit())
                        Log.d("AACEncoder", "Read $bytesRead for inputBufferindex $inputBufIndex")

                        if (bytesRead == -1) { // -1 implies EOS, notify codec that it is end of stream
                            hasMoreData = false
                            Log.d("AACEncoder", "InputFileStream ended for inputBufferindex $inputBufIndex")
                            codec.queueInputBuffer(inputBufIndex, 0, 0, presentationTimeUs.toLong(),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            totalBytesRead += bytesRead

                            //filling the dstBuf with bytes read from the input file stream
                            dstBuf.put(tempBuffer, 0, bytesRead)

                            codec.queueInputBuffer(inputBufIndex, 0, bytesRead, presentationTimeUs.toLong(), 0)

                            presentationTimeUs = (1000000L * (totalBytesRead / 2) / SAMPLING_RATE).toDouble()
                        }
                    }
                }
                // Drain audio
                var outputBufIndex = 0
                while (outputBufIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    outputBufIndex = codec.dequeueOutputBuffer(outBuffInfo, CODEC_TIMEOUT_IN_MS.toLong())

                    if (outputBufIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outputBufIndex)
                        encodedData?.position(outBuffInfo.offset)
                        encodedData?.limit(outBuffInfo.offset + outBuffInfo.size)

                        if (outBuffInfo.flags and BUFFER_FLAG_CODEC_CONFIG != 0 && outBuffInfo.size != 0) {
                            codec.releaseOutputBuffer(outputBufIndex, false)
                        } else {
                            Log.d("AACEncoder", "Writing index outBufIndex $outputBufIndex,  outBuffInfo : $outBuffInfo")
                            mux.writeSampleData(
                                audioTrackIdx,
                                codecOutputBuffers[outputBufIndex],
                                outBuffInfo
                            )
                            codec.releaseOutputBuffer(outputBufIndex, false)
                        }
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputFormat = codec.outputFormat
                        Log.v(LOGTAG, "Output format changed - $outputFormat")
                        audioTrackIdx = mux.addTrack(outputFormat)
                        mux.start()
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        Log.e(LOGTAG, "Output buffers changed during encode!")
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // NO OP
                    } else {
                        Log.e(LOGTAG, "Unknown return code from dequeueOutputBuffer - $outputBufIndex"
                        )
                    }
                }
                percentComplete =
                    Math.round(totalBytesRead.toFloat() / inputFile.length().toFloat() * 100.0)
                        .toInt()
                Log.v(LOGTAG, "Conversion % - $percentComplete")
            } while (outBuffInfo.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            mux.stop();
            mux.release();
            fis.close();
        }

    }

    companion object{
        const val COMPRESSED_AUDIO_FILE_MIME_TYPE = "audio/mp4a-latm"
        const val COMPRESSED_AUDIO_FILE_BIT_RATE = 64000
        const val SAMPLING_RATE = 16000
        const val TEMP_BUFFER_SIZE = 48000
        const val CODEC_TIMEOUT_IN_MS = 5000
        const val LOGTAG="AACEncoder"
    }

}