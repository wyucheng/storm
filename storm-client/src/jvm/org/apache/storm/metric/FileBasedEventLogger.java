/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.metric;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.storm.shade.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.utils.ConfigUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileBasedEventLogger implements IEventLogger {
    private static final Logger LOG = LoggerFactory.getLogger(FileBasedEventLogger.class);

    private static final int FLUSH_INTERVAL_MILLIS = 1000;

    private Path eventLogPath;
    private BufferedWriter eventLogWriter;
    private ScheduledExecutorService flushScheduler;
    private volatile boolean dirty = false;

    private void initLogWriter(Path logFilePath) {
        try {
            LOG.info("logFilePath {}", logFilePath);
            eventLogPath = logFilePath;
            eventLogWriter = Files.newBufferedWriter(eventLogPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                                                     StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("Error setting up FileBasedEventLogger.", e);
            throw new RuntimeException(e);
        }
    }


    private void setUpFlushTask() {
        ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("event-logger-flush-%d")
            .setDaemon(true)
            .build();

        flushScheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (dirty) {
                        eventLogWriter.flush();
                        dirty = false;
                    }
                } catch (IOException ex) {
                    LOG.error("Error flushing " + eventLogPath, ex);
                    throw new RuntimeException(ex);
                }
            }
        };

        flushScheduler.scheduleAtFixedRate(runnable, FLUSH_INTERVAL_MILLIS, FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }


    @Override
    public void prepare(Map<String, Object> conf, Map<String, Object> arguments, TopologyContext context) {
        String stormId = context.getStormId();
        int port = context.getThisWorkerPort();

        /*
         * Include the topology name & worker port in the file name so that
         * multiple event loggers can log independently.
         */
        String workersArtifactRoot = ConfigUtils.workerArtifactsRoot(conf, stormId, port);

        Path path = Paths.get(workersArtifactRoot, "events.log");
        File dir = path.toFile().getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        initLogWriter(path);
        setUpFlushTask();
    }

    @Override
    public void log(EventInfo event) {
        try {
            //TODO: file rotation
            eventLogWriter.write(buildLogMessage(event));
            eventLogWriter.newLine();
            dirty = true;
        } catch (IOException ex) {
            LOG.error("Error logging event {}", event, ex);
            throw new RuntimeException(ex);
        }
    }

    protected String buildLogMessage(EventInfo event) {
        return event.toString();
    }

    @Override
    public void close() {
        try {
            eventLogWriter.close();

        } catch (IOException ex) {
            LOG.error("Error closing event log.", ex);
        }

        closeFlushScheduler();
    }

    private void closeFlushScheduler() {
        if (flushScheduler != null) {
            flushScheduler.shutdown();
            try {
                if (!flushScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    flushScheduler.shutdownNow();
                }
            } catch (InterruptedException ie) {
                // (Re-)Cancel if current thread also interrupted
                flushScheduler.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }
    }
}
