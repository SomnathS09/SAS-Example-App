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

/**
 * Message objects passed to Host app's event listener
 * @param T the type of [type] i.e [SASMediaRecorder.EventType] or [SASAudioRecorder.EventType]
 *
 * @property type type of event in [T] e.g. [SASMediaRecorder.EventType.Duration]
 * @property message String message related to [type] event
 */
data class RecordingEvent<T>(
    val type: T,
    val message: String
)

