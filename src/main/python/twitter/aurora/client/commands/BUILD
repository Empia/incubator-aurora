python_library(
  name = 'all',
  dependencies = [
    pants(':core'),
    pants(':help'),
    pants(':run'),
    pants(':ssh'),
  ]
)

python_library(
  name = 'admin',
  sources = ['admin.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/app'),
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('aurora/twitterdeps/src/python/twitter/common/quantity'),
    pants('src/main/python/twitter/aurora/admin:mesos_maintenance'),
    pants('src/main/python/twitter/aurora/client/api'),
    pants('src/main/python/twitter/aurora/client:base'),
    pants('src/main/python/twitter/aurora/common:clusters'),
    pants('src/main/thrift/com/twitter/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'core',
  sources = ['core.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/app'),
    pants('aurora/twitterdeps/src/python/twitter/common/log'),
    pants('aurora/twitterdeps/src/python/twitter/common/python'),
    pants('src/main/python/twitter/aurora/client/api:command_runner'),
    pants('src/main/python/twitter/aurora/client/api:disambiguator'),
    pants('src/main/python/twitter/aurora/client/api:job_monitor'),
    pants('src/main/python/twitter/aurora/client/api:updater'),
    pants('src/main/python/twitter/aurora/client/hooks'),
    pants('src/main/python/twitter/aurora/client:base'),
    pants('src/main/python/twitter/aurora/client:config'),
    pants('src/main/python/twitter/aurora/client:factory'),
    pants('src/main/python/twitter/aurora/client:options'),
    pants('src/main/python/twitter/aurora/common'),
    pants('src/main/thrift/com/twitter/aurora/gen:py-thrift'),
  ]
)

python_library(
  name = 'help',
  sources = ['help.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/app'),
    pants('src/main/python/twitter/aurora/client:base'),
  ]
)

python_library(
  name = 'run',
  sources = ['run.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/app'),
    pants('src/main/python/twitter/aurora/client/api:command_runner'),
    pants('src/main/python/twitter/aurora/client:base'),
    pants('src/main/python/twitter/aurora/client:options'),
    pants('src/main/python/twitter/aurora/common:aurora_job_key'),
    pants('src/main/python/twitter/aurora/common:clusters'),
  ]
)

python_library(
  name = 'ssh',
  sources = ['ssh.py'],
  dependencies = [
    pants('aurora/twitterdeps/src/python/twitter/common/app'),
    pants('src/main/python/twitter/aurora/client/api:command_runner'),
    pants('src/main/python/twitter/aurora/client:base'),
    pants('src/main/python/twitter/aurora/client:factory'),
    pants('src/main/python/twitter/aurora/client:options'),
    pants('src/main/python/twitter/aurora/common:aurora_job_key'),
    pants('src/main/python/twitter/aurora/common:clusters'),
  ]
)
