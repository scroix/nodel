# Experimental Python 3 port of museumsvictoria/nodel-recipes
# "Extron MVC 121 Plus mixer" at 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
"""Extron MVC 121 Plus audio mixer.

Manual: http://media.extron.com/download/files/userman/68-1937-01_B_MVC_121_Plus_UG_.pdf
"""

param_disabled = Parameter({'title': 'Disabled', 'group': 'Comms', 'schema': {'type': 'boolean'}})
param_address = Parameter({'title': 'Address', 'schema': {'type': 'string', 'hint': 'HOST:TCPPORT'}})

local_event_Status = LocalEvent({'group': 'Status', 'order': 9999 + next_seq(), 'schema': {
    'type': 'object',
    'title': 'Status',
    'properties': {
        'level': {'type': 'integer', 'order': next_seq(), 'title': 'Level'},
        'message': {'type': 'string', 'order': next_seq(), 'title': 'Message'},
    },
}})

pollers = []
tcp = None
comms_connected = False
shutting_down = False

MAX_DIAGNOSTIC_LENGTH = 512
MAX_RECEIVED_EVENT_LENGTH = 4096


def addPoller(handler, interval):
    poller = Timer(handler, interval, stopped=True)
    pollers.append(poller)


def main():
    if param_disabled is True:
        console.warn('Comms disabled')
        local_event_Status.emit({'level': 1, 'message': 'Disabled'})
        tcp.close()
        return

    if is_blank(param_address):
        console.warn('Comms disabled; Address is not configured')
        local_event_Status.emit({'level': 1, 'message': 'Address not configured'})
        tcp.close()
        return

    address = validTCPAddress(param_address)
    if address is None:
        console.warn('Comms disabled; Address must be HOST:TCPPORT')
        local_event_Status.emit({'level': 1, 'message': 'Invalid address'})
        tcp.close()
        return

    tcp.setDest(address)


@at_cleanup
def close_comms():
    global comms_connected, shutting_down

    shutting_down = True
    comms_connected = False
    stopPollers()
    if tcp is not None:
        tcp.close()


def request(command, handler):
    if tcp is None or not comms_connected:
        console.warn('Ignoring request while comms are disabled.')
        return
    tcp.request(command, handler)


def startPollers():
    for poller in pollers:
        poller.start()


def stopPollers():
    for poller in pollers:
        poller.stop()


def boundedInteger(value, minimum, maximum, label):
    text = str(value)
    if len(text) > 32:
        console.warn('Ignoring invalid %s value: %s' % (label, diagnosticValue(value)))
        return None
    digits = text[1:] if text.startswith('-') else text
    if not digits or any(char < '0' or char > '9' for char in digits):
        console.warn('Ignoring invalid %s value: %s' % (label, diagnosticValue(value)))
        return None

    number = int(text)
    if number < minimum or number > maximum:
        console.warn('Ignoring out-of-range %s value: %s' % (label, diagnosticValue(value)))
        return None
    return number


def boundedResponse(resp, prefix, minimum, maximum, label):
    text = str(resp)
    payload = text[len(prefix):] if text.startswith(prefix) else text
    return boundedInteger(payload, minimum, maximum, label)


def addressedBoundedResponse(resp, prefix, minimum, maximum, label):
    text = str(resp)
    if '*' in text:
        if not text.startswith(prefix):
            console.warn('Ignoring invalid %s value: %s' % (label, diagnosticValue(resp)))
            return None
        text = text[len(prefix):]
    return boundedInteger(text, minimum, maximum, label)


def gainResponse(resp, input_number, label):
    text = str(resp)
    prefix = 'In%s Aud' % input_number
    if text.startswith(prefix):
        text = text[len(prefix):]
    elif text.startswith('Aud'):
        text = text[3:]
    return boundedInteger(text, -24, 12, label)


def boundedNumber(value, minimum, maximum, label):
    if len(str(value)) > 32:
        console.warn('Ignoring invalid %s value: %s' % (label, diagnosticValue(value)))
        return None
    try:
        number = float(str(value))
    except ValueError:
        console.warn('Ignoring invalid %s value: %s' % (label, diagnosticValue(value)))
        return None

    if number != number or number < minimum or number > maximum:
        console.warn('Ignoring out-of-range %s value: %s' % (label, diagnosticValue(value)))
        return None
    return number


def diagnosticValue(value):
    text = str(value)
    if len(text) <= MAX_DIAGNOSTIC_LENGTH:
        return text
    return text[:MAX_DIAGNOSTIC_LENGTH] + '... [truncated]'


def validTCPAddress(value):
    address = str(value).strip()
    separator = address.rfind(':')
    if separator <= 0 or separator == len(address) - 1:
        return None
    if boundedInteger(address[separator + 1:], 1, 65535, 'TCP port') is None:
        return None
    return address


# Variable output muting

group = 'Variable output'

varOutMuting = Event('Var Out Muting', {
    'title': 'Muting',
    'group': group,
    'order': next_seq(),
    'schema': {'type': 'boolean'},
})


def varOutMute(state):
    request('1Z' if state is True else '0Z', parseMuteResp)


def parseMuteResp(resp):
    if resp == 'Amt1':
        varOutMuting.emit(True)
    elif resp == 'Amt0':
        varOutMuting.emit(False)
    else:
        console.warn('Ignoring invalid mute response: %s' % diagnosticValue(resp))


Action('Var Out Muting', varOutMute, {
    'title': 'Muting',
    'group': group,
    'order': next_seq(),
    'schema': {'type': 'boolean'},
})


def poll_varOutMute():
    request('Z', parseMuteResp)


addPoller(poll_varOutMute, 14.9)


# Variable output volume

schema = {'type': 'integer', 'min': 0, 'max': 100, 'format': 'range'}
varOutVolume = Event('Var Out Vol', {
    'title': 'Vol.',
    'group': group,
    'order': next_seq(),
    'schema': schema,
})


def parseVolumeResp(resp):
    level = boundedResponse(resp, 'Vol', 0, 100, 'volume response')
    if level is not None:
        varOutVolume.emit(level)


def setVarOutVolume(arg):
    level = boundedInteger(arg, 0, 100, 'volume')
    if level is not None:
        request('%sV' % level, parseVolumeResp)


varOutVolAction = Action(
    'Var Out Vol',
    setVarOutVolume,
    {'title': 'Vol.', 'group': group, 'order': next_seq(), 'schema': schema},
)
varOutVolIncr = Action(
    'Var Out Vol Incr',
    lambda arg: request('+V', parseVolumeResp),
    {'title': 'Incr.', 'group': group, 'order': next_seq()},
)
varOutVolDecr = Action(
    'Var Out Vol Decr',
    lambda arg: request('-V', parseVolumeResp),
    {'title': 'Decr.', 'group': group, 'order': next_seq()},
)


def handleNudge(arg):
    if arg == 'Up':
        varOutVolIncr.call()
    elif arg == 'Down':
        varOutVolDecr.call()


Action('Var Out Vol Nudge', handleNudge, {
    'title': 'Nudge',
    'group': group,
    'order': next_seq(),
    'schema': {'type': 'string', 'enum': ['Up', 'Down']},
})

addPoller(lambda: request('V', parseVolumeResp), 8.77)


# Input gain and attenuation levels

def bindInputGainControls(name, input_number):
    input_group = name
    input_schema = {'type': 'integer', 'min': -24, 'max': 12, 'format': 'range'}
    gainEvent = Event('%s Gain' % name, {
        'title': 'Gain',
        'group': input_group,
        'order': next_seq(),
        'schema': input_schema,
    })

    def parseGainResp(resp):
        decibels = gainResponse(resp, input_number, '%s gain response' % name)
        if decibels is not None:
            gainEvent.emit(decibels)

    def setGain(arg):
        decibels = boundedInteger(arg, -24, 12, '%s gain' % name)
        if decibels is not None:
            request('%s*%sG' % (input_number, decibels), parseGainResp)

    Action(
        '%s Gain' % name,
        setGain,
        {'title': 'Gain', 'group': input_group, 'order': next_seq(), 'schema': input_schema},
    )
    Action(
        '%s Gain Incr' % name,
        lambda arg: request('%s+G' % input_number, parseGainResp),
        {'title': 'Incr.', 'group': input_group, 'order': next_seq()},
    )
    Action(
        '%s Gain Decr' % name,
        lambda arg: request('%s-G' % input_number, parseGainResp),
        {'title': 'Decr.', 'group': input_group, 'order': next_seq()},
    )
    addPoller(
        lambda: request('%sG' % input_number, parseGainResp),
        5.33 + (next_seq() % 10) // 3,
    )


bindInputGainControls('Mic 1', 1)
bindInputGainControls('Mic 2', 2)
bindInputGainControls('Line 3', 3)

ESC = '\x1B'


def bindDSPMute(addr, label):
    dsp_schema = {'type': 'boolean'}
    event = Event('%s Muting' % label, {
        'title': 'Muting',
        'group': label,
        'order': next_seq(),
        'schema': dsp_schema,
    })

    def parseMutingResp(resp):
        state = addressedBoundedResponse(
            resp,
            'DsM%s*' % addr,
            0,
            1,
            '%s mute response' % label,
        )
        if state is not None:
            event.emit(state == 1)

    Action(
        '%s Muting' % label,
        lambda arg: request(
            '%sM%s*%sAU' % (ESC, addr, '1' if arg is True else '0'),
            parseMutingResp,
        ),
        {'title': 'Gain', 'group': label, 'order': next_seq(), 'schema': dsp_schema},
    )
    addPoller(
        lambda: request('%sM%sAU' % (ESC, addr), parseMutingResp),
        5.33 + (next_seq() % 10) // 3,
    )


def bindDSPLevel(addr, label):
    dsp_schema = {'type': 'number'}
    event = Event('%s Level' % label, {
        'title': 'Level',
        'group': label,
        'order': next_seq(),
        'schema': dsp_schema,
    })

    def parseLevelResp(resp):
        tenths = addressedBoundedResponse(
            resp,
            'DsG%s*' % addr,
            -1000,
            1000,
            '%s level response' % label,
        )
        if tenths is not None:
            event.emit(tenths / 10.0)

    def setLevel(arg):
        level = boundedNumber(arg, -100, 100, '%s level' % label)
        if level is not None:
            request('%sG%s*%sAU' % (ESC, addr, int(level * 10)), parseLevelResp)

    Action(
        '%s Level' % label,
        setLevel,
        {'title': 'Gain', 'group': label, 'order': next_seq(), 'schema': dsp_schema},
    )
    addPoller(
        lambda: request('%sG%sAU' % (ESC, addr), parseLevelResp),
        5.33 + (next_seq() % 10) // 3,
    )


bindDSPMute('60002', 'Fixed Out L')
bindDSPLevel('60002', 'Fixed Out L')
bindDSPMute('60003', 'Fixed Out R')
bindDSPLevel('60003', 'Fixed Out R')


# Communications diagnostics

local_event_Connected = LocalEvent({'group': 'Comms', 'order': 9999 + next_seq()})
local_event_Received = LocalEvent({'group': 'Comms', 'order': 9999 + next_seq()})
local_event_Sent = LocalEvent({'group': 'Comms', 'order': 9999 + next_seq()})
local_event_Disconnected = LocalEvent({'group': 'Comms', 'order': 9999 + next_seq()})
local_event_Timeout = LocalEvent({'group': 'Comms', 'order': 9999 + next_seq()})


def connected():
    global comms_connected

    if shutting_down:
        return
    comms_connected = True
    local_event_Connected.emit()
    local_event_Status.emit({'level': 0, 'message': 'OK'})
    startPollers()


def received(data):
    if shutting_down:
        return
    if comms_connected:
        local_event_Status.emit({'level': 0, 'message': 'OK'})

    text = str(data)
    if len(text) > MAX_RECEIVED_EVENT_LENGTH:
        text = text[:MAX_RECEIVED_EVENT_LENGTH] + '... [truncated]'
    local_event_Received.emit(text)


def sent(data):
    if shutting_down:
        return
    local_event_Sent.emit(data)


def disconnected():
    global comms_connected

    comms_connected = False
    stopPollers()
    if shutting_down:
        return
    tcp.clearQueue()
    local_event_Status.emit({'level': 2, 'message': 'Missing'})
    local_event_Disconnected.emit()


def timeout():
    global comms_connected

    if shutting_down:
        return

    comms_connected = False
    stopPollers()
    pendingRequests = tcp.getQueueLength()
    tcp.clearQueue()
    tcp.drop()

    if pendingRequests:
        console.warn('Request timed out; discarded %s queued requests.' % pendingRequests)
    local_event_Status.emit({'level': 2, 'message': 'Missing'})
    local_event_Timeout.emit()


tcp = TCP(
    connected=connected,
    received=received,
    sent=sent,
    disconnected=disconnected,
    sendDelimiters='\r',
    receiveDelimiters='\r\n',
    timeout=timeout,
)
