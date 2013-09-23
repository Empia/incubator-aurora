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
package com.twitter.aurora.scheduler.storage;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.twitter.aurora.gen.JobConfiguration;
import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.TaskEvent;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager;
import com.twitter.aurora.scheduler.storage.Storage.MutableStoreProvider;
import com.twitter.common.base.Closure;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.Clock;

import static com.twitter.aurora.scheduler.base.Tasks.SCHEDULED_TO_JOB_KEY;
import static com.twitter.aurora.scheduler.base.Tasks.SCHEDULED_TO_SHARD_ID;

/**
 * Utility class to contain and perform storage backfill operations.
 * TODO(Sathya): Add test coverage for this class.
 */
public final class StorageBackfill {

  private static final Logger LOG = Logger.getLogger(StorageBackfill.class.getName());

  private static final AtomicLong SHARD_SANITY_CHECK_FAILS =
      Stats.exportLong("shard_sanity_check_failures");

  private StorageBackfill() {
    // Utility class.
  }

  private static void backfillJobDefaults(JobStore.Mutable jobStore) {
    for (String id : jobStore.fetchManagerIds()) {
      for (JobConfiguration job : jobStore.fetchJobs(id)) {
        ConfigurationManager.applyDefaultsIfUnset(job);
        jobStore.saveAcceptedJob(id, job);
      }
    }
  }

  private static void guaranteeTaskHasEvents(ScheduledTask task, Clock clock) {
    TaskEvent latestEvent = task.isSetTaskEvents()
        ? Iterables.getLast(task.getTaskEvents(), null) : null;
    if ((latestEvent == null) || (latestEvent.getStatus() != task.getStatus())) {
      LOG.severe("Task " + Tasks.id(task) + " has no event for current status.");
      task.addToTaskEvents(new TaskEvent(clock.nowMillis(), task.getStatus())
          .setMessage("Synthesized missing event."));
    }
  }

  private static void guaranteeShardUniqueness(
      ScheduledTask task,
      TaskStore taskStore,
      Clock clock) {

    if (Tasks.isActive(task.getStatus())) {
      // Perform a sanity check on the number of active shards.
      Query.Builder query = Query.shardScoped(
          SCHEDULED_TO_JOB_KEY.apply(task), SCHEDULED_TO_SHARD_ID.apply(task)).active();
      Set<String> activeTasksInShard = FluentIterable.from(taskStore.fetchTasks(query))
          .transform(Tasks.SCHEDULED_TO_ID)
          .toSet();

      if (activeTasksInShard.size() > 1) {
        SHARD_SANITY_CHECK_FAILS.incrementAndGet();
        LOG.severe("Active shard sanity check failed when loading " + Tasks.id(task)
            + ", active tasks found: " + activeTasksInShard);

        // We want to keep exactly one task from this shard, so sort the IDs and keep the
        // highest (newest) in the hopes that it is legitimately running.
        String newestTask = Iterables.getLast(Sets.newTreeSet(activeTasksInShard));
        if (!Tasks.id(task).equals(newestTask)) {
          task.setStatus(ScheduleStatus.KILLED);
          task.addToTaskEvents(new TaskEvent(clock.nowMillis(), ScheduleStatus.KILLED)
              .setMessage("Killed duplicate shard."));
          // TODO(wfarner); Circle back if this is necessary.  Currently there's a race
          // condition between the time the scheduler is actually available without hitting
          // IllegalStateException (see DriverImpl).
          // driver.killTask(Tasks.id(task));
        } else {
          LOG.info("Retaining task " + Tasks.id(task));
        }
      }
    }
  }

  /**
   * Backfills the storage to make it match any assumptions that may have changed since
   * the structs were first written.
   *
   * @param storeProvider Storage provider.
   * @param clock Clock, used for timestamping backfilled task events.
   */
  public static void backfill(final MutableStoreProvider storeProvider, final Clock clock) {
    backfillJobDefaults(storeProvider.getJobStore());

    LOG.info("Performing shard uniqueness sanity check.");
    storeProvider.getUnsafeTaskStore().mutateTasks(Query.unscoped(), new Closure<ScheduledTask>() {
      @Override public void execute(final ScheduledTask task) {
        ConfigurationManager.applyDefaultsIfUnset(task.getAssignedTask().getTask());
        guaranteeTaskHasEvents(task, clock);
        guaranteeShardUniqueness(task, storeProvider.getUnsafeTaskStore(), clock);
        // TODO(ksweeney): Guarantee tasks pass current validation code here and quarantine if they
        // don't.
      }
    });
  }
}
