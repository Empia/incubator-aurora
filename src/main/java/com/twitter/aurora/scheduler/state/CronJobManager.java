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
package com.twitter.aurora.scheduler.state;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.apache.commons.lang.StringUtils;

import com.twitter.aurora.gen.CronCollisionPolicy;
import com.twitter.aurora.gen.JobConfiguration;
import com.twitter.aurora.gen.JobKey;
import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.scheduler.base.JobKeys;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.ScheduleException;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager.TaskDescriptionException;
import com.twitter.aurora.scheduler.configuration.ParsedConfiguration;
import com.twitter.aurora.scheduler.cron.CronException;
import com.twitter.aurora.scheduler.cron.CronScheduler;
import com.twitter.aurora.scheduler.events.PubsubEvent.EventSubscriber;
import com.twitter.aurora.scheduler.events.PubsubEvent.StorageStarted;
import com.twitter.aurora.scheduler.storage.Storage;
import com.twitter.aurora.scheduler.storage.Storage.MutateWork;
import com.twitter.aurora.scheduler.storage.Storage.Work;
import com.twitter.common.application.ShutdownRegistry;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.base.ExceptionalCommand;
import com.twitter.common.base.Supplier;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.BackoffHelper;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import static com.twitter.aurora.gen.ScheduleStatus.PENDING;

/**
 * A job scheduler that receives jobs that should be run periodically on a cron schedule.
 */
public class CronJobManager extends JobManager implements EventSubscriber {

  public static final String MANAGER_KEY = "CRON";

  @VisibleForTesting
  static final String CRON_USER = "cron";

  private static final Logger LOG = Logger.getLogger(CronJobManager.class.getName());

  @CmdLine(name = "cron_start_initial_backoff", help =
      "Initial backoff delay while waiting for a previous cron run to start.")
  private static final Arg<Amount<Long, Time>> CRON_START_INITIAL_BACKOFF =
      Arg.create(Amount.of(1L, Time.SECONDS));

  @CmdLine(name = "cron_start_max_backoff", help =
      "Max backoff delay while waiting for a previous cron run to start.")
  private static final Arg<Amount<Long, Time>> CRON_START_MAX_BACKOFF =
      Arg.create(Amount.of(1L, Time.MINUTES));

  private final AtomicLong cronJobsTriggered = Stats.exportLong("cron_jobs_triggered");
  private final AtomicLong cronJobLaunchFailures = Stats.exportLong("cron_job_launch_failures");

  // Maps from the unique job identifier to the unique identifier used internally by the cron
  // scheduler.
  private final Map<JobKey, String> scheduledJobs =
      Collections.synchronizedMap(Maps.<JobKey, String>newHashMap());

  // Prevents runs from dogpiling while waiting for a run to transition out of the KILLING state.
  // This is necessary because killing a job (if dictated by cron collision policy) is an
  // asynchronous operation.
  private final Map<JobKey, ParsedConfiguration> pendingRuns =
      Collections.synchronizedMap(Maps.<JobKey, ParsedConfiguration>newHashMap());

  private final StateManager stateManager;
  private final Storage storage;
  private final CronScheduler cron;
  private final ShutdownRegistry shutdownRegistry;
  private final BackoffHelper delayedStartBackoff;
  private final Executor delayedRunExecutor;

  @Inject
  CronJobManager(
      StateManager stateManager,
      Storage storage,
      CronScheduler cron,
      ShutdownRegistry shutdownRegistry) {

    this(
        stateManager,
        storage,
        cron,
        shutdownRegistry,
        Executors.newCachedThreadPool(
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("CronDelay-%d").build()));
  }

  @VisibleForTesting
  CronJobManager(
      StateManager stateManager,
      Storage storage,
      CronScheduler cron,
      ShutdownRegistry shutdownRegistry,
      Executor delayedRunExecutor) {

    this.stateManager = checkNotNull(stateManager);
    this.storage = checkNotNull(storage);
    this.cron = checkNotNull(cron);
    this.shutdownRegistry = checkNotNull(shutdownRegistry);
    this.delayedStartBackoff =
        new BackoffHelper(CRON_START_INITIAL_BACKOFF.get(), CRON_START_MAX_BACKOFF.get());
    this.delayedRunExecutor = checkNotNull(delayedRunExecutor);

    Stats.exportSize("cron_num_pending_runs", pendingRuns);
  }

  private void mapScheduledJob(JobConfiguration job, String scheduledJobKey) {
    JobKey jobKey = job.getKey();
    synchronized (scheduledJobs) {
      Preconditions.checkState(
          !scheduledJobs.containsKey(jobKey),
          "Illegal state - cron schedule already exists for " + JobKeys.toPath(jobKey));
      scheduledJobs.put(jobKey, scheduledJobKey);
    }
  }

  /**
   * Notifies the cron job manager that storage is started, and job configurations are ready to
   * load.
   *
   * @param storageStarted Event.
   */
  @Subscribe
  public void storageStarted(StorageStarted storageStarted) {
    cron.start();
    shutdownRegistry.addAction(new ExceptionalCommand<CronException>() {
      @Override public void execute() throws CronException {
        cron.stop();
      }
    });

    Iterable<JobConfiguration> crons =
        storage.consistentRead(new Work.Quiet<Iterable<JobConfiguration>>() {
          @Override public Iterable<JobConfiguration> apply(Storage.StoreProvider storeProvider) {
            return storeProvider.getJobStore().fetchJobs(MANAGER_KEY);
          }
        });

    for (JobConfiguration job : crons) {
      try {
        mapScheduledJob(job, scheduleJob(ParsedConfiguration.fromUnparsed(job)));
      } catch (ScheduleException e) {
        logLaunchFailure(job, e);
      } catch (TaskDescriptionException e) {
        logLaunchFailure(job, e);
      }
    }
  }

  private void logLaunchFailure(JobConfiguration job, Exception e) {
    cronJobLaunchFailures.incrementAndGet();
    LOG.log(Level.SEVERE, "Scheduling failed for recovered job " + job, e);
  }

  /**
   * Triggers execution of a job.
   *
   * @param jobKey Key of the job to start.
   */
  public void startJobNow(JobKey jobKey) throws TaskDescriptionException {
    checkNotNull(jobKey);

    Optional<JobConfiguration> jobConfig = fetchJob(jobKey);
    checkArgument(jobConfig.isPresent(), "No such cron job " + JobKeys.toPath(jobKey));

    cronTriggered(ParsedConfiguration.fromUnparsed(jobConfig.get()));
  }

  private void delayedRun(final Query.Builder query, final ParsedConfiguration config) {
    JobConfiguration job = config.getJobConfig();
    final String jobPath = JobKeys.toPath(job);
    final JobKey jobKey = job.getKey().deepCopy();
    LOG.info("Waiting for job to terminate before launching cron job " + jobPath);
    if (pendingRuns.put(jobKey, config) == null) {
      LOG.info("Launching a task to wait for job to finish: " + jobPath);
      // There was no run already pending for this job, launch a task to delay launch until the
      // existing run has terminated.
      delayedRunExecutor.execute(new Runnable() {
        @Override public void run() {
          runWhenTerminated(query, jobKey);
        }
      });
    }
  }

  private void runWhenTerminated(final Query.Builder query, final JobKey jobKey) {
    try {
      delayedStartBackoff.doUntilSuccess(new Supplier<Boolean>() {
        @Override public Boolean get() {
          if (!hasTasks(query)) {
            LOG.info("Initiating delayed launch of cron " + jobKey);
            ParsedConfiguration config = pendingRuns.remove(jobKey);
            checkNotNull(config, "Failed to fetch job for delayed run of " + jobKey);
            LOG.info("Launching " + config.getTaskConfigs().size() + " tasks.");
            stateManager.insertPendingTasks(config.getTaskConfigs());
            return true;
          } else {
            LOG.info("Not yet safe to run cron " + jobKey);
            return false;
          }
        }
      });
    } catch (InterruptedException e) {
      LOG.log(Level.WARNING, "Interrupted while trying to launch cron " + jobKey, e);
      Thread.currentThread().interrupt();
    }
  }

  private boolean hasTasks(Query.Builder query) {
    return !Storage.Util.consistentFetchTasks(storage, query).isEmpty();
  }

  public static CronCollisionPolicy orDefault(@Nullable CronCollisionPolicy policy) {
    return Optional.fromNullable(policy).or(CronCollisionPolicy.KILL_EXISTING);
  }

  /**
   * Triggers execution of a cron job, depending on the cron collision policy for the job.
   *
   * @param config The config of the job to be triggered.
   */
  @VisibleForTesting
  void cronTriggered(ParsedConfiguration config) {
    JobConfiguration job = config.getJobConfig();
    LOG.info(String.format("Cron triggered for %s at %s with policy %s",
        JobKeys.toPath(job), new Date(), job.getCronCollisionPolicy()));
    cronJobsTriggered.incrementAndGet();

    ImmutableSet.Builder<TaskConfig> builder = ImmutableSet.builder();

    final Query.Builder activeQuery = Query.jobScoped(job.getKey()).active();

    Set<ScheduledTask> activeTasks = Storage.Util.consistentFetchTasks(storage, activeQuery);

    if (activeTasks.isEmpty()) {
      builder.addAll(config.getTaskConfigs());
    } else {
      // Assign a default collision policy.
      CronCollisionPolicy collisionPolicy = orDefault(job.getCronCollisionPolicy());

      switch (collisionPolicy) {
        case KILL_EXISTING:
          try {
            schedulerCore.killTasks(activeQuery, CRON_USER);
            // Check immediately if the tasks are gone.  This could happen if the existing tasks
            // were pending.
            if (!hasTasks(activeQuery)) {
              builder.addAll(config.getTaskConfigs());
            } else {
              delayedRun(activeQuery, config);
            }
          } catch (ScheduleException e) {
            LOG.log(Level.SEVERE, "Failed to kill job.", e);
          }
          break;

        case CANCEL_NEW:
          break;

        case RUN_OVERLAP:
          Map<Integer, ScheduledTask> byShard =
              Maps.uniqueIndex(activeTasks, Tasks.SCHEDULED_TO_SHARD_ID);
          Map<Integer, ScheduleStatus> existingTasks =
              Maps.transformValues(byShard, Tasks.GET_STATUS);
          if (existingTasks.isEmpty()) {
            builder.addAll(config.getTaskConfigs());
          } else if (Iterables.any(existingTasks.values(), Predicates.equalTo(PENDING))) {
            LOG.info("Job " + JobKeys.toPath(job) + " has pending tasks, suppressing run.");
          } else {
            // To safely overlap this run, we need to adjust the shard IDs of the overlapping
            // run (maintaining the role/job/shard UUID invariant).
            int shardOffset = Ordering.natural().max(existingTasks.keySet()) + 1;
            LOG.info("Adjusting shard IDs of " + JobKeys.toPath(job) + " by " + shardOffset
                + " for overlapping cron run.");
            for (TaskConfig task : config.getTaskConfigs()) {
              builder.add(task.deepCopy().setShardId(task.getShardId() + shardOffset));
            }
          }
          break;

        default:
          LOG.severe("Unrecognized cron collision policy: " + job.getCronCollisionPolicy());
      }
    }

    Set<TaskConfig> newTasks = builder.build();
    if (!newTasks.isEmpty()) {
      stateManager.insertPendingTasks(newTasks);
    }
  }

  void updateJob(ParsedConfiguration config) throws ScheduleException {
    JobConfiguration job = config.getJobConfig();
    if (!hasCronSchedule(job)) {
      throw new ScheduleException("A cron job may not be updated to a non-cron job.");
    }
    String key = scheduledJobs.remove(job.getKey());
    checkNotNull(key, "Attempted to update unknown job " + JobKeys.toPath(job));
    cron.deschedule(key);
    checkArgument(receiveJob(config));
  }

  @Override
  public String getUniqueKey() {
    return MANAGER_KEY;
  }

  private static boolean hasCronSchedule(JobConfiguration job) {
    checkNotNull(job);
    return !StringUtils.isEmpty(job.getCronSchedule());
  }

  @Override
  public boolean receiveJob(ParsedConfiguration config) throws ScheduleException {
    final JobConfiguration job = config.getJobConfig();
    if (!hasCronSchedule(job)) {
      return false;
    }

    String scheduledJobKey = scheduleJob(config);
    storage.write(new MutateWork.NoResult.Quiet() {
      @Override protected void execute(Storage.MutableStoreProvider storeProvider) {
        storeProvider.getJobStore().saveAcceptedJob(MANAGER_KEY, job);
      }
    });
    mapScheduledJob(job, scheduledJobKey);

    return true;
  }

  private String scheduleJob(final ParsedConfiguration config) throws ScheduleException {
    final JobConfiguration job = config.getJobConfig();
    final String jobPath = JobKeys.toPath(job);
    if (!hasCronSchedule(job)) {
      throw new ScheduleException(
          String.format("Not a valid cronjob, %s has no cron schedule", jobPath));
    }

    if (!cron.isValidSchedule(job.getCronSchedule())) {
      throw new ScheduleException("Invalid cron schedule: " + job.getCronSchedule());
    }

    LOG.info(String.format("Scheduling cron job %s: %s", jobPath, job.getCronSchedule()));
    try {
      return cron.schedule(job.getCronSchedule(), new Runnable() {
        @Override public void run() {
          // TODO(William Farner): May want to record information about job runs.
          LOG.info("Running cron job: " + jobPath);
          cronTriggered(config);
        }
      });
    } catch (CronException e) {
      throw new ScheduleException("Failed to schedule cron job: " + e.getMessage(), e);
    }
  }

  @Override
  public Iterable<JobConfiguration> getJobs() {
    return storage.consistentRead(new Work.Quiet<Iterable<JobConfiguration>>() {
      @Override
      public Iterable<JobConfiguration> apply(Storage.StoreProvider storeProvider) {
        return storeProvider.getJobStore().fetchJobs(MANAGER_KEY);
      }
    });
  }

  @Override
  public boolean hasJob(JobKey jobKey) {
    return fetchJob(jobKey).isPresent();
  }

  private Optional<JobConfiguration> fetchJob(final JobKey jobKey) {
    checkNotNull(jobKey);
    return storage.consistentRead(new Work.Quiet<Optional<JobConfiguration>>() {
      @Override public Optional<JobConfiguration> apply(Storage.StoreProvider storeProvider) {
        return storeProvider.getJobStore().fetchJob(MANAGER_KEY, jobKey);
      }
    });
  }

  @Override
  public boolean deleteJob(final JobKey jobKey) {
    checkNotNull(jobKey);

    if (!hasJob(jobKey)) {
      return false;
    }

    String scheduledJobKey = scheduledJobs.remove(jobKey);
    if (scheduledJobKey != null) {
      cron.deschedule(scheduledJobKey);
      storage.write(new MutateWork.NoResult.Quiet() {
        @Override protected void execute(Storage.MutableStoreProvider storeProvider) {
          storeProvider.getJobStore().removeJob(jobKey);
        }
      });
      LOG.info("Successfully deleted cron job " + jobKey);
    }
    return true;
  }

  private final Function<String, String> keyToSchedule = new Function<String, String>() {
    @Override public String apply(String key) {
      return cron.getSchedule(key).or("Not found.");
    }
  };

  public Map<JobKey, String> getScheduledJobs() {
    synchronized (scheduledJobs) {
      return ImmutableMap.copyOf(Maps.transformValues(scheduledJobs, keyToSchedule));
    }
  }

  public Set<JobKey> getPendingRuns() {
    synchronized (pendingRuns) {
      return FluentIterable.from(pendingRuns.keySet()).transform(JobKeys.DEEP_COPY).toSet();
    }
  }
}
