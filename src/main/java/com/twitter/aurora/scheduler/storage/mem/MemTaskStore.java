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
package com.twitter.aurora.scheduler.storage.mem;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import org.apache.commons.lang.StringUtils;

import com.twitter.aurora.gen.JobKey;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.gen.TaskQuery;
import com.twitter.aurora.scheduler.base.JobKeys;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.storage.TaskStore;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.base.Closure;
import com.twitter.common.base.MorePreconditions;
import com.twitter.common.inject.TimedInterceptor.Timed;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.Stats;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An in-memory task store.
 */
class MemTaskStore implements TaskStore.Mutable {

  private static final Logger LOG = Logger.getLogger(MemTaskStore.class.getName());

  @CmdLine(name = "slow_query_log_threshold",
      help = "Log all queries that take at least this long to execute.")
  private static final Arg<Amount<Long, Time>> SLOW_QUERY_LOG_THRESHOLD =
      Arg.create(Amount.of(25L, Time.MILLISECONDS));

  /**
   * COPIER is separate from deepCopy to capture timing information.  Only the instrumented
   * {@link #deepCopy(ScheduledTask)} should interact directly with {@code COPIER}.
   */
  private static final Function<ScheduledTask, ScheduledTask> COPIER =
      Util.deepCopier();
  private final Function<ScheduledTask, ScheduledTask> deepCopy =
      new Function<ScheduledTask, ScheduledTask>() {
        @Override public ScheduledTask apply(ScheduledTask input) {
          return deepCopy(input);
        }
      };

  private final long slowQueryThresholdNanos = SLOW_QUERY_LOG_THRESHOLD.get().as(Time.NANOSECONDS);

  private final Map<String, ScheduledTask> tasks = Maps.newConcurrentMap();
  private final Multimap<JobKey, String> tasksByJobKey =
      Multimaps.synchronizedSetMultimap(HashMultimap.<JobKey, String>create());

  private final AtomicLong taskQueriesById = Stats.exportLong("task_queries_by_id");
  private final AtomicLong taskQueriesByJob = Stats.exportLong("task_queries_by_job");
  private final AtomicLong taskQueriesAll = Stats.exportLong("task_queries_all");

  @Timed("mem_storage_deep_copies")
  protected ScheduledTask deepCopy(ScheduledTask input) {
    return COPIER.apply(input);
  }

  @Timed("mem_storage_fetch_tasks")
  @Override
  public ImmutableSet<ScheduledTask> fetchTasks(Query.Builder query) {
    checkNotNull(query);

    long start = System.nanoTime();
    ImmutableSet<ScheduledTask> result = immutableMatches(query.get()).toSet();
    long durationNanos = System.nanoTime() - start;
    Level level = (durationNanos >= slowQueryThresholdNanos) ? Level.INFO : Level.FINE;
    if (LOG.isLoggable(level)) {
      Long time = Amount.of(durationNanos, Time.NANOSECONDS).as(Time.MILLISECONDS);
      LOG.log(level, "Query took " + time + " ms: " + query.get());
    }

    return result;
  }

  @Timed("mem_storage_save_tasks")
  @Override
  public void saveTasks(Set<ScheduledTask> newTasks) {
    checkNotNull(newTasks);
    Preconditions.checkState(Tasks.ids(newTasks).size() == newTasks.size(),
        "Proposed new tasks would create task ID collision.");

    Set<ScheduledTask> immutable =
        FluentIterable.from(newTasks).transform(deepCopy).toSet();
    tasks.putAll(Maps.uniqueIndex(immutable, Tasks.SCHEDULED_TO_ID));
    tasksByJobKey.putAll(taskIdsByJobKey(immutable));
  }

  private Multimap<JobKey, String> taskIdsByJobKey(Iterable<ScheduledTask> toIndex) {
    return Multimaps.transformValues(
        Multimaps.index(toIndex, Tasks.SCHEDULED_TO_JOB_KEY),
        Tasks.SCHEDULED_TO_ID);
  }

  @Timed("mem_storage_delete_all_tasks")
  @Override
  public void deleteAllTasks() {
    tasks.clear();
    tasksByJobKey.clear();
  }

  @Timed("mem_storage_delete_tasks")
  @Override
  public void deleteTasks(Set<String> taskIds) {
    checkNotNull(taskIds);

    for (String id : taskIds) {
      ScheduledTask removed = tasks.remove(id);
      if (removed != null) {
        tasksByJobKey.remove(Tasks.SCHEDULED_TO_JOB_KEY.apply(removed), Tasks.id(removed));
      }
    }
  }

  @Timed("mem_storage_mutate_tasks")
  @Override
  public ImmutableSet<ScheduledTask> mutateTasks(
      Query.Builder query,
      Closure<ScheduledTask> mutator) {

    checkNotNull(query);
    checkNotNull(mutator);

    ImmutableSet.Builder<ScheduledTask> mutated = ImmutableSet.builder();
    for (ScheduledTask original : immutableMatches(query.get())) {
      // Copy the object before invoking user code.  This is to support diff checking.
      ScheduledTask mutable = deepCopy.apply(original);
      mutator.execute(mutable);
      if (!original.equals(mutable)) {
        Preconditions.checkState(Tasks.id(original).equals(Tasks.id(mutable)),
            "A tasks ID may not be mutated.");

        // A diff is present - detach the mutable object from the closure's code to render
        // further mutation impossible.
        ScheduledTask updated = deepCopy.apply(mutable);
        tasks.put(Tasks.id(mutable), updated);
        mutated.add(deepCopy.apply(updated));
      }
    }

    return mutated.build();
  }

  @Timed("mem_storage_unsafe_modify_in_place")
  @Override
  public boolean unsafeModifyInPlace(String taskId, TaskConfig taskConfiguration) {
    MorePreconditions.checkNotBlank(taskId);
    checkNotNull(taskConfiguration);

    ScheduledTask stored = tasks.get(taskId);
    if (stored == null) {
      return false;
    } else {
      stored.getAssignedTask().setTask(Util.<TaskConfig>deepCopier().apply(taskConfiguration));
      return true;
    }
  }

  private static Predicate<ScheduledTask> queryFilter(final TaskQuery query) {
    return new Predicate<ScheduledTask>() {
      @Override public boolean apply(ScheduledTask task) {
        TaskConfig config = task.getAssignedTask().getTask();
        if (query.getOwner() != null) {
          if (!StringUtils.isBlank(query.getOwner().getRole())) {
            if (!query.getOwner().getRole().equals(config.getOwner().getRole())) {
              return false;
            }
          }
          if (!StringUtils.isBlank(query.getOwner().getUser())) {
            if (!query.getOwner().getUser().equals(config.getOwner().getUser())) {
              return false;
            }
          }
        }
        if (query.getEnvironment() != null) {
          if (!query.getEnvironment().equals(config.getEnvironment())) {
            return false;
          }
        }
        if (query.getJobName() != null) {
          if (!query.getJobName().equals(config.getJobName())) {
            return false;
          }
        }

        if (query.getTaskIds() != null) {
          if (!query.getTaskIds().contains(Tasks.id(task))) {
            return false;
          }
        }

        if (query.getStatusesSize() > 0) {
          if (!query.getStatuses().contains(task.getStatus())) {
            return false;
          }
        }
        if (!StringUtils.isEmpty(query.getSlaveHost())) {
          if (!query.getSlaveHost().equals(task.getAssignedTask().getSlaveHost())) {
            return false;
          }
        }
        if (query.getShardIdsSize() > 0) {
          if (!query.getShardIds().contains(config.getShardId())) {
            return false;
          }
        }

        return true;
      }
    };
  }

  private FluentIterable<ScheduledTask> immutableMatches(TaskQuery query) {
    return mutableMatches(query).transform(deepCopy);
  }

  private Iterable<ScheduledTask> fromIdIndex(Iterable<String> taskIds) {
    ImmutableList.Builder<ScheduledTask> matches = ImmutableList.builder();
    for (String id : taskIds) {
      ScheduledTask match = tasks.get(id);
      if (match != null) {
        matches.add(match);
      }
    }
    return matches.build();
  }

  private FluentIterable<ScheduledTask> mutableMatches(TaskQuery query) {
    // Apply the query against the working set.
    Iterable<ScheduledTask> from;
    Optional<JobKey> jobKey = JobKeys.from(Query.arbitrary(query));
    if (query.isSetTaskIds()) {
      taskQueriesById.incrementAndGet();
      from = fromIdIndex(query.getTaskIds());
    } else if (jobKey.isPresent()) {
      taskQueriesByJob.incrementAndGet();
      Collection<String> taskIds = tasksByJobKey.get(jobKey.get());
      if (taskIds == null) {
        from = ImmutableList.of();
      } else {
        from = fromIdIndex(taskIds);
      }
    } else {
      taskQueriesAll.incrementAndGet();
      from = tasks.values();
    }

    return FluentIterable.from(from).filter(queryFilter(query));
  }
}
