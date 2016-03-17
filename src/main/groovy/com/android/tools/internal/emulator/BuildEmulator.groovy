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
import org.gradle.api.logging.Logger
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.BuildException
import java.io.ByteArrayOutputStream
/**
 * Custom task to build emulator.
 */
class BuildEmulator extends DefaultTask {

    static class LoggerWriter extends ByteArrayOutputStream {
        private Logger logger;
        private LogLevel level;

        public LoggerWriter ( final Logger logger, final LogLevel level ) {
            super();
            this.logger = logger;
            this.level = level;
        }

        @Override
        public void write(int b) {
            if ((char)b == '\n' || (char)b == '\r') {
                this.flush();
            } else {
                super.write(b)
            }
        }

        @Override
        void write(byte[] b, int off, int len) {
            int lastindex = 0;
            for (int i = 0; i < len; i ++) {
                if ((char)b[off+i] == '\n' || (char)b[off+i] == '\r') {
                    super.write(b, off+lastindex, i-lastindex);
                    lastindex = i+1;
                    this.flush();
                }
            }
        }

        @Override
        public void flush() {
            this.logger.log(this.level, this.toString());
            this.reset();
            super.flush();
        }
    }

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
    @Input
    String revision

    @Input
    String build_number

    @TaskAction
    void build() {

        String command = windows ?
                "$project.projectDir/android-rebuild.sh --verbose --mingw --out-dir=$output --sdk-revision=$revision --sdk-build-number=$build_number --symbols --crash-prod" :
                "$project.projectDir/android-rebuild.sh --verbose --out-dir=$output --sdk-revision=$revision --sdk-build-number=$build_number --symbols --crash-prod"

        LoggerWriter stdout = new LoggerWriter(logger, LogLevel.INFO)
        LoggerWriter stderr = new LoggerWriter(logger, LogLevel.ERROR)

        Process p = command.execute()
        p.consumeProcessOutput(stdout, stderr)

        int result = p.waitFor()

        if (result != 0) {
            throw new BuildException("Failed to run android-rebuild command. See console output", null)
        }

        stdout.reset();
        stderr.reset();

        /**
         * Upload the symbols
         */
        command = "$project.projectDir/android/scripts/upload-symbols.sh --crash-prod --symbol-dir=$output/build/symbols"

        p = command.execute()
        p.consumeProcessOutput(stdout, stderr)

        result = p.waitFor()

        if (result != 0) {
            throw new BuildException("Failed to run upload-symbols command. See console output", null)
        }

    }
}
