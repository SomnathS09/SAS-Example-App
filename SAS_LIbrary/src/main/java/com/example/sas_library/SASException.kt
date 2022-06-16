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
 *Top-level exception in the SAS framework. All other SAS exceptions should extend this.
 */
open class SASException(message: String? = null,
                   cause: Throwable? = null,
                   recoverySuggestion: String) : Exception(message, cause) {

    constructor(cause: Throwable,
                recoverySuggestion: String = TODO_RECOVERY_SUGGESTION
    ) : this(null, cause, recoverySuggestion)

    companion object {
        const val TODO_RECOVERY_SUGGESTION: String = "Sorry, we don't have a suggested fix for this error yet."
    }
}