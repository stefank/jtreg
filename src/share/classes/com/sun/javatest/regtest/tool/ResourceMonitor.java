/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.javatest.regtest.tool;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.sun.javatest.regtest.util.StreamCopier;

public class ResourceMonitor {
    private static class ResourceMonitorThread extends Thread {
        private static long SampleIntervalMS = 5000;

        private Object lock = new Object();
        private boolean stop = false;
        private BufferedWriter cpuFileWriter;
        private BufferedWriter memoryFileWriter;

        public ResourceMonitorThread(BufferedWriter cpuFileWriter, BufferedWriter memoryFileWriter) throws IOException {
            this.cpuFileWriter = cpuFileWriter;
            this.memoryFileWriter = memoryFileWriter;
        }

        public void run() {
            System.err.println("ResourceMonitorThread started");
            synchronized (lock) {
                while (true) {
                    try {
                        sample();

                        lock.wait(SampleIntervalMS);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }

                    if (stop) {
                        return;
                    }
                }
            }
        }

        private void sampleBashCommand(BufferedWriter toFile, String bashCommand) throws InterruptedException, IOException {
            Process p = Runtime.getRuntime().exec(new String[] {
                "bash",
                 "-c",
                 bashCommand});

            StreamCopier out = new StreamCopier(p.getInputStream(), new PrintWriter(toFile, true), null);
            StreamCopier err = new StreamCopier(p.getErrorStream(), new PrintWriter(System.err, true), null);

            out.start();
            err.start();

            int ret = p.waitFor();

            out.join();
            err.join();
        }

        private void sampleCPU(BufferedWriter toFile) throws IOException, InterruptedException {
            sampleBashCommand(toFile, "ps -A -o %cpu | awk 'BEGIN {SUM=0}{SUM+=$1}END{print SUM}'");
        }

        private void sampleMemory(BufferedWriter toFile) throws IOException, InterruptedException {
            sampleBashCommand(toFile, "vm_stat | awk '/.*page size of.*/ { PAGE_SIZE=$8} /Pages free: (.*)\\./ {print $3 * PAGE_SIZE / 1024 / 1024}'");
        }

        private void sample() throws IOException, InterruptedException {
            sampleCPU(cpuFileWriter);
            sampleMemory(memoryFileWriter);
        }

        public void disengage() throws InterruptedException {
            System.err.println("Disengage called");
            synchronized (lock) {
                stop = true;
                lock.notify();
            }
            join();
        }
    }

    private ResourceMonitorThread thread;

    private BufferedWriter createFileWriter(Path directory, String name) throws IOException {
        Path file = directory.resolve(name);

        if (Files.exists(file)) {
            Files.delete(file);
        }

        Files.createFile(file);

        return Files.newBufferedWriter(file, StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
    }

    public ResourceMonitor(Path directory) throws IllegalMonitorStateException, IOException {
        BufferedWriter cpuFileWriter = createFileWriter(directory, "monitor-cpu.log");
        BufferedWriter memoryFileWriter = createFileWriter(directory, "monitor-memory.log");

        thread = new ResourceMonitorThread(cpuFileWriter, memoryFileWriter);
        thread.start();
    }

    public void disengage() {
        try {
            thread.disengage();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }
}
