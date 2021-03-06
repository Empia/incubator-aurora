import getpass
import re

from twitter.aurora.config import AuroraConfig
from twitter.aurora.config.schema.base import Job, SimpleTask
from twitter.aurora.config.thrift import (
    convert as convert_pystachio_to_thrift,
    InvalidConfig,
    task_instance_from_job,
)
from twitter.thermos.config.schema import (
    Process,
    Resources,
    Task,
)

from gen.twitter.aurora.constants import GOOD_IDENTIFIER_PATTERN_PYTHON
from gen.twitter.aurora.test.constants import (
    INVALID_IDENTIFIERS,
    VALID_IDENTIFIERS,
)
from gen.twitter.aurora.ttypes import (
    CronCollisionPolicy,
    JobKey,
    Identity,
)

from pystachio import Map, String
from pystachio.naming import frozendict
import pytest


HELLO_WORLD = Job(
  name = 'hello_world',
  role = 'john_doe',
  environment = 'staging66',
  cluster = 'smf1-test',
  task = Task(
    name = 'main',
    processes = [Process(name = 'hello_world', cmdline = 'echo {{mesos.instance}}')],
    resources = Resources(cpu = 0.1, ram = 64 * 1048576, disk = 64 * 1048576),
  )
)


def test_simple_config():
  job = convert_pystachio_to_thrift(HELLO_WORLD)
  assert job.instanceCount == 1
  tti = job.taskConfig
  assert job.key == JobKey(
    role=HELLO_WORLD.role().get(),
    environment=HELLO_WORLD.environment().get(),
    name=HELLO_WORLD.name().get())
  assert job.owner == Identity(role=HELLO_WORLD.role().get(), user=getpass.getuser())
  assert job.cronSchedule == ''
  assert tti.jobName == 'hello_world'
  assert tti.isService == False
  assert tti.numCpus == 0.1
  assert tti.ramMb == 64
  assert tti.diskMb == 64
  assert tti.requestedPorts == set()
  assert tti.production == False
  assert tti.priority == 0
  assert tti.maxTaskFailures == 1
  assert tti.constraints == set()
  assert tti.packages == set()
  assert tti.environment == HELLO_WORLD.environment().get()


def test_config_with_options():
  hwc = HELLO_WORLD(
    production = True,
    priority = 200,
    service = True,
    cron_policy = 'RUN_OVERLAP',
    constraints = {
      'dedicated': 'your_mom',
      'cpu': 'x86_64'
    },
    environment = 'prod'
  )
  job = convert_pystachio_to_thrift(hwc)
  assert job.instanceCount == 1
  tti = job.taskConfig

  assert tti.production == True
  assert tti.priority == 200
  assert tti.isService == True
  assert job.cronCollisionPolicy == CronCollisionPolicy.RUN_OVERLAP
  assert len(tti.constraints) == 2
  assert tti.environment == 'prod'
  assert job.key.environment == 'prod'


def test_config_with_ports():
  hwc = HELLO_WORLD(
    task = HELLO_WORLD.task()(
      processes = [
        Process(name = 'hello_world',
                cmdline = 'echo {{thermos.ports[http]}} {{thermos.ports[admin]}}')
      ]
    )
  )
  config = AuroraConfig(hwc)
  job = config.job()
  assert job.taskConfig.requestedPorts == set(['http', 'admin'])


def test_config_with_bad_resources():
  MB = 1048576
  hwtask = HELLO_WORLD.task()

  convert_pystachio_to_thrift(HELLO_WORLD)

  good_resources = [
    Resources(cpu = 1.0, ram = 1 * MB, disk = 1 * MB)
  ]

  bad_resources = [
    Resources(cpu = 0, ram = 1 * MB, disk = 1 * MB),
    Resources(cpu = 1, ram = 0 * MB, disk = 1 * MB),
    Resources(cpu = 1, ram = 1 * MB, disk = 0 * MB),
    Resources(cpu = 1, ram = 1 * MB - 1, disk = 1 * MB),
    Resources(cpu = 1, ram = 1 * MB, disk = 1 * MB - 1)
  ]

  for resource in good_resources:
    convert_pystachio_to_thrift(HELLO_WORLD(task = hwtask(resources = resource)))

  for resource in bad_resources:
    with pytest.raises(ValueError):
      convert_pystachio_to_thrift(HELLO_WORLD(task = hwtask(resources = resource)))


def test_config_with_task_links():
  tl = Map(String, String)
  unresolved_tl = {
    'foo': 'http://%host%:{{thermos.ports[foo]}}',
    'bar': 'http://%host%:{{thermos.ports[bar]}}/{{mesos.instance}}',
  }
  resolved_tl = {
    'foo': 'http://%host%:%port:foo%',
    'bar': 'http://%host%:%port:bar%/%shard_id%'
  }
  aurora_config = AuroraConfig(HELLO_WORLD(task_links = tl(unresolved_tl)))
  assert aurora_config.task_links() == tl(resolved_tl)
  assert aurora_config.job().taskConfig.taskLinks == frozendict(resolved_tl)

  bad_tl = {
    'foo': '{{thermos.ports.bad}}'
  }
  with pytest.raises(AuroraConfig.InvalidConfig):
    AuroraConfig(HELLO_WORLD(task_links=tl(bad_tl))).job()


def test_unbound_references():
  def job_command(cmdline):
    return AuroraConfig(HELLO_WORLD(task = SimpleTask('hello_world', cmdline))).raw()

  # bindingless and bad => good bindings should work
  convert_pystachio_to_thrift(job_command('echo hello world'))
  convert_pystachio_to_thrift(job_command('echo {{mesos.user}}')
      .bind(mesos = {'user': '{{mesos.role}}'}))

  # unbound
  with pytest.raises(InvalidConfig):
    convert_pystachio_to_thrift(job_command('echo {{mesos.user}}'))


def test_cron_policy_alias():
  cron_schedule = '*/10 * * * *'
  CRON_HELLO_WORLD = HELLO_WORLD(cron_schedule=cron_schedule)

  tti = convert_pystachio_to_thrift(CRON_HELLO_WORLD)
  assert tti.cronSchedule == cron_schedule
  assert tti.cronCollisionPolicy == CronCollisionPolicy.KILL_EXISTING

  tti = convert_pystachio_to_thrift(CRON_HELLO_WORLD(cron_policy='RUN_OVERLAP'))
  assert tti.cronSchedule == cron_schedule
  assert tti.cronCollisionPolicy == CronCollisionPolicy.RUN_OVERLAP

  tti = convert_pystachio_to_thrift(CRON_HELLO_WORLD(cron_collision_policy='RUN_OVERLAP'))
  assert tti.cronSchedule == cron_schedule
  assert tti.cronCollisionPolicy == CronCollisionPolicy.RUN_OVERLAP

  with pytest.raises(ValueError):
    tti = convert_pystachio_to_thrift(CRON_HELLO_WORLD(cron_policy='RUN_OVERLAP',
                                                       cron_collision_policy='RUN_OVERLAP'))

  with pytest.raises(ValueError):
    tti = convert_pystachio_to_thrift(CRON_HELLO_WORLD(cron_collision_policy='GARBAGE'))


def test_packages_in_config():
  job = convert_pystachio_to_thrift(HELLO_WORLD, packages = [('alpha', 'beta', 1)])
  assert job.instanceCount == 1
  tti = job.taskConfig

  assert len(tti.packages) == 1
  pi = iter(tti.packages).next()
  assert pi.role == 'alpha'
  assert pi.name == 'beta'
  assert pi.version == 1


def test_task_instance_from_job():
  instance = task_instance_from_job(Job(health_check_interval_secs=30), 0)
  assert instance is not None


def test_identifier_validation():
  matcher = re.compile(GOOD_IDENTIFIER_PATTERN_PYTHON)
  for identifier in VALID_IDENTIFIERS:
    assert matcher.match(identifier)
  for identifier in INVALID_IDENTIFIERS:
    assert not matcher.match(identifier)
