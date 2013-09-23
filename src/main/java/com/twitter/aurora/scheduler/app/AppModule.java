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
package com.twitter.aurora.scheduler.app;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.apache.mesos.Scheduler;
import org.apache.zookeeper.data.ACL;

import com.twitter.aurora.GuiceUtils;
import com.twitter.aurora.scheduler.SchedulerModule;
import com.twitter.aurora.scheduler.async.AsyncModule;
import com.twitter.aurora.scheduler.events.PubsubEventModule;
import com.twitter.aurora.scheduler.filter.SchedulingFilterImpl;
import com.twitter.aurora.scheduler.http.ClusterName;
import com.twitter.aurora.scheduler.http.ServletModule;
import com.twitter.aurora.scheduler.metadata.MetadataModule;
import com.twitter.aurora.scheduler.periodic.PeriodicTaskModule;
import com.twitter.aurora.scheduler.quota.QuotaModule;
import com.twitter.aurora.scheduler.state.StateModule;
import com.twitter.aurora.scheduler.stats.AsyncStatsModule;
import com.twitter.common.application.ShutdownRegistry;
import com.twitter.common.application.modules.LifecycleModule;
import com.twitter.common.base.Command;
import com.twitter.common.inject.TimedInterceptor;
import com.twitter.common.net.pool.DynamicHostSet;
import com.twitter.common.stats.Stats;
import com.twitter.common.stats.StatsProvider;
import com.twitter.common.util.Clock;
import com.twitter.common.zookeeper.ServerSet;
import com.twitter.common.zookeeper.ServerSetImpl;
import com.twitter.common.zookeeper.SingletonService;
import com.twitter.common.zookeeper.ZooKeeperClient;
import com.twitter.common.zookeeper.ZooKeeperUtils;
import com.twitter.thrift.ServiceInstance;

/**
 * Binding module for the aurora scheduler application.
 */
class AppModule extends AbstractModule {
  private static final Logger LOG = Logger.getLogger(AppModule.class.getName());

  private final String clusterName;
  private final String serverSetPath;

  AppModule(String clusterName, String serverSetPath) {
    this.clusterName = clusterName;
    this.serverSetPath = serverSetPath;
  }

  @Override
  protected void configure() {
    // Enable intercepted method timings and context classloader repair.
    TimedInterceptor.bind(binder());
    GuiceUtils.bindJNIContextClassLoader(binder(), Scheduler.class);
    GuiceUtils.bindExceptionTrap(binder(), Scheduler.class);

    bind(Clock.class).toInstance(Clock.SYSTEM_CLOCK);

    bind(Key.get(String.class, ClusterName.class)).toInstance(clusterName);

    // Filter layering: notifier filter -> base impl
    PubsubEventModule.bind(binder(), SchedulingFilterImpl.class);
    bind(SchedulingFilterImpl.class).in(Singleton.class);

    LifecycleModule.bindStartupAction(binder(), RegisterShutdownStackPrinter.class);

    install(new AsyncModule());
    install(new AsyncStatsModule());
    install(new MetadataModule());
    install(new PeriodicTaskModule());
    install(new QuotaModule());
    install(new ServletModule());
    install(new SchedulerModule());
    install(new StateModule());

    bind(StatsProvider.class).toInstance(Stats.STATS_PROVIDER);
  }

  /**
   * Command to register a thread stack printer that identifies initiator of a shutdown.
   */
  private static class RegisterShutdownStackPrinter implements Command {
    private static final Function<StackTraceElement, String> STACK_ELEM_TOSTRING =
        new Function<StackTraceElement, String>() {
          @Override public String apply(StackTraceElement element) {
            return element.getClassName() + "." + element.getMethodName()
                + String.format("(%s:%s)", element.getFileName(), element.getLineNumber());
          }
        };

    private final ShutdownRegistry shutdownRegistry;

    @Inject
    RegisterShutdownStackPrinter(ShutdownRegistry shutdownRegistry) {
      this.shutdownRegistry = shutdownRegistry;
    }

    @Override
    public void execute() {
      shutdownRegistry.addAction(new Command() {
        @Override public void execute() {
          Thread thread = Thread.currentThread();
          String message = new StringBuilder()
              .append("Thread: ").append(thread.getName())
              .append(" (id ").append(thread.getId()).append(")")
              .append("\n")
              .append(Joiner.on("\n  ").join(
                  Iterables.transform(Arrays.asList(thread.getStackTrace()), STACK_ELEM_TOSTRING)))
              .toString();

          LOG.info("Shutdown initiated by: " + message);
        }
      });
    }
  }

  private static final List<ACL> ZOOKEEPER_ACL = ZooKeeperUtils.EVERYONE_READ_CREATOR_ALL;

  @Provides
  @Singleton
  ServerSet provideServerSet(ZooKeeperClient client) {
    return new ServerSetImpl(client, ZOOKEEPER_ACL, serverSetPath);
  }

  @Provides
  @Singleton
  DynamicHostSet<ServiceInstance> provideSchedulerHostSet(ServerSet serverSet) {
    // Used for a type re-binding of the serverset.
    return serverSet;
  }

  @Provides
  @Singleton
  SingletonService provideSingletonService(ZooKeeperClient client, ServerSet serverSet) {
    return new SingletonService(
        serverSet,
        SingletonService.createSingletonCandidate(client, serverSetPath, ZOOKEEPER_ACL));
  }
}
