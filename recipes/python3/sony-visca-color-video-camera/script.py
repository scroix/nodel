# Experimental Python 3 port pinned from museumsvictoria/nodel-recipes
# commit 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
'''VISCA-over-IP camera control for pan, tilt, zoom, focus, and presets.'''


import ipaddress


DEFAULT_PORT = 52381
DEFAULT_VISCA_ADDRESS = 1
DEFAULT_STATUS_URL = 'http://$IP_ADDR/login'
DEFAULT_STATUS_TOKEN = 'birddog_p200.png'
MAX_HTTP_URL_LENGTH = 2048
MAX_STATUS_TOKEN_LENGTH = 256
MAX_LOGGED_PACKET_BYTES = 256
MAX_PENDING_COMMANDS = 64
MAX_RETRANSMISSIONS = 2
SEND_CONFIRMATION_TIMEOUT_SECONDS = 5.0
RESPONSE_TIMEOUT_SECONDS = 1.0


param_disabled = Parameter({'desc': 'Disables this node?', 'schema': {'type': 'boolean'}})
param_ipAddress = Parameter({'schema': {'type': 'string'}})
param_port = Parameter({'schema': {
    'type': 'integer', 'hint': '(default is %s)' % DEFAULT_PORT}})
param_viscaAddress = Parameter({'schema': {
    'type': 'integer', 'hint': '(default is %s)' % DEFAULT_VISCA_ADDRESS}})
param_StatusHTTP = Parameter({
    'title': 'Status via HTTP',
    'desc': ('Sometimes the VISCA protocol does not respond, so HTTP can be used '
             'as an additional status check.'),
    'schema': {'type': 'object', 'properties': {
        'url': {'type': 'string', 'hint': '(def. "%s")' % DEFAULT_STATUS_URL,
                'order': next_seq()},
        'token': {'type': 'string', 'hint': '(def. "%s")' % DEFAULT_STATUS_TOKEN,
                  'order': next_seq()},
    }},
})


local_event_IPAddress = LocalEvent({'schema': {'type': 'string'}})
local_event_PanSpeed = LocalEvent({
    'group': 'PTZ Drive',
    'title': 'Pan Speed',
    'schema': {'type': 'integer', 'format': 'range', 'min': 1, 'max': 24},
    'order': next_seq(),
})
local_event_TiltSpeed = LocalEvent({
    'group': 'PTZ Drive',
    'title': 'Tilt Speed',
    'schema': {'type': 'integer', 'format': 'range', 'min': 1, 'max': 24},
    'order': next_seq(),
})
le_Focus_Mode = create_local_event(
    'Focus Mode',
    metadata={
        'title': 'Focus Mode',
        'group': 'PTZ Focus',
        'order': next_seq(),
        'schema': {'type': 'string'},
    },
)
local_event_Status = LocalEvent({
    'title': 'Status',
    'group': 'Status',
    'order': 9990,
    'schema': {
        'title': 'Status',
        'type': 'object',
        'properties': {
            'level': {'title': 'Level', 'order': 1, 'type': 'integer'},
            'message': {'title': 'Message', 'order': 2, 'type': 'string'},
        },
    },
})
local_event_LastContactDetect = LocalEvent({
    'group': 'Status', 'title': 'Last contact detect', 'schema': {'type': 'string'}})
local_event_LogLevel = LocalEvent({
    'group': 'Debug',
    'order': 10000 + next_seq(),
    'desc': 'Use this to ramp up the logging (with indentation)',
    'schema': {'type': 'integer'},
})


_port = DEFAULT_PORT
_viscaAddress = DEFAULT_VISCA_ADDRESS
_sequenceNumber = 0
_udpReady = False
_destinationConfigured = False
_configuredIPAddress = None
_configuredTarget = None
_expectedSourceIPAddress = None
_lastReceive = 0
_udpReceiveCount = 0
_commandQueue = []
_pendingCommand = None
_pendingGeneration = 0
_resetRequestSequence = -1


def boundedInteger(value, minimum, maximum, label):
    text = str(value)
    if len(text) > 16 or not text.isdecimal():
        console.warn('Ignoring invalid %s: %s' % (label, text[:64]))
        return None
    number = int(text)
    if number < minimum or number > maximum:
        console.warn('Ignoring out-of-range %s: %s' % (label, text[:64]))
        return None
    return number


def warn(level, msg):
    if (local_event_LogLevel.getArg() or 0) >= level:
        console.warn(('  ' * level) + msg)


def log(level, msg):
    if (local_event_LogLevel.getArg() or 0) >= level:
        console.log(('  ' * level) + msg)


def packetHex(data):
    payload = data.encode('latin-1') if isinstance(data, str) else bytes(data)
    shown = payload[:MAX_LOGGED_PACKET_BYTES].hex(':')
    if len(payload) > MAX_LOGGED_PACKET_BYTES:
        shown += ':... [%s bytes truncated]' % (len(payload) - MAX_LOGGED_PACKET_BYTES)
    return shown


def udpReady():
    global _udpReady
    _udpReady = True
    console.info('udp_ready')
    if _destinationConfigured:
        resetSequenceNo()


def udp_received(src, data):
    global _udpReceiveCount
    _udpReceiveCount += 1
    log(2, 'udp_recv %s (from %s)' % (packetHex(data), src))
    if not _destinationConfigured:
        return
    packet = data.encode('latin-1')
    sourceHost = str(src).rsplit(':', 1)[0]
    if (_expectedSourceIPAddress is not None and sourceHost != _expectedSourceIPAddress):
        return
    if len(packet) < 9:
        return
    payloadLength = int.from_bytes(packet[2:4], 'big')
    if len(packet) != payloadLength + 8 or packet[:2] not in (b'\x01\x11', b'\x02\x01'):
        return
    sequenceNumber = int.from_bytes(packet[4:8], 'big')
    if _pendingCommand is None or sequenceNumber != _pendingCommand['sequence']:
        log(1, 'Ignoring VISCA reply with no matching pending command')
        return
    isControlReply = packet[:2] == b'\x02\x01'
    if isControlReply != _pendingCommand['reset']:
        log(1, 'Ignoring VISCA reply with a mismatched envelope type')
        return

    payload = packet[8:]
    if isControlReply:
        if payload == b'\x01' or payload == b'\x0f\x01':
            finishPendingCommand(True, 'OK')
        elif payload.startswith(b'\x0f'):
            finishPendingCommand(False, 'VISCA control error')
        return

    if len(payload) < 3 or payload[0] != 0x90:
        return
    replyType = payload[1] & 0xf0
    if replyType == 0x40:
        markContact(1, 'Awaiting completion')
    elif replyType == 0x50:
        finishPendingCommand(True, 'OK')
    elif replyType == 0x60:
        finishPendingCommand(False, 'VISCA device error')


def udp_sent(data):
    packet = str(data)
    call_safe(lambda: packetWasSent(packet))


def createManagedUDP():
    return UDP(sent=udp_sent, ready=udpReady, received=udp_received)


udp = createManagedUDP()


def nextSequenceNumber():
    global _sequenceNumber
    _sequenceNumber = (_sequenceNumber + 1) & 0xffffffff
    return _sequenceNumber


def markContact(level, message):
    global _lastReceive
    _lastReceive = system_clock()
    local_event_LastContactDetect.emit(str(date_now()))
    local_event_Status.emit({'level': level, 'message': message})


def clearCommandState():
    global _pendingCommand, _pendingGeneration
    _commandQueue[:] = []
    _pendingCommand = None
    _pendingGeneration += 1


def enqueuePacket(packet, sequenceNumber, reset=False):
    if len(_commandQueue) + (1 if _pendingCommand is not None else 0) >= MAX_PENDING_COMMANDS:
        console.warn('VISCA command rejected: command queue is full')
        return False
    _commandQueue.append({
        'packet': packet,
        'sequence': sequenceNumber,
        'reset': reset,
        'retries': 0,
        'target': _configuredTarget,
        'awaitingSend': False,
    })
    sendNextPacket()
    return True


def sendNextPacket():
    global _pendingCommand
    if (_pendingCommand is not None or not _commandQueue
            or not _udpReady or not _destinationConfigured):
        return
    _pendingCommand = _commandQueue.pop(0)
    submitPendingPacket()


def submitPendingPacket():
    global _pendingGeneration
    if _pendingCommand is None:
        return
    _pendingCommand['awaitingSend'] = True
    udp.sendTo(_pendingCommand['target'], _pendingCommand['packet'])
    _pendingGeneration += 1
    generation = _pendingGeneration
    call_safe(
        lambda: pendingSendTimedOut(generation),
        delay=SEND_CONFIRMATION_TIMEOUT_SECONDS,
    )


def pendingSendTimedOut(generation):
    global udp, _udpReady
    if (generation != _pendingGeneration or _pendingCommand is None
            or not _pendingCommand['awaitingSend']):
        return
    clearCommandState()
    _udpReady = False
    local_event_Status.emit({'level': 2, 'message': 'VISCA send failed'})
    _toolkit.releaseUDPs()
    udp = createManagedUDP()
    if _destinationConfigured:
        udp.setDest(_configuredTarget)
    udp.start()


def packetWasSent(packet):
    log(1, 'udp_sent %s' % packetHex(packet))
    if (_pendingCommand is None or not _pendingCommand['awaitingSend']
            or packet != _pendingCommand['packet']):
        return
    _pendingCommand['awaitingSend'] = False
    schedulePendingTimeout()


def schedulePendingTimeout():
    global _pendingGeneration
    _pendingGeneration += 1
    generation = _pendingGeneration
    call_safe(lambda: pendingCommandTimedOut(generation), delay=RESPONSE_TIMEOUT_SECONDS)


def pendingCommandTimedOut(generation):
    global _pendingCommand
    if generation != _pendingGeneration or _pendingCommand is None:
        return
    if not _destinationConfigured:
        clearCommandState()
        return
    if _pendingCommand['retries'] < MAX_RETRANSMISSIONS:
        _pendingCommand['retries'] += 1
        submitPendingPacket()
        return
    _pendingCommand = None
    local_event_Status.emit({'level': 2, 'message': 'VISCA response timeout'})
    sendNextPacket()


def finishPendingCommand(success, message):
    global _pendingCommand, _pendingGeneration
    _pendingCommand = None
    _pendingGeneration += 1
    markContact(0 if success else 1, message)
    sendNextPacket()


def get_command_string(cmd_type, visca_addr, seq_number, data=None):
    address = bytes((0x80 + visca_addr,))
    commands = {
        'up': b'\x01\x06\x01' + bytes((currentPanSpeed(), currentTiltSpeed())) + b'\x03\x01',
        'down': b'\x01\x06\x01' + bytes((currentPanSpeed(), currentTiltSpeed())) + b'\x03\x02',
        'left': b'\x01\x06\x01' + bytes((currentPanSpeed(), currentTiltSpeed())) + b'\x01\x03',
        'right': b'\x01\x06\x01' + bytes((currentPanSpeed(), currentTiltSpeed())) + b'\x02\x03',
        'home': b'\x01\x06\x04',
        'stop': b'\x01\x06\x01\x05\x05\x03\x03',
        'zoom_stop': b'\x01\x04\x07\x00',
        'zoom_tele': b'\x01\x04\x07\x02',
        'zoom_wide': b'\x01\x04\x07\x03',
        'focus_auto': b'\x01\x04\x38\x02',
        'focus_manual': b'\x01\x04\x38\x03',
        'focus_stop': b'\x01\x04\x08\x00',
        'focus_far': b'\x01\x04\x08\x02',
        'focus_near': b'\x01\x04\x08\x03',
    }

    if cmd_type == 'reset_seq':
        payloadType = b'\x02\x00'
        payload = b'\x01'
    elif cmd_type in ('preset_reset', 'preset_set', 'preset_recall'):
        operation = {'preset_reset': 0, 'preset_set': 1, 'preset_recall': 2}[cmd_type]
        preset = boundedInteger(data, 0, 255, 'preset')
        if preset is None:
            return None
        payloadType = b'\x01\x00'
        payload = address + b'\x01\x04\x3f' + bytes((operation, preset)) + b'\xff'
    else:
        command = commands.get(cmd_type)
        if command is None:
            raise ValueError('Unsupported command type')
        payloadType = b'\x01\x00'
        payload = address + command + b'\xff'

    packet = (
        payloadType
        + len(payload).to_bytes(2, 'big')
        + (seq_number & 0xffffffff).to_bytes(4, 'big')
        + payload
    )
    return packet.decode('latin-1')


def currentPanSpeed():
    return boundedInteger(local_event_PanSpeed.getArg(), 1, 24, 'pan speed') or 5


def currentTiltSpeed():
    return boundedInteger(local_event_TiltSpeed.getArg(), 1, 24, 'tilt speed') or 5


def sendCommand(command, data=None):
    if not _udpReady or not _destinationConfigured:
        console.warn('VISCA command rejected: UDP destination is not ready')
        return False
    sequenceNumber = nextSequenceNumber()
    packet = get_command_string(command, _viscaAddress, sequenceNumber, data)
    if packet is None:
        return False
    return enqueuePacket(packet, sequenceNumber)


def sendPriorityCommand(command):
    if not _udpReady or not _destinationConfigured:
        console.warn('VISCA command rejected: UDP destination is not ready')
        return False
    sequenceNumber = nextSequenceNumber()
    packet = get_command_string(command, _viscaAddress, sequenceNumber)
    clearCommandState()
    return enqueuePacket(packet, sequenceNumber)


def resetSequenceNo():
    global _sequenceNumber, _resetRequestSequence
    if not _udpReady or not _destinationConfigured:
        return False
    clearCommandState()
    _sequenceNumber = 0
    _resetRequestSequence = (_resetRequestSequence + 1) & 0xffffffff
    packet = get_command_string('reset_seq', _viscaAddress, _resetRequestSequence)
    return enqueuePacket(packet, _resetRequestSequence, reset=True)


def stopMonitoring(message):
    timer_poller.stop()
    timer_statusCheck.stop()
    local_event_Status.emit({'level': 2, 'message': message})


def configureDestination(ipAddress):
    global _configuredIPAddress, _configuredTarget, _destinationConfigured
    global _expectedSourceIPAddress, _lastReceive
    if is_blank(ipAddress):
        clearCommandState()
        _configuredIPAddress = None
        _configuredTarget = None
        _expectedSourceIPAddress = None
        _destinationConfigured = False
        udp.setDest('')
        stopMonitoring('Not configured')
        return False

    host = str(ipAddress).strip()
    try:
        parsedAddress = ipaddress.ip_address(host)
        if parsedAddress.version != 4:
            raise ValueError('VISCA over IP requires an IPv4 destination')
        host = str(parsedAddress)
    except ValueError:
        clearCommandState()
        console.warn('VISCA destination is invalid')
        _configuredIPAddress = None
        _configuredTarget = None
        _expectedSourceIPAddress = None
        _destinationConfigured = False
        udp.setDest('')
        stopMonitoring('Invalid address')
        return False

    destinationChanged = host != _configuredIPAddress
    _configuredIPAddress = host
    _expectedSourceIPAddress = host
    _destinationConfigured = True
    _configuredTarget = '%s:%s' % (host, _port)
    if destinationChanged:
        clearCommandState()
        _lastReceive = 0
        local_event_LastContactDetect.emit(None)
    local_event_IPAddress.emit(host)
    udp.setDest(_configuredTarget)
    local_event_Status.emit({'level': 1, 'message': 'Awaiting response'})
    timer_poller.start()
    timer_statusCheck.start()
    if _udpReady:
        resetSequenceNo()
    return True


def remote_event_IPAddress(arg):
    if param_disabled is True or not is_blank(param_ipAddress):
        return
    old = local_event_IPAddress.getArg()
    if arg != old:
        console.info('IP address updated! was %s, new %s' % (old, arg))
        local_event_IPAddress.emit(None if is_blank(arg) else str(arg).strip())
        local_event_IPAddress.persistNow()
        configureDestination(arg)


@before_main
def initPanAndTiltSpeeds():
    if boundedInteger(local_event_PanSpeed.getArg(), 1, 24, 'pan speed') is None:
        local_event_PanSpeed.emit(5)
    if boundedInteger(local_event_TiltSpeed.getArg(), 1, 24, 'tilt speed') is None:
        local_event_TiltSpeed.emit(5)


@local_action({'group': 'PTZ Drive', 'title': 'Pan Speed', 'schema': {
    'type': 'integer', 'hint': '(default: 5, Min: 1, Max: 24)', 'format': 'range',
    'min': 1, 'max': 24}, 'order': next_seq()})
def PanSpeed(arg):
    speed = boundedInteger(arg, 1, 24, 'pan speed')
    if speed is not None:
        local_event_PanSpeed.emit(speed)


@local_action({'group': 'PTZ Drive', 'title': 'Tilt Speed', 'schema': {
    'type': 'integer', 'hint': '(default: 5, Min: 1, Max: 24)', 'format': 'range',
    'min': 1, 'max': 24}, 'order': next_seq()})
def TiltSpeed(arg):
    speed = boundedInteger(arg, 1, 24, 'tilt speed')
    if speed is not None:
        local_event_TiltSpeed.emit(speed)


@local_action({'group': 'PTZ Drive', 'title': 'Home', 'order': next_seq()})
def ptz_home(arg):
    sendCommand('home')


@local_action({'group': 'PTZ Drive', 'title': 'Up', 'order': next_seq()})
def ptz_up(arg):
    sendCommand('up')


@local_action({'group': 'PTZ Drive', 'title': 'Down', 'order': next_seq()})
def ptz_down(arg):
    sendCommand('down')


@local_action({'group': 'PTZ Drive', 'title': 'Left', 'order': next_seq()})
def ptz_left(arg):
    sendCommand('left')


@local_action({'group': 'PTZ Drive', 'title': 'Right', 'order': next_seq()})
def ptz_right(arg):
    sendCommand('right')


@local_action({'group': 'PTZ Drive', 'title': 'Stop', 'order': next_seq()})
def ptz_stop(arg):
    sendPriorityCommand('stop')


@local_action({'group': 'PTZ Preset', 'title': 'Preset Reset', 'order': next_seq(),
               'schema': {'type': 'integer'}})
def ptz_preset_reset(arg):
    sendCommand('preset_reset', arg)


@local_action({'group': 'PTZ Preset', 'title': 'Preset Set', 'order': next_seq(),
               'schema': {'type': 'integer'}})
def ptz_preset_set(arg):
    sendCommand('preset_set', arg)


@local_action({'group': 'PTZ Preset', 'title': 'Preset Recall', 'order': next_seq(),
               'schema': {'type': 'integer'}})
def ptz_preset_recall(arg):
    sendCommand('preset_recall', arg)


@local_action({'group': 'PTZ Zoom', 'title': 'Zoom Stop', 'order': next_seq()})
def ptz_zoom_stop(arg):
    sendPriorityCommand('zoom_stop')


@local_action({'group': 'PTZ Zoom', 'title': 'Zoom Tele', 'order': next_seq()})
def ptz_zoom_tele(arg):
    sendCommand('zoom_tele')


@local_action({'group': 'PTZ Zoom', 'title': 'Zoom Wide', 'order': next_seq()})
def ptz_zoom_wide(arg):
    sendCommand('zoom_wide')


@local_action({'group': 'PTZ Focus', 'title': 'Focus Mode - Auto', 'order': next_seq()})
def ptz_focus_mode_auto(arg):
    if sendCommand('focus_auto'):
        le_Focus_Mode.emit('AUTO')


@local_action({'group': 'PTZ Focus', 'title': 'Focus Mode - Manual', 'order': next_seq()})
def ptz_focus_mode_manual(arg):
    if sendCommand('focus_manual'):
        le_Focus_Mode.emit('MANUAL')


@local_action({'group': 'PTZ Focus', 'title': 'Focus - Stop', 'order': next_seq()})
def ptz_focus_stop(arg):
    sendPriorityCommand('focus_stop')


@local_action({'group': 'PTZ Focus', 'title': 'Focus - Far', 'order': next_seq()})
def ptz_focus_far(arg):
    sendCommand('focus_far')


@local_action({'group': 'PTZ Focus', 'title': 'Focus - Near', 'order': next_seq()})
def ptz_focus_near(arg):
    sendCommand('focus_near')


@local_action({'group': 'Status', 'order': next_seq()})
def httpPoll():
    global _lastReceive
    if not _destinationConfigured:
        return
    config = param_StatusHTTP if hasattr(param_StatusHTTP, 'get') else {}
    token = str(config.get('token') or DEFAULT_STATUS_TOKEN)
    url = str(config.get('url') or DEFAULT_STATUS_URL)
    if (not token or len(token) > MAX_STATUS_TOKEN_LENGTH
            or not url or len(url) > MAX_HTTP_URL_LENGTH):
        console.warn('HTTP status configuration is invalid')
        return
    url = url.replace('$IP_ADDR', _configuredIPAddress)
    try:
        response = str(get_url(url, connectTimeout=5, readTimeout=5))
        if token not in response:
            console.warn('Unexpected HTTP status response: configured token was not found')
            return
        _lastReceive = system_clock()
        local_event_LastContactDetect.emit(str(date_now()))
        local_event_Status.emit({'level': 0, 'message': 'OK'})
    except Exception as error:
        log(1, 'problem polling %s: %s' % (url, str(error)[:256]))


def statusCheck():
    if not _destinationConfigured:
        local_event_Status.emit({'level': 2, 'message': 'Not configured'})
        return
    if _lastReceive <= 0 or (system_clock() - _lastReceive) / 1000.0 > 90:
        previousContactValue = local_event_LastContactDetect.getArg()
        message = ('Never seen' if not previousContactValue
                   else 'Missing %s' % formatPeriod(date_parse(previousContactValue)))
        local_event_Status.emit({'level': 2, 'message': message})
        return
    local_event_LastContactDetect.emit(str(date_now()))
    local_event_Status.emit({'level': 0, 'message': 'OK'})


def formatPeriod(dateObj):
    if dateObj is None:
        return 'for unknown period'
    diff = (date_now().getMillis() - dateObj.getMillis()) // 1000 // 60
    if diff == 0:
        return 'for <1 min'
    if diff < 60:
        return 'for <%s mins' % diff
    if diff < 60 * 24:
        return 'since %s' % dateObj.toString('h:mm:ss a')
    return 'since %s' % dateObj.toString('E d-MMM h:mm:ss a')


timer_poller = Timer(lambda: httpPoll.call(), 30, 5, stopped=True)
timer_statusCheck = Timer(statusCheck, 75, 75, stopped=True)


def main():
    global _port, _viscaAddress
    if not is_blank(param_port):
        configuredPort = boundedInteger(param_port, 1, 65535, 'UDP port')
        if configuredPort is not None:
            _port = configuredPort
    if not is_blank(param_viscaAddress):
        configuredAddress = boundedInteger(param_viscaAddress, 0, 7, 'VISCA address')
        if configuredAddress is not None:
            _viscaAddress = configuredAddress

    if param_disabled is True:
        console.warn('Disabled! nothing to do')
        stopMonitoring('Disabled')
        udp.close()
        return

    _toolkit.getHttpClient().setIgnoreRedirects(True)
    ipAddress = local_event_IPAddress.getArg() if is_blank(param_ipAddress) else param_ipAddress
    if not configureDestination(ipAddress):
        console.warn('No IP address configured or updated; will wait...')


@at_cleanup
def closeResources():
    clearCommandState()
    timer_poller.stop()
    timer_statusCheck.stop()
    udp.close()
