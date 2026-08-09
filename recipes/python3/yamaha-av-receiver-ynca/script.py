# Experimental Python 3 port pinned from museumsvictoria/nodel-recipes
# commit 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
'''Yamaha AV Receiver control using the YNCA protocol.'''


import math
import random as python_random
from urllib.parse import urlparse


YNCA_TCPPORT = 50000
MAX_COMMAND_LENGTH = 256
MAX_RESPONSE_LENGTH = 4096
MAX_PUBLIC_TEXT_LENGTH = 512
MAX_PRESENTATION_URL_LENGTH = 2048
MAX_PENDING_COMMANDS = 128
COMMAND_INTERVAL_MILLIS = 100
COMMAND_RESPONSE_TIMEOUT_SECONDS = 5
MUTING_TOGGLE_TIMEOUT_SECONDS = 5


param_Disabled = Parameter({'schema': {'type': 'boolean'}})
param_IPAddress = Parameter({
    'title': 'IP address (or blank for discovery via UPnP node)',
    'schema': {'type': 'string'},
})


local_event_LogLevel = LocalEvent({
    'group': 'Debug', 'order': 10000 + next_seq(), 'schema': {'type': 'integer'}})
local_event_LastCommsErrorTimestamp = LocalEvent({
    'title': 'Last Comms Error Timestamp',
    'group': 'Status',
    'order': 99999 + next_seq(),
    'schema': {'type': 'string'},
})
local_event_LastContactDetect = LocalEvent({
    'group': 'Status',
    'order': 99999 + next_seq(),
    'title': 'Last contact detect',
    'schema': {'type': 'string'},
})
local_event_Status = LocalEvent({
    'group': 'Status',
    'order': 99999 + next_seq(),
    'schema': {'type': 'object', 'properties': {
        'level': {'type': 'integer', 'order': 1},
        'message': {'type': 'string', 'order': 2},
    }},
})
local_event_DiscoveredIPAddress = LocalEvent({
    'group': 'Discovery', 'schema': {'type': 'string'}})


INPUTS_CODES = [
    'TUNER', 'PHONE', 'HDMI1', 'HDMI2', 'HDMI3', 'HDMI4', 'HDMI5',
    'AV1', 'AV2', 'VAUX', 'AUDIO1', 'AUDIO2', 'AUDIO3', 'AUDIO4',
    'AUDIO5', 'CLINK', 'SERVER', 'NETRADIO', 'BT', 'USB',
]


tcp = None
pollers = []
resultCallbacks = {}
connectionResetHandlers = []
protocolErrorHandlers = []
lastReceive = [0]
_tcpReceiveCount = 0
_commsConnected = False
_configuredDest = None
_restartScheduled = False
_shuttingDown = False
_connectionGeneration = 0
_connectionCycle = 0
commandQueue = []
_commandDrainGeneration = 0
_commandDrainScheduled = False
_commandRequestToken = 0
_activeCommandRequest = None


def warn(level, msg):
    if (local_event_LogLevel.getArg() or 0) >= level:
        console.warn(('  ' * level) + msg)


def log(level, msg):
    if (local_event_LogLevel.getArg() or 0) >= level:
        console.log(('  ' * level) + msg)


def boundedText(value, maximum=MAX_PUBLIC_TEXT_LENGTH):
    text = str(value)
    if len(text) <= maximum:
        return text
    return '%s... [%s characters truncated]' % (text[:maximum], len(text) - maximum)


def validHost(value):
    if is_blank(value):
        return None
    host = str(value).strip()
    if (not host or len(host) > 253 or ':' in host
            or any(char.isspace() for char in host)):
        return None
    return host


def boundedNumber(value, label):
    text = str(value)
    if len(text) > 32:
        console.warn('Ignoring invalid %s' % label)
        return None
    try:
        number = float(text)
    except (TypeError, ValueError):
        console.warn('Ignoring invalid %s' % label)
        return None
    if not math.isfinite(number) or abs(number) > 1000:
        console.warn('Ignoring out-of-range %s' % label)
        return None
    return number


def enumValue(value, allowed, label):
    text = str(value)
    if text not in allowed:
        console.warn('Ignoring invalid %s: %s' % (label, boundedText(text, 64)))
        return None
    return text


def formatVolume(value):
    number = boundedNumber(value, 'volume')
    if number is None:
        return None
    # Preserve the official recipe's truncation onto 0.5 boundaries.
    return '%1.1f' % (int((number * 10) / 5) * 5 / 10.0)


def formatPower(value):
    state = enumValue(value, ('On', 'Off'), 'power')
    return 'Standby' if state == 'Off' else state


def parsePower(value):
    if value == 'Standby':
        return 'Off'
    return enumValue(value, ('On',), 'power response')


def parseVolume(value):
    return boundedNumber(value, 'volume response')


def randomPeriod(lower, upper):
    return python_random.uniform(lower, upper)


def sendCommand(command):
    text = str(command)
    if len(text) > MAX_COMMAND_LENGTH or '\r' in text or '\n' in text:
        console.warn('YNCA command rejected: invalid length or delimiter')
        return False
    if tcp is None or not _commsConnected:
        console.warn('YNCA command rejected while comms are unavailable')
        return False
    if len(commandQueue) >= MAX_PENDING_COMMANDS:
        console.warn('YNCA command rejected: command queue is full')
        return False
    commandQueue.append(text)
    scheduleCommandDrain()
    return True


def clearCommandQueue():
    global _commandDrainGeneration, _commandDrainScheduled
    global _commandRequestToken, _activeCommandRequest
    commandQueue[:] = []
    _commandDrainGeneration += 1
    _commandDrainScheduled = False
    _commandRequestToken += 1
    _activeCommandRequest = None


def scheduleCommandDrain(delay=0):
    global _commandDrainScheduled
    if _commandDrainScheduled:
        return
    _commandDrainScheduled = True
    generation = _commandDrainGeneration
    call_safe(lambda: drainCommandQueue(generation), delay=delay)


def drainCommandQueue(generation):
    global _commandDrainScheduled, _commandRequestToken, _activeCommandRequest
    if generation != _commandDrainGeneration:
        return
    _commandDrainScheduled = False
    if (tcp is None or not _commsConnected or not commandQueue
            or _activeCommandRequest is not None):
        return

    command = commandQueue.pop(0)
    _commandRequestToken += 1
    requestToken = _commandRequestToken
    _activeCommandRequest = requestToken
    tcp.request(
        command,
        lambda response: commandRequestCompleted(generation, requestToken),
    )
    call_safe(
        lambda: commandRequestTimedOut(generation, requestToken),
        delay=COMMAND_RESPONSE_TIMEOUT_SECONDS,
    )


def commandRequestCompleted(generation, requestToken):
    global _activeCommandRequest
    if (generation != _commandDrainGeneration
            or requestToken != _activeCommandRequest):
        return
    _activeCommandRequest = None
    # The general receive callback runs after this handler and can enqueue a
    # derived command, so reserve the protocol interval even when empty now.
    scheduleCommandDrain(COMMAND_INTERVAL_MILLIS / 1000.0)


def commandRequestTimedOut(generation, requestToken):
    if (generation != _commandDrainGeneration
            or requestToken != _activeCommandRequest):
        return
    console.warn('YNCA command response timeout')
    stopComms('Command response timeout')
    if tcp is not None:
        tcp.drop()


def addPoller(handler, pollingPeriod):
    timer = Timer(
        handler,
        randomPeriod(pollingPeriod - 15, pollingPeriod + 15),
        randomPeriod(10, 15),
        stopped=True,
    )
    pollers.append(timer)
    return timer


def startPollers():
    for poller in pollers:
        poller.start()
    status_timer.start()


def stopPollers():
    for poller in pollers:
        poller.stop()
    status_timer.stop()


def bind_oneway_unitfunc(name, group, unitFunction, schema, title=None, pollingPeriod=120):
    title = name if title is None else title
    signal = Event(name, {
        'title': title, 'group': group, 'order': next_seq(), 'schema': schema})
    getter = Action(
        'Get %s' % name,
        lambda arg: sendCommand(unitFunction + '=?'),
        {'group': group, 'order': next_seq()},
    )

    def emitResult(result):
        signal.emit(boundedText(result))

    resultCallbacks[unitFunction] = emitResult
    addPoller(lambda: getter.call(), pollingPeriod)
    return getter, signal


def bind_twoway_unitfunc(
        name, group, unitFunction, schema, title=None, pollingPeriod=120,
        setterFilter=None, getterFilter=None, beforeSetter=None):
    title = name if title is None else title
    signal = Event(name, {
        'title': title, 'group': group, 'order': next_seq(), 'schema': schema})
    getter = Action(
        'Get ' + name,
        lambda arg: sendCommand(unitFunction + '=?'),
        {'title': 'Get %s' % title, 'group': group, 'order': next_seq()},
    )

    def setValue(arg):
        value = arg if setterFilter is None else setterFilter(arg)
        if value is not None:
            if beforeSetter is not None:
                beforeSetter()
            sendCommand(unitFunction + '=%s' % value)

    setter = Action(name, setValue, {
        'title': title,
        'group': group,
        'order': next_seq(),
        'schema': schema,
    })

    def emitResult(result):
        try:
            value = result if getterFilter is None else getterFilter(result)
        except (TypeError, ValueError) as error:
            console.warn('Ignoring invalid %s response: %s' % (
                name, boundedText(error, 128)))
            return
        if value is not None:
            signal.emit(value)

    resultCallbacks[unitFunction] = emitResult
    addPoller(lambda: getter.call(), pollingPeriod)
    return setter, signal


def bind_zone_unit_funcs(zonePrefix, zoneCode):
    powerAction, powerSignal = bind_twoway_unitfunc(
        '%s Power' % zonePrefix,
        '%s Power' % zonePrefix,
        '@%s:PWR' % zoneCode,
        {'type': 'string', 'enum': ['On', 'Off']},
        title='Power',
        setterFilter=formatPower,
        getterFilter=parsePower,
        pollingPeriod=20,
    )
    bind_twoway_unitfunc(
        '%s Volume' % zonePrefix,
        '%s Volume' % zonePrefix,
        '@%s:VOL' % zoneCode,
        {'type': 'number'},
        title='Volume',
        setterFilter=formatVolume,
        getterFilter=parseVolume,
    )
    mutingKnown = [False]
    mutingTogglePending = [False]
    mutingToggleGeneration = [0]

    def cancelMutingToggle():
        mutingToggleGeneration[0] += 1
        mutingTogglePending[0] = False

    def expireMutingToggle(generation):
        if (generation == mutingToggleGeneration[0]
                and mutingTogglePending[0]):
            cancelMutingToggle()
            console.warn('%s muting toggle expired while awaiting state' % zonePrefix)

    def parseMutingResponse(arg):
        state = enumValue(arg, ('On', 'Off'), 'muting response')
        if state is None:
            return None
        mutingKnown[0] = True
        if mutingTogglePending[0]:
            cancelMutingToggle()
            sendCommand('@%s:MUTE=%s' % (
                zoneCode, 'On' if state == 'Off' else 'Off'))
        return state

    def resetMutingState():
        mutingKnown[0] = False
        cancelMutingToggle()

    connectionResetHandlers.append(resetMutingState)
    protocolErrorHandlers.append(cancelMutingToggle)
    mutingAction, mutingSignal = bind_twoway_unitfunc(
        '%s Muting' % zonePrefix,
        '%s Volume' % zonePrefix,
        '@%s:MUTE' % zoneCode,
        {'type': 'string', 'enum': ['On', 'Off']},
        title='Muting',
        setterFilter=lambda arg: enumValue(arg, ('On', 'Off'), 'muting'),
        getterFilter=parseMutingResponse,
        beforeSetter=cancelMutingToggle,
    )

    def toggleMuting(arg):
        if not mutingKnown[0]:
            if not mutingTogglePending[0]:
                if sendCommand('@%s:MUTE=?' % zoneCode):
                    mutingToggleGeneration[0] += 1
                    mutingTogglePending[0] = True
                    generation = mutingToggleGeneration[0]
                    call_safe(
                        lambda: expireMutingToggle(generation),
                        delay=MUTING_TOGGLE_TIMEOUT_SECONDS,
                    )
            return
        mutingAction.call('On' if mutingSignal.getArg() == 'Off' else 'Off')

    Action(
        '%s Muting Toggle' % zonePrefix,
        toggleMuting,
        {'group': '%s Volume' % zonePrefix, 'title': 'Muting Toggle',
         'order': next_seq()},
    )
    inputAction, inputSignal = bind_twoway_unitfunc(
        '%s Input' % zonePrefix,
        '%s Inputs' % zonePrefix,
        '@%s:INP' % zoneCode,
        {'type': 'string', 'enum': INPUTS_CODES},
        title='Input',
        setterFilter=lambda arg: enumValue(arg, INPUTS_CODES, 'input'),
        getterFilter=lambda arg: enumValue(arg, INPUTS_CODES, 'input response'),
    )

    def bindInput(name):
        Action(
            '%s Input %s' % (zonePrefix, name),
            lambda arg: inputAction.call(name),
            {'group': '%s Inputs' % zonePrefix, 'title': name, 'order': next_seq()},
        )
        signal = Event(
            '%s Input %s' % (zonePrefix, name),
            {'group': '%s Inputs' % zonePrefix, 'title': name, 'order': next_seq(),
             'schema': {'type': 'boolean'}},
        )
        inputSignal.addEmitHandler(lambda arg: signal.emit(arg == name))

    for inputCode in INPUTS_CODES:
        bindInput(inputCode)


@after_main
def bind_sys_information():
    bind_oneway_unitfunc('Version', 'System Info', '@SYS:VERSION', {'type': 'string'})


@after_main
def bind_zones():
    bind_zone_unit_funcs('Main', 'MAIN')
    bind_zone_unit_funcs('Zone 2', 'ZONE2')


def handle_resp(resp):
    text = str(resp)
    if len(text) > MAX_RESPONSE_LENGTH:
        console.warn('Ignoring oversized YNCA response')
        return False
    indexOfEquals = text.find('=')
    if indexOfEquals <= 0:
        if text in ('@UNDEFINED', '@RESTRICTED'):
            for handler in protocolErrorHandlers:
                handler()
            return True
        log(2, 'handle_resp ignoring [%s]' % boundedText(text, 256))
        return False
    leftSide = text[:indexOfEquals]
    rightSide = text[indexOfEquals + 1:]
    callback = resultCallbacks.get(leftSide)
    if callback is None:
        return leftSide.startswith('@') and len(leftSide) <= 64
    callback(rightSide)
    return True


def tcp_connected(generation):
    global _commsConnected, _connectionCycle
    if generation != _connectionGeneration:
        return
    _connectionCycle += 1
    _commsConnected = True
    lastReceive[0] = 0
    tcp.clearQueue()
    clearCommandQueue()
    for resetHandler in connectionResetHandlers:
        resetHandler()
    local_event_Status.emit({'level': 1, 'message': 'Awaiting response'})
    startPollers()
    console.info('tcp_connected')


def tcp_received(generation, data):
    global _tcpReceiveCount
    if generation != _connectionGeneration or not _commsConnected:
        return
    _tcpReceiveCount += 1
    text = str(data)
    log(3, 'tcp_recv [%s]' % boundedText(text, 256))
    if not handle_resp(text):
        return
    lastReceive[0] = system_clock()
    local_event_LastContactDetect.emit(str(date_now()))
    if text in ('@UNDEFINED', '@RESTRICTED'):
        local_event_Status.emit({'level': 1, 'message': text[1:].title()})
    else:
        local_event_Status.emit({'level': 0, 'message': 'OK'})


def tcp_sent(generation, data):
    text = str(data)
    cycle = _connectionCycle
    call_safe(lambda: logTCPSent(generation, cycle, text))


def logTCPSent(generation, cycle, data):
    if generation != _connectionGeneration or cycle != _connectionCycle:
        return
    log(3, 'tcp_sent [%s]' % boundedText(data, 256))


def stopComms(message):
    global _commsConnected
    _commsConnected = False
    stopPollers()
    clearCommandQueue()
    if tcp is not None:
        tcp.clearQueue()
    local_event_LastCommsErrorTimestamp.emit(str(date_now()))
    local_event_Status.emit({'level': 2, 'message': message})


def tcp_disconnected(generation):
    cycle = _connectionCycle
    call_safe(lambda: handleTCPDisconnected(generation, cycle))


def handleTCPDisconnected(generation, cycle):
    if (generation == _connectionGeneration and cycle == _connectionCycle
            and not _shuttingDown):
        console.warn('tcp_disconnected')
        stopComms('Disconnected')


def tcp_timeout(generation):
    cycle = _connectionCycle
    call_safe(lambda: handleTCPTimeout(generation, cycle))


def handleTCPTimeout(generation, cycle):
    if (generation == _connectionGeneration and cycle == _connectionCycle
            and not _shuttingDown):
        console.warn('tcp_timeout')
        stopComms('Timeout')
        tcp.drop()


def createTCPConnection(dest):
    global tcp, _configuredDest, _connectionGeneration
    local_event_Status.emit({'level': 1, 'message': 'Connecting'})
    _connectionGeneration += 1
    generation = _connectionGeneration
    tcp = TCP(
        dest=dest,
        connected=lambda: tcp_connected(generation),
        received=lambda data: tcp_received(generation, data),
        sent=lambda data: tcp_sent(generation, data),
        disconnected=lambda: tcp_disconnected(generation),
        timeout=lambda: tcp_timeout(generation),
        sendDelimiters='\r\n',
        receiveDelimiters='\r\n',
    )
    _configuredDest = dest
    # Resources created by a discovery callback after initialisation need an
    # explicit start; start() is idempotent during the normal load lifecycle.
    tcp.start()


def retireTCPAndRestart():
    global tcp, _configuredDest, _restartScheduled, _connectionGeneration
    stopComms('Reconfiguring')
    _connectionGeneration += 1
    if tcp is not None:
        tcp.close()
        tcp = None
    _configuredDest = None
    if not _restartScheduled:
        _restartScheduled = True
        call(lambda: _node.restart(), delay=0.25)


def effectiveIPAddress():
    if not is_blank(param_IPAddress):
        return validHost(param_IPAddress)
    remoteBeacon = lookup_remote_event('UPnPBeacon')
    remoteNode = remoteBeacon.getNode() if remoteBeacon is not None else None
    if remoteNode is not None and str(remoteNode).strip().lower() not in ('', 'unbound'):
        return validHost(local_event_DiscoveredIPAddress.getArg())
    return None


@after_main
def setupTCP():
    if param_Disabled is True:
        if tcp is not None:
            tcp.close()
        local_event_Status.emit({'level': 1, 'message': 'Disabled'})
        console.warn('Disabled! nothing to do')
        return

    ipAddress = effectiveIPAddress()
    if ipAddress is None:
        if tcp is not None:
            retireTCPAndRestart()
        else:
            stopPollers()
            invalidExplicitAddress = not is_blank(param_IPAddress)
            message = 'Invalid address' if invalidExplicitAddress else 'Not configured'
            local_event_Status.emit({'level': 2, 'message': message})
            console.warn('%s; nothing to do' % message)
        return

    dest = '%s:%s' % (ipAddress, YNCA_TCPPORT)
    if tcp is not None and dest == _configuredDest:
        return
    if tcp is not None:
        retireTCPAndRestart()
        return
    if not _restartScheduled:
        console.info('Will connect to [%s]' % dest)
        createTCPConnection(dest)


def remote_event_UPnPBeacon(arg):
    if param_Disabled is True or not is_blank(param_IPAddress):
        return
    if arg is None or not hasattr(arg, 'get'):
        console.warn('Ignoring invalid UPnP beacon')
        return
    presentationURL = str(arg.get('presentationurl') or '')
    if not presentationURL or len(presentationURL) > MAX_PRESENTATION_URL_LENGTH:
        console.warn('Ignoring invalid UPnP presentation URL')
        return
    try:
        parsedURL = urlparse(presentationURL)
        host = validHost(parsedURL.hostname) if parsedURL.scheme in ('http', 'https') else None
    except ValueError:
        host = None
    if host is None:
        console.warn('Ignoring invalid UPnP presentation URL')
        return
    if host == local_event_DiscoveredIPAddress.getArg():
        if tcp is None:
            setupTCP()
        return
    local_event_DiscoveredIPAddress.emit(host)
    local_event_DiscoveredIPAddress.persistNow()
    setupTCP()


def statusCheck():
    if not _commsConnected:
        return
    difference = (system_clock() - lastReceive[0]) / 1000.0
    if lastReceive[0] <= 0 or difference > 90:
        previousContactValue = local_event_LastContactDetect.getArg()
        if not previousContactValue:
            message = 'Always been missing.'
        else:
            previousContact = date_parse(previousContactValue)
            roughDifference = (
                (date_now().getMillis() - previousContact.getMillis()) // 1000 // 60)
            if roughDifference < 60:
                message = 'Missing for approx. %s mins' % roughDifference
            elif roughDifference < 60 * 24:
                message = 'Missing since %s' % previousContact.toString('h:mm:ss a')
            else:
                message = 'Missing since %s' % previousContact.toString('h:mm:ss a, E d-MMM')
        local_event_Status.emit({'level': 2, 'message': message})
        return
    local_event_LastContactDetect.emit(str(date_now()))
    local_event_Status.emit({'level': 0, 'message': 'OK'})


status_timer = Timer(statusCheck, 75, 75, stopped=True)


def main():
    console.info('Yamaha YNCA recipe loaded')


@at_cleanup
def closeResources():
    global _shuttingDown, _commsConnected, _connectionGeneration
    _shuttingDown = True
    _commsConnected = False
    _connectionGeneration += 1
    stopPollers()
    clearCommandQueue()
    if tcp is not None:
        tcp.close()
