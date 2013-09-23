/*
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.aurora.scheduler.periodic;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;
import com.google.protobuf.ByteString;

import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskStatus;

import com.twitter.aurora.Protobufs;
import com.twitter.aurora.codec.ThriftBinaryCodec;
import com.twitter.aurora.codec.ThriftBinaryCodec.CodingException;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.comm.AdjustRetainedTasks;
import com.twitter.aurora.scheduler.PulseMonitor;
import com.twitter.aurora.scheduler.TaskLauncher;
import com.twitter.aurora.scheduler.base.CommandUtil;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.configuration.Resources;
import com.twitter.aurora.scheduler.storage.Storage;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Data;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A task launcher that periodically initiates garbage collection on a host, re-using a single
 * garbage collection executor
 */
public class GcExecutorLauncher implements TaskLauncher {
  private static final Logger LOG = Logger.getLogger(GcExecutorLauncher.class.getName());

  /**
   * Binding annotation for gc executor-related fields..
   */
  @BindingAnnotation
  @Target({ FIELD, PARAMETER, METHOD }) @Retention(RUNTIME)
  public @interface GcExecutor { }

  private static final Resources GC_EXECUTOR_RESOURCES =
      new Resources(0.19, Amount.of(127L, Data.MB), Amount.of(15L, Data.MB), 0);
  private static final Resources ALMOST_EMPTY_TASK_RESOURCES =
      new Resources(0.01, Amount.of(1L, Data.MB), Amount.of(1L, Data.MB), 0);

  private static final String SYSTEM_TASK_PREFIX = "system-gc-";
  private static final String EXECUTOR_NAME = "aurora.gc";

  private final PulseMonitor<String> pulseMonitor;
  private final Optional<String> gcExecutorPath;
  private final Storage storage;

  @Inject
  GcExecutorLauncher(
      @GcExecutor PulseMonitor<String> pulseMonitor,
      @GcExecutor Optional<String> gcExecutorPath,
      Storage storage) {

    this.pulseMonitor = checkNotNull(pulseMonitor);
    this.gcExecutorPath = checkNotNull(gcExecutorPath);
    this.storage = checkNotNull(storage);
  }

  @Override
  public Optional<TaskInfo> createTask(Offer offer) {
    if (!gcExecutorPath.isPresent() || pulseMonitor.isAlive(offer.getHostname())) {
      return Optional.absent();
    }

    Set<ScheduledTask> tasksOnHost =
        Storage.Util.weaklyConsistentFetchTasks(storage, Query.slaveScoped(offer.getHostname()));
    AdjustRetainedTasks message = new AdjustRetainedTasks()
        .setRetainedTasks(Maps.transformValues(Tasks.mapById(tasksOnHost), Tasks.GET_STATUS));
    byte[] data;
    try {
      data = ThriftBinaryCodec.encode(message);
    } catch (CodingException e) {
      LOG.severe("Failed to encode retained tasks message: " + message);
      return Optional.absent();
    }

    pulseMonitor.pulse(offer.getHostname());

    ExecutorInfo.Builder executor = ExecutorInfo.newBuilder()
        .setExecutorId(ExecutorID.newBuilder().setValue(EXECUTOR_NAME))
        .setName(EXECUTOR_NAME)
        .setSource(offer.getHostname())
        .addAllResources(GC_EXECUTOR_RESOURCES.toResourceList())
        .setCommand(CommandUtil.create(gcExecutorPath.get()));

    return Optional.of(TaskInfo.newBuilder().setName("system-gc")
        .setTaskId(TaskID.newBuilder().setValue(SYSTEM_TASK_PREFIX + UUID.randomUUID().toString()))
        .setSlaveId(offer.getSlaveId())
        .setData(ByteString.copyFrom(data))
        .setExecutor(executor)
        .addAllResources(ALMOST_EMPTY_TASK_RESOURCES.toResourceList())
        .build());
  }

  @Override
  public boolean statusUpdate(TaskStatus status) {
    if (status.getTaskId().getValue().startsWith(SYSTEM_TASK_PREFIX)) {
      LOG.info("Received status update for GC task: " + Protobufs.toString(status));
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void cancelOffer(OfferID offer) {
    // No-op.
  }
}
