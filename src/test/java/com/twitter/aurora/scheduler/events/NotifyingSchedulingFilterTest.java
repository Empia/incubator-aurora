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
package com.twitter.aurora.scheduler.events;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.scheduler.configuration.Resources;
import com.twitter.aurora.scheduler.events.PubsubEvent.Vetoed;
import com.twitter.aurora.scheduler.filter.SchedulingFilter;
import com.twitter.aurora.scheduler.filter.SchedulingFilter.Veto;
import com.twitter.common.base.Closure;
import com.twitter.common.testing.easymock.EasyMockTest;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

public class NotifyingSchedulingFilterTest extends EasyMockTest {

  private static final TaskConfig TASK = new TaskConfig()
      .setNumCpus(1)
      .setRamMb(1024)
      .setDiskMb(1024);
  private static final Resources TASK_RESOURCES = Resources.from(TASK);
  private static final String TASK_ID = "taskId";
  private static final String SLAVE = "slaveHost";

  private static final Veto VETO_1 = new Veto("veto1", 1);
  private static final Veto VETO_2 = new Veto("veto2", 2);

  private SchedulingFilter filter;

  private Closure<PubsubEvent> eventSink;
  private SchedulingFilter delegate;

  @Before
  public void setUp() {
    delegate = createMock(SchedulingFilter.class);
    eventSink = createMock(new Clazz<Closure<PubsubEvent>>() { });
    filter = new NotifyingSchedulingFilter(delegate, eventSink);
  }

  @Test
  public void testEvents() {
    Set<Veto> vetoes = ImmutableSet.of(VETO_1, VETO_2);
    expect(delegate.filter(TASK_RESOURCES, SLAVE, TASK, TASK_ID)).andReturn(vetoes);
    eventSink.execute(new Vetoed(TASK_ID, vetoes));

    control.replay();

    assertEquals(vetoes, filter.filter(TASK_RESOURCES, SLAVE, TASK, TASK_ID));
  }

  @Test
  public void testNoVetoes() {
    Set<Veto> vetoes = ImmutableSet.of();
    expect(delegate.filter(TASK_RESOURCES, SLAVE, TASK, TASK_ID)).andReturn(vetoes);

    control.replay();

    assertEquals(vetoes, filter.filter(TASK_RESOURCES, SLAVE, TASK, TASK_ID));
  }
}
