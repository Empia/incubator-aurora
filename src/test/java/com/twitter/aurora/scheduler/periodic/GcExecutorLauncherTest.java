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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskInfo;
import org.junit.Before;
import org.junit.Test;

import com.twitter.aurora.codec.ThriftBinaryCodec;
import com.twitter.aurora.gen.AssignedTask;
import com.twitter.aurora.gen.Identity;
import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.gen.comm.AdjustRetainedTasks;
import com.twitter.aurora.scheduler.PulseMonitor;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.storage.testing.StorageTestUtil;
import com.twitter.common.testing.easymock.EasyMockTest;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static com.twitter.aurora.gen.ScheduleStatus.FAILED;

public class GcExecutorLauncherTest extends EasyMockTest {

  private static final String HOST = "slave-host";

  private static final Offer OFFER = Offer.newBuilder()
        .setSlaveId(SlaveID.newBuilder().setValue("slave-id"))
        .setHostname(HOST)
        .setFrameworkId(FrameworkID.newBuilder().setValue("framework-id").build())
        .setId(OfferID.newBuilder().setValue("offer-id"))
        .build();

  private static final String JOB_A = "jobA";

  private final AtomicInteger taskIdCounter = new AtomicInteger();

  private StorageTestUtil storageUtil;
  private PulseMonitor<String> hostMonitor;
  private GcExecutorLauncher gcExecutorLauncher;

  @Before
  public void setUp() {
    storageUtil = new StorageTestUtil(this);
    storageUtil.expectOperations();
    hostMonitor = createMock(new Clazz<PulseMonitor<String>>() { });
    gcExecutorLauncher = new GcExecutorLauncher(
        hostMonitor,
        Optional.of("nonempty"),
        storageUtil.storage);
  }

  @Test
  public void testPruning() throws ThriftBinaryCodec.CodingException {
    ScheduledTask thermosPrunedTask = makeTask(JOB_A, true, FAILED);
    ScheduledTask thermosTask = makeTask(JOB_A, true, FAILED);
    ScheduledTask nonThermosTask = makeTask(JOB_A, false, FAILED);

    // Service first createTask - no hosts ready for GC.
    expect(hostMonitor.isAlive(HOST)).andReturn(true);

    // Service second createTask - prune no tasks.
    expect(hostMonitor.isAlive(HOST)).andReturn(false);
    expectGetTasksByHost(HOST, thermosPrunedTask, thermosTask, nonThermosTask);
    hostMonitor.pulse(HOST);

    // Service third createTask - prune one tasks.
    expect(hostMonitor.isAlive(HOST)).andReturn(false);
    expectGetTasksByHost(HOST, thermosPrunedTask);
    hostMonitor.pulse(HOST);

    control.replay();

    // First call - hostMonitor returns true, no GC.
    Optional<TaskInfo> taskInfo = gcExecutorLauncher.createTask(OFFER);
    assertFalse(taskInfo.isPresent());

    // Second call - no tasks pruned.
    taskInfo = gcExecutorLauncher.createTask(OFFER);
    assertTrue(taskInfo.isPresent());
    assertRetainedTasks(taskInfo.get(), thermosPrunedTask, thermosTask, nonThermosTask);
    ExecutorInfo executor1 = taskInfo.get().getExecutor();

    // Third call - two tasks pruned.
    taskInfo = gcExecutorLauncher.createTask(OFFER);
    assertTrue(taskInfo.isPresent());
    assertRetainedTasks(taskInfo.get(), thermosPrunedTask);

    // Same executor should be re-used for both tasks
    assertEquals(executor1, taskInfo.get().getExecutor());
  }

  private static void assertRetainedTasks(TaskInfo taskInfo, ScheduledTask... tasks)
      throws ThriftBinaryCodec.CodingException {
    AdjustRetainedTasks message = ThriftBinaryCodec.decode(
        AdjustRetainedTasks.class, taskInfo.getData().toByteArray());
    Map<String, ScheduledTask> byId = Tasks.mapById(ImmutableSet.copyOf(tasks));
    assertEquals(Maps.transformValues(byId, Tasks.GET_STATUS), message.getRetainedTasks());
  }

  private ScheduledTask makeTask(String jobName, boolean isThermos, ScheduleStatus status) {
    return new ScheduledTask()
        .setStatus(status)
        .setAssignedTask(new AssignedTask()
            .setTaskId("task-" + taskIdCounter.incrementAndGet())
            .setSlaveHost(HOST)
            .setTask(new TaskConfig()
                .setJobName(jobName)
                .setOwner(new Identity().setRole("role").setUser("user"))
                .setThermosConfig(isThermos ? new byte[] {1, 2, 3} : new byte[] {})));
  }

  private void expectGetTasksByHost(String host, ScheduledTask... tasks) {
    storageUtil.expectTaskFetch(Query.slaveScoped(host), tasks);
  }
}
