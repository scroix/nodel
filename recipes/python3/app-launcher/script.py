# Experimental Python 3 port of museumsvictoria/nodel-recipes
# "App Launcher" at 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
"""Keep one configured long-running parent process in the requested power state."""

import os
import shutil
from itertools import islice


MAX_PATH_LENGTH = 4096
MAX_ARGUMENT_TEXT_LENGTH = 16384
MAX_ARGUMENT_COUNT = 128
MAX_ARGUMENT_LENGTH = 4096
MAX_FEEDBACK_LINE_LENGTH = 4096
MAX_FEEDBACK_FILTERS = 64
MAX_FEEDBACK_FILTER_LENGTH = 512


param_AppPath = Parameter({
    'title': 'App. Path (required, executable name with or without path)',
    'required': True,
    'schema': {
        'type': 'string',
        'hint': '(e.g. "C:\\MyApps\\myapp.exe" or "somethingOnThePath.exe")',
    },
    'desc': 'The full path to the application executable',
})

param_AppArgs = Parameter({
    'title': 'App. Args',
    'schema': {
        'type': 'string',
        'hint': 'e.g. --color BLUE --title "What is Your Story?"',
    },
    'desc': 'Application arguments, space delimited with quoting or escaping',
})

param_AppWorkingDir = Parameter({
    'title': 'App. Working Dir.',
    'schema': {'type': 'string', 'hint': 'e.g. c:\\temp'},
    'desc': 'Full path to the working directory',
})

param_PowerStateOnStart = Parameter({
    'title': 'Running state on Node Start',
    'schema': {'type': 'string', 'enum': ['On', 'Off', '(previous)']},
    'desc': 'What power state to use when the node starts',
})

param_FeedbackFilters = Parameter({
    'title': 'Console Feedback filters',
    'schema': {
        'type': 'array',
        'items': {
            'type': 'object',
            'properties': {
                'type': {'type': 'string', 'enum': ['Include', 'Exclude'], 'order': 1},
                'filter': {'type': 'string', 'order': 2},
            },
        },
    },
})


local_event_Running = LocalEvent({
    'group': 'Monitoring',
    'schema': {'type': 'string', 'enum': ['On', 'Off']},
    'desc': 'Locks to the actual running state of the application process',
})
local_event_DesiredPower = LocalEvent({
    'group': 'Power',
    'schema': {'type': 'string', 'enum': ['On', 'Off']},
    'desc': 'The desired power (or running state), set using the action',
})
local_event_Power = LocalEvent({
    'group': 'Power',
    'schema': {'type': 'string', 'enum': ['On', 'Partially On', 'Off', 'Partially Off']},
    'desc': 'The effective power state using Nodel power conventions',
})
local_event_LastStarted = LocalEvent({
    'group': 'Monitoring',
    'schema': {'type': 'string'},
    'desc': 'The last time the application started',
})
local_event_FirstInterrupted = LocalEvent({
    'group': 'Monitoring',
    'schema': {'type': 'string'},
    'desc': 'The first time the process was interrupted',
})
local_event_LastInterrupted = LocalEvent({
    'group': 'Monitoring',
    'schema': {'type': 'string'},
    'desc': 'The last time the process was interrupted',
})
local_event_PowerOn = LocalEvent({
    'group': 'Power',
    'title': 'On',
    'order': next_seq(),
    'schema': {'type': 'boolean'},
})
local_event_PowerOff = LocalEvent({
    'group': 'Power',
    'title': 'Off',
    'order': next_seq(),
    'schema': {'type': 'boolean'},
})
local_event_Status = LocalEvent({
    'order': -100,
    'group': 'Status',
    'schema': {
        'type': 'object',
        'properties': {
            'level': {'type': 'integer'},
            'message': {'type': 'string'},
        },
    },
})


_configured = False
_resolvedAppPath = None
_powerGeneration = 0
_runningGeneration = None
_intentionalStopThrough = -1


def boundedText(value, maximum, label):
    if value is None:
        return None
    text = str(value)
    if len(text) > maximum:
        console.warn('%s exceeds the %s character limit.' % (label, maximum))
        return None
    if '\x00' in text:
        console.warn('%s contains a null character.' % label)
        return None
    return text


def decodeArgList(argsString):
    if is_blank(argsString):
        return []

    text = boundedText(argsString, MAX_ARGUMENT_TEXT_LENGTH, 'App. Args')
    if text is None:
        return None

    args = []
    current = []
    quote = None
    tokenStarted = False
    index = 0
    while index < len(text):
        char = text[index]
        if quote is not None:
            if char == quote:
                quote = None
            elif char == '\\' and index + 1 < len(text) and text[index + 1] == quote:
                index += 1
                current.append(text[index])
            else:
                current.append(char)
            tokenStarted = True
        elif char.isspace():
            if tokenStarted:
                args.append(''.join(current))
                current = []
                tokenStarted = False
        elif char == '"' or (char == "'" and not tokenStarted):
            quote = char
            tokenStarted = True
        elif char == '\\' and index + 1 < len(text) and (
                text[index + 1].isspace() or text[index + 1] in ('"', "'")):
            index += 1
            current.append(text[index])
            tokenStarted = True
        else:
            current.append(char)
            tokenStarted = True

        if len(current) > MAX_ARGUMENT_LENGTH:
            console.warn('App. Args contains an oversized argument.')
            return None
        if len(args) > MAX_ARGUMENT_COUNT:
            console.warn('App. Args exceeds the %s argument limit.' % MAX_ARGUMENT_COUNT)
            return None
        index += 1

    if quote is not None:
        console.warn('App. Args contains an unterminated quote.')
        return None
    if tokenStarted:
        args.append(''.join(current))
    if len(args) > MAX_ARGUMENT_COUNT:
        console.warn('App. Args exceeds the %s argument limit.' % MAX_ARGUMENT_COUNT)
        return None
    return args


def resolveAppPath(value):
    text = boundedText(value, MAX_PATH_LENGTH, 'App. Path')
    if text is None or is_blank(text):
        return None

    expanded = os.path.expanduser(text)
    if os.path.isabs(expanded) or os.path.dirname(expanded):
        candidate = os.path.abspath(expanded)
    else:
        candidate = shutil.which(expanded)

    if candidate is None or not os.path.isfile(candidate):
        return None
    return os.path.realpath(candidate)


def process_started():
    global _runningGeneration

    if local_event_DesiredPower.getArg() != 'On':
        console.info('Application started after an Off request; stopping it immediately.')
        _process.stop()
        local_event_Running.emit('Off')
        return

    _runningGeneration = _powerGeneration
    console.info('Application started.')
    local_event_Running.emit('On')
    local_event_LastStarted.emit(str(date_now()))


def process_stopped(exitCode):
    global _runningGeneration

    console.info('Application stopped with exit code %s.' % exitCode)
    nowString = str(date_now())
    interrupted = (
        local_event_DesiredPower.getArg() == 'On'
        and _runningGeneration is not None
        and _runningGeneration > _intentionalStopThrough
    )
    if interrupted:
        local_event_LastInterrupted.emit(nowString)
        if is_blank(local_event_FirstInterrupted.getArg()):
            local_event_FirstInterrupted.emit(nowString)
    _runningGeneration = None
    local_event_Running.emit('Off')


def process_feedback(line):
    text = str(line or '')
    if len(text) > MAX_FEEDBACK_LINE_LENGTH:
        text = text[:MAX_FEEDBACK_LINE_LENGTH] + ' [truncated]'

    includeFiltering = False
    keep = None
    try:
        filters = iter(param_FeedbackFilters or [])
    except TypeError:
        filters = iter(())
    for filterInfo in islice(filters, MAX_FEEDBACK_FILTERS):
        if filterInfo is None:
            continue
        getValue = getattr(filterInfo, 'get', None)
        if not callable(getValue):
            continue
        filterType = str(getValue('type') or '')
        filterText = str(getValue('filter') or '')[:MAX_FEEDBACK_FILTER_LENGTH]
        matches = filterText in text
        if filterType == 'Include':
            includeFiltering = True
            if matches:
                keep = True
        elif filterType == 'Exclude' and matches:
            keep = False

    if keep is None:
        keep = not includeFiltering
    if keep:
        console.info('feedback> [%s]' % text)


_process = Process(
    [],
    started=process_started,
    stdout=process_feedback,
    stderr=process_feedback,
    stopped=process_stopped,
)


@before_main
def syncRunningEvent():
    local_event_Running.emit('Off')


def determinePower(arg=None):
    desired = local_event_DesiredPower.getArg()
    running = local_event_Running.getArg()
    if desired is None or desired == running:
        state = running
    else:
        state = 'Partially %s' % desired
    local_event_Power.emit(state)
    local_event_PowerOn.emit(running == 'On')
    local_event_PowerOff.emit(running == 'Off')


@after_main
def bindPower():
    local_event_Running.addEmitHandler(determinePower)
    local_event_DesiredPower.addEmitHandler(determinePower)
    determinePower()


@after_main
def ensurePersistSignals():
    def ensure(signal):
        signal.addEmitHandler(lambda arg: signal.persistNow())

    for signal in [
        local_event_Running,
        local_event_DesiredPower,
        local_event_Power,
        local_event_LastStarted,
        local_event_FirstInterrupted,
        local_event_LastInterrupted,
    ]:
        ensure(signal)


def main():
    global _configured, _resolvedAppPath

    if is_blank(param_AppPath):
        _process.stop()
        local_event_Status.emit({'level': 2, 'message': 'Not configured'})
        console.warn('No App. Path has been specified; the launcher is disabled.')
        return

    _resolvedAppPath = resolveAppPath(param_AppPath)
    if _resolvedAppPath is None:
        _process.stop()
        local_event_Status.emit({'level': 2, 'message': 'Application not found'})
        console.error('The App. Path could not be found or was invalid.')
        return

    working = None
    if not is_blank(param_AppWorkingDir):
        working = boundedText(param_AppWorkingDir, MAX_PATH_LENGTH, 'App. Working Dir.')
        if working is None or not os.path.isdir(working):
            _process.stop()
            local_event_Status.emit({'level': 2, 'message': 'Working directory not found'})
            console.error('The App. working directory could not be found.')
            return
        working = os.path.realpath(working)

    arguments = decodeArgList(param_AppArgs)
    if arguments is None:
        _process.stop()
        local_event_Status.emit({'level': 2, 'message': 'Invalid application arguments'})
        return

    command = [_resolvedAppPath] + arguments
    if working is not None:
        _process.setWorking(working)
    _process.setCommand(command)
    _configured = True
    local_event_Status.emit({'level': 0, 'message': 'Ready'})
    console.info('Application configured: [%s] with %s argument(s).' % (
        _resolvedAppPath, len(arguments)))

    if param_PowerStateOnStart == 'On':
        Power.call('On')
    elif param_PowerStateOnStart == 'Off':
        Power.call('Off')
    elif local_event_DesiredPower.getArg() != 'On':
        console.info('Desired power was previously off, so the application will not start.')
        _process.stop()


@local_action({
    'group': 'Power',
    'order': next_seq(),
    'schema': {'type': 'string', 'enum': ['On', 'Off']},
    'desc': 'Also clears First Interrupted warnings',
})
def Power(arg):
    global _powerGeneration, _intentionalStopThrough

    local_event_FirstInterrupted.emit('')
    if arg == 'On':
        if not _configured:
            console.warn('Cannot start because App. Path is not configured.')
            return
        _powerGeneration += 1
        local_event_DesiredPower.emit('On')
        _process.start()
    elif arg == 'Off':
        _powerGeneration += 1
        _intentionalStopThrough = _powerGeneration
        local_event_DesiredPower.emit('Off')
        _process.stop()
    else:
        console.warn('Power must be On or Off.')


@local_action({'group': 'Power', 'title': 'On', 'order': next_seq()})
def PowerOn():
    Power.call('On')


@local_action({'group': 'Power', 'title': 'Off', 'order': next_seq()})
def PowerOff():
    Power.call('Off')


def safeEventDate(signal):
    try:
        return date_parse(signal.getArg() or '1960')
    except Exception:
        return date_parse('1960')


def statusCheck():
    if not _configured:
        local_event_Status.emit({'level': 2, 'message': 'Not configured'})
        return

    now = date_now()
    firstInterrupted = safeEventDate(local_event_FirstInterrupted)
    lastInterrupted = safeEventDate(local_event_LastInterrupted)
    firstInterruptedDiff = now.getMillis() - firstInterrupted.getMillis()

    if firstInterruptedDiff < 4 * 24 * 3600 * 1000:
        if firstInterrupted == lastInterrupted:
            times = 'last time %s' % toBriefTime(lastInterrupted)
        else:
            times = 'last time %s, first time %s' % (
                toBriefTime(lastInterrupted), toBriefTime(firstInterrupted))
        local_event_Status.emit({
            'level': 1,
            'message': 'Application interruptions may be taking place (%s)' % times,
        })
    elif local_event_DesiredPower.getArg() == 'On' and local_event_Power.getArg() != 'On':
        local_event_Status.emit({'level': 2, 'message': 'Application is not running'})
    else:
        local_event_Status.emit({'level': 0, 'message': 'OK'})


statusCheck_timer = Timer(statusCheck, 30)


def toBriefTime(dateTime):
    diff = (date_now().getMillis() - dateTime.getMillis()) // 60000
    if diff == 0:
        return '<1 min ago'
    if diff < 60:
        return '%s mins ago' % diff
    if diff < 24 * 60:
        return dateTime.toString('h:mm:ss a')
    if diff < 365 * 24 * 60:
        return dateTime.toString('h:mm:ss a, E d-MMM')
    if diff > 10 * 365 * 24 * 60:
        return 'never'
    return '>1 year'
