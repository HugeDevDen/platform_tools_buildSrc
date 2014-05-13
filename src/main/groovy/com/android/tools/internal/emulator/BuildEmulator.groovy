/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.internal.emulator

import org.gradle.api.DefaultTask
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.BuildException
/**
 * Custom task to build emulator.
 */
class BuildEmulator extends DefaultTask {

    @OutputDirectory
    File output

    /**
     * Since we don't have a good understanding of emulator build, treat
     * the whole project (external/qemu) as the input folder.
     */
    @InputDirectory
    File getInputDir() {
        return project.projectDir
    }

    boolean windows = false

    @TaskAction
    void build() {

        String command = windows ?
                "$project.projectDir/android-rebuild.sh --mingw --out-dir=$output" :
                "$project.projectDir/android-rebuild.sh --out-dir=$output"

        StringBuilder stdout = new StringBuilder()
        StringBuilder stderr = new StringBuilder()

        Process p = command.execute()
        p.consumeProcessOutput(stdout, stderr)

        int result = p.waitFor()

        logger.log(LogLevel.INFO, stdout.toString())
        logger.log(LogLevel.ERROR, stderr.toString())

        if (result != 0) {
            throw new BuildException("Failed to run command. See console output", null)
        }
    }
}
