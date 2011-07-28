package com.twitter.mesos.executor;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import org.apache.commons.io.FileSystemUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;

import com.twitter.common.application.ActionRegistry;
import com.twitter.common.base.Closure;
import com.twitter.common.base.Command;
import com.twitter.common.stats.StatImpl;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.BuildInfo;
import com.twitter.mesos.Message;
import com.twitter.mesos.Tasks;
import com.twitter.mesos.executor.Task.TaskRunException;
import com.twitter.mesos.gen.AssignedTask;
import com.twitter.mesos.gen.ScheduleStatus;
import com.twitter.mesos.gen.comm.ExecutorStatus;
import com.twitter.mesos.gen.comm.LiveTaskInfo;
import com.twitter.mesos.gen.comm.RegisteredTaskUpdate;
import com.twitter.mesos.gen.comm.SchedulerMessage;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.twitter.mesos.gen.ScheduleStatus.FAILED;
import static com.twitter.mesos.gen.ScheduleStatus.RUNNING;
import static com.twitter.mesos.gen.ScheduleStatus.STARTING;

/**
 * ExecutorCore
 *
 * TODO(William Farner): When a LiveTask is terminated, should we replace its entry with a DeadTask?
 *
 * @author William Farner
 */
public class ExecutorCore implements TaskManager {
  private static final Logger LOG = Logger.getLogger(MesosExecutorImpl.class.getName());

  /**
   * {@literal @Named} binding key for the task executor service.
   */
  public static final String TASK_EXECUTOR =
      "com.twitter.mesos.executor.ExecutorCore.TASK_EXECUTOR";

  private final Map<String, Task> tasks = Maps.newConcurrentMap();

  private final File executorRootDir;

  private final ScheduledExecutorService syncExecutor = new ScheduledThreadPoolExecutor(1,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("ExecutorSync-%d").build());

  private final AtomicReference<String> slaveId = new AtomicReference<String>();

  private final BuildInfo buildInfo;
  private final Function<AssignedTask, Task> taskFactory;
  private final ExecutorService taskExecutor;
  private final Function<Message, Integer> messageHandler;

  private final AtomicLong tasksReceived = Stats.exportLong("executor_tasks_received");
  private final AtomicLong taskFailures = Stats.exportLong("executor_task_launch_failures");
  private final AtomicLong tasksKilled = Stats.exportLong("executor_tasks_killed");

  @Inject
  public ExecutorCore(@ExecutorRootDir File executorRootDir, BuildInfo buildInfo,
      Function<AssignedTask, Task> taskFactory,
      @Named(TASK_EXECUTOR) ExecutorService taskExecutor,
      Function<Message, Integer> messageHandler) {
    this.executorRootDir = checkNotNull(executorRootDir);
    this.buildInfo = checkNotNull(buildInfo);
    this.taskFactory = checkNotNull(taskFactory);
    this.taskExecutor = checkNotNull(taskExecutor);
    this.messageHandler = checkNotNull(messageHandler);

    Stats.export(new StatImpl<Integer>("executor_tasks_stored") {
      @Override public Integer read() {
        return tasks.size();
      }
    });
  }

  /**
   * Adds dead tasks that the executor may report record of.
   *
   * @param deadTasks Dead tasks to store.
   */
  void addDeadTasks(Iterable<Task> deadTasks) {
    checkNotNull(deadTasks);

    for (Task task : deadTasks) {
      tasks.put(task.getId(), task);
    }
  }

  /**
   * Initiates periodic tasks that the executor performs (state sync, resource monitoring, etc).
   *
   * @param shutdownRegistry to register orderly shutdown of the periodic task scheduler.
   */
  void startPeriodicTasks(ActionRegistry shutdownRegistry) {
    new ResourceManager(this, executorRootDir, shutdownRegistry).start();
    startStateSync();
    startRegisteredTaskPusher();
    shutdownRegistry.addAction(new Command() {
      @Override public void execute() {
        LOG.info("Shutting down sync executor.");
        syncExecutor.shutdownNow();
      }
    });
  }

  void setSlaveId(String slaveId) {
    this.slaveId.set(checkNotNull(slaveId));
    LOG.info("Assigned slave ID: " + slaveId);
  }

  static class StateChange {
    final ScheduleStatus status;
    @Nullable final String message;

    StateChange(ScheduleStatus status) {
      this(status, null);
    }

    StateChange(ScheduleStatus status, @Nullable String message) {
      this.status = checkNotNull(status);
      this.message = message;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof StateChange)) {
        return false;
      }

      StateChange other = (StateChange) o;
      return new EqualsBuilder()
          .append(status, other.status)
          .append(message, other.message)
          .isEquals();
    }

    @Override
    public String toString() {
      return new ToStringBuilder(this)
          .append(status)
          .append(message)
          .toString();
    }
  }

  /**
   * Executes a task on the system.
   *
   * @param assignedTask The assigned task to run.
   * @param stateChangeCallback Called when the task transitions to different states.
   */
  public void executeTask(AssignedTask assignedTask,
      final Closure<StateChange> stateChangeCallback) {
    checkNotNull(assignedTask);
    checkNotNull(stateChangeCallback);

    final String taskId = assignedTask.getTaskId();

    LOG.info(String.format("Received task for execution: %s - %s",
        Tasks.jobKey(assignedTask), taskId));
    tasksReceived.incrementAndGet();

    final Task task = taskFactory.apply(assignedTask);
    tasks.put(taskId, task);

    stateChangeCallback.execute(new StateChange(STARTING));
    try {
      task.stage();
      stateChangeCallback.execute(new StateChange(RUNNING));
      task.run();
    } catch (TaskRunException e) {
      LOG.log(Level.SEVERE, "Failed to stage or run task " + taskId, e);
      taskFailures.incrementAndGet();
      task.terminate(FAILED);
      stateChangeCallback.execute(new StateChange(FAILED, e.getMessage()));
      deleteCompletedTask(taskId);
      return;
    }

    taskExecutor.execute(new Runnable() {
      @Override public void run() {
        LOG.info("Waiting for task " + taskId + " to complete.");
        ScheduleStatus state = task.blockUntilTerminated();
        LOG.info("Task " + taskId + " completed in state " + state);
        stateChangeCallback.execute(new StateChange(state));
      }
    });
  }

  public void stopLiveTask(String taskId) {
    Task task = tasks.get(taskId);

    if (task != null && task.isRunning()) {
      LOG.info("Killing task: " + task);
      tasksKilled.incrementAndGet();
      task.terminate(ScheduleStatus.KILLED);
    } else if (task == null) {
      LOG.severe("No such task found: " + taskId);
    } else {
      LOG.info("Kill request for task in state " + task.getScheduleStatus() + " ignored.");
    }
  }

  public Task getTask(String taskId) {
    return tasks.get(taskId);
  }

  public Iterable<Task> getTasks() {
    return Iterables.unmodifiableIterable(tasks.values());
  }

  @Override
  public Iterable<Task> getLiveTasks() {
    return Iterables.unmodifiableIterable(Iterables.filter(tasks.values(),
        new Predicate<Task>() {
          @Override public boolean apply(Task task) {
            return task.isRunning();
          }
        }));
  }

  @Override
  public boolean hasTask(String taskId) {
    return tasks.containsKey(taskId);
  }

  @Override
  public boolean isRunning(String taskId) {
    return hasTask(taskId) && tasks.get(taskId).isRunning();
  }

  @Override
  public void deleteCompletedTask(String taskId) {
    Preconditions.checkState(!isRunning(taskId), "Task " + taskId + " is still running!");
    tasks.remove(taskId);
  }

  /**
   * Cleanly shuts down the executor.
   *
   * @return The active tasks that were killed as the executor shut down.
   */
  public Iterable<Task> shutdownCore() {
    LOG.info("Shutting down executor core.");
    Iterable<Task> liveTasks = getLiveTasks();
    for (Task task : liveTasks) {
      stopLiveTask(task.getId());
    }

    return liveTasks;
  }

  private static String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOG.log(Level.SEVERE, "Failed to look up own hostname.", e);
      return null;
    }
  }

  private void startStateSync() {
    final Properties buildProperties = buildInfo.getProperties();

    final String DEFAULT = "unknown";
    final ExecutorStatus baseStatus = new ExecutorStatus()
        .setHost(getHostName())
        .setBuildUser(buildProperties.getProperty(BuildInfo.Key.USER.value, DEFAULT))
        .setBuildMachine(buildProperties.getProperty(BuildInfo.Key.MACHINE.value, DEFAULT))
        .setBuildPath(buildProperties.getProperty(BuildInfo.Key.PATH.value, DEFAULT))
        .setBuildGitTag(buildProperties.getProperty(BuildInfo.Key.GIT_TAG.value, DEFAULT))
        .setBuildGitRevision(buildProperties.getProperty(BuildInfo.Key.GIT_REVISION.value, DEFAULT))
        .setBuildTimestamp(buildProperties.getProperty(BuildInfo.Key.TIMESTAMP.value, DEFAULT));

    Runnable syncer = new Runnable() {
      @Override public void run() {
        if (slaveId.get() == null) {
          LOG.severe("slaveID not set, can't send executor status to scheduler.");
          return;
        }

        ExecutorStatus status = new ExecutorStatus(baseStatus).setSlaveId(slaveId.get());

        try {
          status.setDiskFreeKb(FileSystemUtils.freeSpaceKb(executorRootDir.getAbsolutePath()));
        } catch (IOException e) {
          LOG.log(Level.INFO, "Failed to get disk free space.", e);
        }

        SchedulerMessage message = new SchedulerMessage();
        message.setExecutorStatus(status);
        messageHandler.apply(new Message(message));
      }
    };

    // TODO(William Farner): Make sync interval configurable.
    syncExecutor.scheduleAtFixedRate(syncer, 30, 30, TimeUnit.SECONDS);
  }

  private void startRegisteredTaskPusher() {
    Runnable pusher = new Runnable() {
      @Override public void run() {
        RegisteredTaskUpdate update = new RegisteredTaskUpdate().setSlaveHost(getHostName());
        update.setTaskInfos(Lists.<LiveTaskInfo>newArrayList());

        for (Map.Entry<String, Task> entry : tasks.entrySet()) {
          Task task = entry.getValue();

          update.addToTaskInfos(new LiveTaskInfo()
              .setTaskId(entry.getKey())
              .setTaskInfo(task.getAssignedTask().getTask())
              .setResources(task.getResourceConsumption())
              .setStatus(task.getScheduleStatus()));
        }

        SchedulerMessage message = new SchedulerMessage();
        message.setTaskUpdate(update);
        messageHandler.apply(new Message(message));
      }
    };

    // TODO(William Farner): Make push interval configurable.
    syncExecutor.scheduleAtFixedRate(pusher, 5, 5, TimeUnit.SECONDS);
  }
}
