/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.metrics.eventtracker;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.ning.metrics.serialization.writer.DiskSpoolEventWriter;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class CollectorControllerProvider implements Provider<CollectorController>
{
    private static final Logger log = Logger.getLogger(CollectorControllerProvider.class);

    private final ScheduledExecutorService executor;
    private final DiskSpoolEventWriter eventWriter;
    private final EventSender eventSender;

    @Inject
    public CollectorControllerProvider(ScheduledExecutorService executor, DiskSpoolEventWriter eventWriter, EventSender eventSender)
    {
        this.executor = executor;
        this.eventWriter = eventWriter;
        this.eventSender = eventSender;
    }

    @Override
    public CollectorController get()
    {
        final CollectorController controller = new CollectorController(eventWriter);

        // Make sure to flush all files on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            @Override
            public void run()
            {
                mainEventTrackerShutdownHook(executor, eventWriter, eventSender, controller);
            }
        });

        return controller;
    }

    protected static void mainEventTrackerShutdownHook(ScheduledExecutorService executor, DiskSpoolEventWriter eventWriter, EventSender eventSender, CollectorController controller)
    {
        log.info("Starting main shutdown sequence");

        log.info("Stop accepting new events");
        // Don't accept events anymore
        controller.setAcceptEvents(false);

        log.info("Shut down the writers service");
        // Stop the periodic flusher to the final spool area
        try {
            executor.shutdown();
            executor.awaitTermination(15, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            log.warn("Interrupted while trying to shutdown the disk flusher", e);
        }

        log.info("Flush current open file to disk");
        // Commit the current file
        try {
            eventWriter.forceCommit();
        }
        catch (IOException e) {
            log.warn("IOExeption while committing current file", e);
        }

        log.info("Promote quarantined files to final spool area");
        // Give quarantined events a last chance
        eventWriter.processQuarantinedFiles();

        log.info("Flush all local files");
        // Flush events to remote collectors
        try {
            eventWriter.flush();
        }
        catch (IOException e) {
            log.warn("IOException while flushing last files to the collectors", e);
        }

        log.info("Close event sender");
        // Close the sender
        eventSender.close();

        log.info("Main shutdown sequence has terminated");
    }
}
