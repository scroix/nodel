# Experimental Python 3 port pinned from museumsvictoria/nodel-recipes
# commit 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
"""Alcorn McBride 8 TraXX serial protocol over managed TCP."""


STATUS_CHECK_INTERVAL = 30
CHANNELS = ['1', '2', '3', '4', '5', '6', '7', '8', '*']
MAX_RESPONSE_LENGTH = 4096
ERROR_CODES = {
    'E00': 'Communication Error',
    'E04': 'Feature Not Available Yet',
    'E11': 'Media Not Present',
    'E12': 'Search Error',
    'E20': 'Format Error'
}


param_ipAddress = Parameter({'schema': {'type': 'string', 'hint': 'will override bindings'}})
param_Port = Parameter({'schema': {'type': 'string', 'hint': 'will override bindings'}})

local_event_IPAddress = LocalEvent({'group': 'Network Info', 'order': 1,
                                    'schema': {'type': 'string'}})
local_event_Port = LocalEvent({'group': 'Network Info', 'order': 2,
                               'schema': {'type': 'integer'}})
local_event_LastContactDetect = LocalEvent({'group': 'Status', 'order': 99999 + next_seq(),
                                            'title': 'Last contact detect',
                                            'schema': {'type': 'string'}})
local_event_Status = LocalEvent({'group': 'Status', 'order': 99999 + next_seq(),
                                 'schema': {'type': 'object', 'properties': {
                                     'level': {'type': 'integer', 'order': 1},
                                     'message': {'type': 'string', 'order': 2}
                                 }}})
local_event_Version = LocalEvent({'group': 'Info', 'order': next_seq(),
                                  'schema': {'type': 'string'}})
local_event_LastError = LocalEvent({'group': 'Status', 'order': next_seq(),
                                    'title': 'Last error', 'schema': {'type': 'string'}})
local_event_LogLevel = LocalEvent({'group': 'Debug', 'order': 100000 + next_seq(),
                                   'desc': 'Use this to ramp up the logging (with indentation)',
                                   'schema': {'type': 'integer'}})

_lastReceive = None
_commsConnected = False
_configuredDest = None
_restartScheduled = False
tcp = None


def currentLogLevel():
    value = local_event_LogLevel.getArg()
    return int(value) if value else 0


def writeLog(method, level, msg):
    if currentLogLevel() >= level:
        method(('  ' * level) + str(msg))


def info(level, msg):
    writeLog(console.info, level, msg)


def error(level, msg):
    writeLog(console.error, level, msg)


def warn(level, msg):
    writeLog(console.warn, level, msg)


def log(level, msg):
    writeLog(console.log, level, msg)


def boundedResponse(data):
    text = str(data)
    if len(text) <= MAX_RESPONSE_LENGTH:
        return text
    return '%s... [%s characters truncated]' % (
        text[:MAX_RESPONSE_LENGTH], len(text) - MAX_RESPONSE_LENGTH)


def remote_event_IPAddress(arg):
    if same_value(arg, local_event_IPAddress.getArg()):
        return
    local_event_IPAddress.emit(arg)
    local_event_IPAddress.persistNow()
    setupTCP()


def remote_event_Port(arg):
    if same_value(arg, local_event_Port.getArg()):
        return
    local_event_Port.emit(arg)
    local_event_Port.persistNow()
    setupTCP()


def tcpConnected():
    global _commsConnected, _lastReceive
    tcp.clearQueue()
    _commsConnected = True
    _lastReceive = None
    local_event_Status.emit({'level': 1, 'message': 'Connected, awaiting response'})
    timer_Status.start()
    info(0, 'tcp_connected')


def stopComms(message='Disconnected'):
    global _commsConnected
    _commsConnected = False
    timer_Status.stop()
    if tcp is not None:
        tcp.clearQueue()
    local_event_Status.emit({'level': 2, 'message': message})


def tcpDisconnected():
    stopComms()
    warn(0, 'tcp_disconnected')


def tcpTimeout():
    stopComms('Timed out')
    if tcp is not None:
        tcp.drop()
    warn(0, 'tcp_timeout')


def tcpSent(data):
    log(2, 'tcp_sent [%s]' % boundedResponse(data))


def tcpReceived(data):
    global _lastReceive
    _lastReceive = system_clock()
    data = boundedResponse(data)
    log(2, 'tcp_received [%s]' % data)
    parseResponse(data)
    local_event_LastContactDetect.emit(str(date_now()))
    local_event_Status.emit({'level': 0, 'message': 'OK'})


timer_Status = Timer(lambda: StatusCheck.call(), 20, 10, stopped=True)


def main():
    info(0, 'Started!')


def createTCPConnection(dest):
    global tcp, _configuredDest
    local_event_Status.emit({'level': 1, 'message': 'Connecting'})
    tcp = TCP(
        dest=dest,
        connected=tcpConnected,
        disconnected=tcpDisconnected,
        sent=tcpSent,
        received=tcpReceived,
        timeout=tcpTimeout,
        sendDelimiters='\r',
        receiveDelimiters='\r\n')
    _configuredDest = dest
    # Resources created after normal node initialisation are not automatically
    # started; start() is idempotent during initial load.
    tcp.start()


def retireTCPAndRestart():
    global tcp, _configuredDest, _restartScheduled
    stopComms('Reconfiguring')
    if tcp is not None:
        tcp.close()
        tcp = None
    _configuredDest = None
    if not _restartScheduled:
        _restartScheduled = True
        call(lambda: _node.restart(), delay=0.25)


def effectiveDestination():
    if param_ipAddress and param_Port:
        ipAddress = str(param_ipAddress).strip()
        portValue = param_Port
    elif local_event_IPAddress.getArg() and local_event_Port.getArg():
        ipAddress = str(local_event_IPAddress.getArg()).strip()
        portValue = local_event_Port.getArg()
    else:
        return None

    try:
        port = int(portValue)
    except (TypeError, ValueError):
        return None
    if not ipAddress or port < 1 or port > 65535:
        return None
    return '%s:%s' % (ipAddress, port)


@after_main
def setupTCP():
    newDest = effectiveDestination()
    if newDest is None:
        if tcp is not None:
            retireTCPAndRestart()
        else:
            local_event_Status.emit({'level': 2, 'message': 'Not configured'})
            warn(0, 'IP params or IP binding not specified!')
        return

    if tcp is not None and newDest == _configuredDest:
        return
    if tcp is not None:
        retireTCPAndRestart()
        return
    if _restartScheduled:
        warn(0, 'IP params or IP binding not specified!')
        return

    info(0, 'Setting dest [%s]' % newDest)
    createTCPConnection(newDest)


def validActionArgs(arg, includeSound=False):
    if arg is None or not hasattr(arg, 'get'):
        warn(0, 'Action rejected: expected an object argument')
        return None
    rawChannel = arg.get('Channel')
    channel = '*' if rawChannel is None or rawChannel == '' else str(rawChannel)
    if channel not in CHANNELS:
        warn(0, 'Action rejected: invalid channel')
        return None
    if not includeSound:
        return channel
    sound = arg.get('Sound')
    validSound = (
        isinstance(sound, int) and not isinstance(sound, bool) and
        (1 <= sound <= 511 or 1001 <= sound <= 1511))
    if sound is not None and not validSound:
        warn(0, 'Action rejected: sound must be 1-511 or 1001-1511')
        return None
    return channel, sound


def sendIfConnected(command):
    if not _commsConnected:
        warn(0, 'Command ignored while 8 TraXX is disconnected')
        return False

    def responseHandler(resp):
        resp = boundedResponse(resp)
        if resp == 'R' or isErrorCode(resp):
            return
        local_event_LastError.emit('Unexpected command response (%s)' % resp)

    tcp.request(command, responseHandler)
    return True


@local_action({'group': 'Operations', 'order': next_seq(), 'schema': {
    'type': 'object', 'properties': {
        'Channel': {'type': 'string', 'enum': CHANNELS, 'desc': 'channel number', 'order': 1},
        'Sound': {'type': 'integer', 'desc': 'sound number', 'order': 2}}}})
def PlayFileToChannel(arg):
    values = validActionArgs(arg, True)
    if values is None:
        return
    channel, sound = values
    sendIfConnected(('%s%sPL' % (sound, channel)) if sound is not None else '%sPL' % channel)


@local_action({'group': 'Operations', 'order': next_seq(), 'schema': {
    'type': 'object', 'properties': {
        'Channel': {'type': 'string', 'enum': CHANNELS, 'desc': 'channel number', 'order': 1},
        'Sound': {'type': 'integer', 'desc': 'sound number', 'order': 2}}}})
def LoopPlayToChannel(arg):
    values = validActionArgs(arg, True)
    if values is None:
        return
    channel, sound = values
    sendIfConnected(('%s%sLP' % (sound, channel)) if sound is not None else '%sLP' % channel)


@local_action({'group': 'Operations', 'order': next_seq(), 'schema': {
    'type': 'object', 'properties': {
        'Channel': {'type': 'string', 'enum': CHANNELS, 'desc': 'channel number', 'order': 1},
        'Sound': {'type': 'integer', 'desc': 'sound number', 'order': 2}}}})
def AssignSoundToChannel(arg):
    values = validActionArgs(arg, True)
    if values is None:
        return
    channel, sound = values
    if sound is None:
        warn(0, 'Action rejected: Assign Sound requires a sound number')
        return
    sendIfConnected('%s%sSE' % (sound, channel))


@local_action({'group': 'Operations', 'order': next_seq(), 'schema': {
    'type': 'object', 'properties': {
        'Channel': {'type': 'string', 'enum': CHANNELS, 'desc': 'channel number', 'order': 1}}}})
def ResetChannel(arg):
    channel = validActionArgs(arg)
    if channel is not None:
        sendIfConnected('%sRJ' % channel)


@local_action({'group': 'Operations', 'order': next_seq(), 'schema': {
    'type': 'object', 'properties': {
        'Channel': {'type': 'string', 'enum': CHANNELS, 'desc': 'channel number', 'order': 1},
        'Mute': {'type': 'string', 'enum': ['Mute', 'Unmute'], 'order': 2}}}})
def MuteChannel(arg):
    channel = validActionArgs(arg)
    if channel is None:
        return
    mute = arg.get('Mute')
    if mute not in ['Mute', 'Unmute']:
        warn(0, 'Action rejected: invalid mute state')
        return
    sendIfConnected('%s%sAD' % ('0' if mute == 'Mute' else '1', channel))


@local_action({'group': 'Operations', 'order': next_seq(), 'schema': {
    'type': 'object', 'properties': {
        'State': {'type': 'string', 'enum': ['Enable', 'Disable'], 'order': 1}}}})
def KeylockControl(arg):
    state = arg.get('State') if arg is not None and hasattr(arg, 'get') else None
    if state not in ['Enable', 'Disable']:
        warn(0, 'Action rejected: invalid keylock state')
        return
    sendIfConnected('1KL' if state == 'Enable' else '0KL')


@local_action({'group': 'Info', 'order': next_seq()})
def PollVersion():
    if not _commsConnected:
        warn(0, 'Version poll ignored while 8 TraXX is disconnected')
        return

    def responseHandler(resp):
        resp = boundedResponse(resp)
        log(0, resp)
        if not resp.startswith('Alcorn McBride 8TraXX'):
            warn(0, 'Bad response to version request')
            return
        versionIndex = resp.lower().rfind('v')
        if versionIndex < 0:
            warn(0, 'Version response did not contain a version')
            return
        local_event_Version.emit(resp[versionIndex:])

    tcp.request('?V', responseHandler)


def isErrorCode(data):
    return len(data) == 3 and data[0] == 'E' and data[1:].isdigit()


def parseResponse(data):
    if isErrorCode(data):
        message = ERROR_CODES.get(data, 'Unknown error code')
        warn(0, 'error response [%s] %s' % (data, message))
        local_event_LastError.emit('%s (%s)' % (message, data))


@local_action({'title': 'Poll', 'group': 'Status'})
def StatusCheck():
    if not _commsConnected:
        local_event_Status.emit({'level': 2, 'message': 'Disconnected'})
        return
    PollVersion.call()
    if _lastReceive is None:
        local_event_Status.emit({'level': 2, 'message': 'Never seen'})
        return

    diff = (system_clock() - _lastReceive) / 1000.0
    if diff > STATUS_CHECK_INTERVAL:
        previous = local_event_LastContactDetect.getArg()
        message = 'Never seen' if not previous else 'Missing %s' % formatPeriod(date_parse(previous))
        local_event_Status.emit({'level': 2, 'message': message})
    else:
        local_event_LastContactDetect.emit(str(date_now()))
        local_event_Status.emit({'level': 0, 'message': 'OK'})


def formatPeriod(date, asInstant=False):
    if date is None:
        return 'for unknown period'
    minutes = (date_now().getMillis() - date.getMillis()) // 1000 // 60
    if minutes < 0:
        return 'never ever'
    if minutes == 0:
        return '<1 min ago' if asInstant else 'for <1 min'
    if minutes < 60:
        return ('<%s mins ago' if asInstant else 'for <%s mins') % minutes
    if minutes < 60 * 24:
        return ('at %s' if asInstant else 'since %s') % date.toString('h:mm:ss a')
    return ('at %s' if asInstant else 'since %s') % date.toString('E d-MMM h:mm:ss a')


@local_action({'group': 'Debug', 'title': '+', 'order': 100000 + next_seq()})
def increaseLogLevel():
    local_event_LogLevel.emit(currentLogLevel() + 1)


@local_action({'group': 'Debug', 'title': '-', 'order': 100000 + next_seq()})
def decreaseLogLevel():
    local_event_LogLevel.emit(currentLogLevel() - 1)
