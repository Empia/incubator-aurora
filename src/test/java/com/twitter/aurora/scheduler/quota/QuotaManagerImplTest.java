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
package com.twitter.aurora.scheduler.quota;

import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.easymock.IExpectationSetters;
import org.junit.Before;
import org.junit.Test;

import com.twitter.aurora.gen.AssignedTask;
import com.twitter.aurora.gen.Identity;
import com.twitter.aurora.gen.JobUpdateConfiguration;
import com.twitter.aurora.gen.Quota;
import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.gen.TaskUpdateConfiguration;
import com.twitter.aurora.scheduler.base.JobKeys;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.quota.QuotaManager.QuotaManagerImpl;
import com.twitter.aurora.scheduler.storage.testing.StorageTestUtil;
import com.twitter.common.testing.easymock.EasyMockTest;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuotaManagerImplTest extends EasyMockTest {

  private static final String ROLE = "foo";
  private static final Query.Builder ACTIVE_QUERY = Query.roleScoped(ROLE).active();

  private StorageTestUtil storageUtil;
  private QuotaManager quotaManager;

  @Before
  public void setUp() throws Exception {
    storageUtil = new StorageTestUtil(this);
    quotaManager = new QuotaManagerImpl(storageUtil.storage);
  }

  @Test
  public void testGetEmptyQuota() {
    storageUtil.expectOperations();
    noActiveUpdates();
    expect(storageUtil.quotaStore.fetchQuota(ROLE)).andReturn(Optional.<Quota>absent());
    returnNoTasks().atLeastOnce();

    control.replay();

    assertEquals(Quotas.NO_QUOTA, quotaManager.getQuota(ROLE));
    assertEquals(Quotas.NO_QUOTA, quotaManager.getConsumption(ROLE));
  }

  @Test
  public void testConsumeNoQuota() {
    storageUtil.expectOperations();
    noActiveUpdates();
    applyQuota(new Quota(1, 1, 1));
    returnNoTasks();

    control.replay();

    assertTrue(quotaManager.hasRemaining(ROLE, Quotas.NO_QUOTA));
  }

  @Test
  public void testNoQuotaExhausted() {
    storageUtil.expectOperations();
    returnNoTasks();
    noActiveUpdates();
    expect(storageUtil.quotaStore.fetchQuota(ROLE)).andReturn(Optional.<Quota>absent());

    control.replay();

    assertFalse(quotaManager.hasRemaining(ROLE, new Quota(1, 1, 1)));
  }

  @Test
  public void testSetQuota() {
    Quota quota = new Quota(1, 2, 3);

    storageUtil.expectOperations();
    storageUtil.quotaStore.saveQuota(ROLE, quota);

    control.replay();

    quotaManager.setQuota(ROLE, quota);
  }

  @Test
  public void testUseAllQuota() {
    ScheduledTask task1 = createTask("foo", "id1", 1, 1, 1);
    ScheduledTask task2 = createTask("foo", "id2", 1, 1, 1);

    storageUtil.expectOperations();
    applyQuota(new Quota(2, 2, 2)).anyTimes();
    noActiveUpdates();
    returnTasks(task1);
    returnTasks(task1, task2);

    control.replay();

    Quota half = new Quota(1, 1, 1);
    assertTrue(quotaManager.hasRemaining(ROLE, half));
    assertFalse(quotaManager.hasRemaining(ROLE, half));
  }

  @Test
  public void testExhaustCpu() {
    storageUtil.expectOperations();
    applyQuota(new Quota(2, 2, 2));
    noActiveUpdates();
    returnTasks(createTask("foo", "id1", 1, 1, 1));

    control.replay();

    assertFalse(quotaManager.hasRemaining(ROLE, new Quota(2, 1, 1)));
  }

  @Test
  public void testExhaustRam() {
    storageUtil.expectOperations();
    applyQuota(new Quota(2, 2, 2));
    noActiveUpdates();
    returnTasks(createTask("foo", "id1", 1, 1, 1));

    control.replay();

    assertFalse(quotaManager.hasRemaining(ROLE, new Quota(1, 2, 1)));
  }

  @Test
  public void testExhaustDisk() {
    storageUtil.expectOperations();
    applyQuota(new Quota(2, 2, 2));
    noActiveUpdates();
    returnTasks(createTask("foo", "id1", 1, 1, 1));

    control.replay();

    assertFalse(quotaManager.hasRemaining(ROLE, new Quota(1, 1, 2)));
  }

  @Test
  public void testNonproductionUnaccounted() {
    ScheduledTask task = createTask("foo", "id1", 3, 3, 3);
    task.getAssignedTask().getTask().setProduction(false);

    storageUtil.expectOperations();
    applyQuota(new Quota(2, 2, 2));
    noActiveUpdates();
    returnTasks(task);

    control.replay();

    assertTrue(quotaManager.hasRemaining(ROLE, new Quota(2, 2, 2)));
  }

  @Test
  public void testUpdating() {
    ScheduledTask task = createTask("bar", "id1", 1, 1, 1);
    ScheduledTask updatingTask = createTask("foo", "id1", 1, 1, 1);

    storageUtil.expectOperations();
    applyQuota(new Quota(4, 4, 4)).anyTimes();
    returnTasks(task, updatingTask).anyTimes();

    // Simulate a job update that increases the job quota consumption.
    expectUpdateQuery().andReturn(
        ImmutableSet.of(new JobUpdateConfiguration(
            JobKeys.from(ROLE, "env", "foo"),
            "token",
            ImmutableSet.of(new TaskUpdateConfiguration(
                updatingTask.getAssignedTask().getTask(),
                createTaskConfig("foo", 2, 2, 2)))))).anyTimes();

    control.replay();

    assertTrue(quotaManager.hasRemaining(ROLE, new Quota(1, 1, 1)));
    assertFalse(quotaManager.hasRemaining(ROLE, new Quota(2, 2, 2)));
  }

  private IExpectationSetters<?> returnTasks(ScheduledTask... tasks) {
    return storageUtil.expectTaskFetch(ACTIVE_QUERY, tasks);
  }

  private IExpectationSetters<?> returnNoTasks() {
    return returnTasks();
  }

  private IExpectationSetters<Set<JobUpdateConfiguration>> expectUpdateQuery() {
    return expect(storageUtil.updateStore.fetchUpdateConfigs(ROLE));
  }

  private void noActiveUpdates() {
    expectUpdateQuery().andReturn(ImmutableSet.<JobUpdateConfiguration>of()).anyTimes();
  }

  private IExpectationSetters<Optional<Quota>> applyQuota(Quota quota) {
    return expect(storageUtil.quotaStore.fetchQuota(ROLE)).andReturn(Optional.of(quota));
  }

  private ScheduledTask createTask(String jobName, String taskId, int cpus, int ramMb, int diskMb) {
    return new ScheduledTask()
        .setStatus(ScheduleStatus.RUNNING)
        .setAssignedTask(
            new AssignedTask()
                .setTaskId(taskId)
                .setTask(createTaskConfig(jobName, cpus, ramMb, diskMb)));
  }

  private TaskConfig createTaskConfig(String jobName, int cpus, int ramMb, int diskMb) {
    return new TaskConfig()
        .setOwner(new Identity(ROLE, ROLE))
        .setJobName(jobName)
        .setNumCpus(cpus)
        .setRamMb(ramMb)
        .setDiskMb(diskMb)
        .setProduction(true);
  }
}
