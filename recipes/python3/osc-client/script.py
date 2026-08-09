# Experimental Python 3 port pinned from museumsvictoria/nodel-recipes
# commit 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
"""Unidirectional OSC client with parameter-defined actions."""

import struct


DEFAULT_IP_ADDRESS = '127.0.0.1'
DEFAULT_PORT = 9000
DEFAULT_PATTERN = '/foo/bar'
MAX_PATTERNS = 64
MAX_LABEL_LENGTH = 128
MAX_OSC_STRING_BYTES = 1024
NO_ARGUMENT = object()


param_ipAddress = Parameter({'title': 'IP address', 'schema': {
    'type': 'string', 'hint': DEFAULT_IP_ADDRESS}, 'order': next_seq()})
param_port = Parameter({'title': 'Port', 'schema': {
    'type': 'integer', 'hint': DEFAULT_PORT}, 'order': next_seq()})
param_patterns = Parameter({'title': 'Patterns', 'order': next_seq(), 'schema': {
    'type': 'array', 'items': {'type': 'object', 'title': 'Pattern', 'properties': {
        'label': {'type': 'string', 'title': 'Label', 'hint': 'Foobar', 'order': next_seq()},
        'address': {'type': 'string', 'title': 'Address', 'hint': DEFAULT_PATTERN,
                    'order': next_seq()}
    }}}})


def oscString(value):
    encoded = str(value).encode('utf-8')
    if not encoded or len(encoded) > MAX_OSC_STRING_BYTES or b'\0' in encoded:
        raise ValueError('OSC strings must contain 1-%s non-NUL bytes' % MAX_OSC_STRING_BYTES)
    encoded += b'\0'
    return encoded + (b'\0' * ((-len(encoded)) % 4))


def encodeOscMessage(address, arg=NO_ARGUMENT):
    address = str(address).strip()
    if not address.startswith('/'):
        raise ValueError('OSC addresses must start with /')

    if arg is NO_ARGUMENT:
        typeTag = ','
        payload = b''
    elif isinstance(arg, bool):
        typeTag = ',i'
        payload = struct.pack('>i', int(arg))
    elif isinstance(arg, int):
        typeTag = ',i'
        payload = struct.pack('>i', arg)
    elif isinstance(arg, float):
        typeTag = ',f'
        payload = struct.pack('>f', arg)
    else:
        typeTag = ',s'
        payload = oscString(arg)

    return oscString(address) + oscString(typeTag) + payload


def getNumber(value):
    if not isinstance(value, str):
        return value
    try:
        float(value)
    except ValueError:
        return value
    return int(value) if value.isdecimal() else float(value)


def normaliseActionArgument(arg):
    if isinstance(arg, int) and not isinstance(arg, bool) and arg > 1:
        return float(arg) / 100
    return getNumber(arg)


def sendOsc(address, arg=NO_ARGUMENT):
    if not _udpReady:
        console.warn('OSC message rejected: UDP is not ready')
        return False
    try:
        payload = encodeOscMessage(address, arg)
    except (OverflowError, struct.error, TypeError, ValueError) as exc:
        console.warn('OSC message rejected: %s' % exc)
        return False

    # ManagedUDP exposes its binary-safe direct-char String API to scripts.
    udp.send(payload.decode('latin-1'))
    console.log('Sending OSC message to [%s].' % address)
    return True


def createClientAction(pattern):
    if pattern is None or not hasattr(pattern, 'get'):
        console.warn('Ignoring OSC pattern that is not an object')
        return
    label = str(pattern.get('label') or '').strip()
    address = str(pattern.get('address') or '').strip()
    if not label or len(label) > MAX_LABEL_LENGTH or not address.startswith('/'):
        console.warn('Ignoring OSC pattern with an invalid label or address')
        return
    try:
        oscString(address)
    except ValueError as exc:
        console.warn('Ignoring OSC pattern: %s' % exc)
        return
    if lookup_local_action(label) is not None:
        console.warn('Ignoring duplicate OSC action [%s]' % label)
        return

    def defaultHandler(arg):
        if arg is None or arg == '':
            sendOsc(address)
        else:
            sendOsc(address, normaliseActionArgument(arg))

    create_local_action(
        name=label,
        metadata={
            'title': label,
            'group': 'Patterns',
            'order': next_seq(),
            'schema': {'type': 'string', 'title': address}
        },
        handler=defaultHandler)


def createCustomAction():
    def genericOscHandler(message):
        if message is None or not hasattr(message, 'get'):
            console.warn('OSC custom action rejected: expected an object argument')
            return
        address = str(message.get('address') or DEFAULT_PATTERN).strip()
        rawArg = message.get('arg')
        if rawArg is None or rawArg == '':
            sendOsc(address)
        else:
            sendOsc(address, normaliseActionArgument(rawArg))

    create_local_action(
        'Custom',
        handler=genericOscHandler,
        metadata={
            'group': 'Custom',
            'title': 'Send a custom message',
            'schema': {'type': 'object', 'properties': {
                'address': {'type': 'string', 'title': 'Address', 'hint': DEFAULT_PATTERN,
                            'order': next_seq()},
                'arg': {'type': 'string', 'title': 'Argument', 'hint': '1',
                        'order': next_seq()}
            }},
            'order': next_seq()
        })


_udpReady = False


def udpReady():
    global _udpReady
    _udpReady = True
    console.info('UDP ready. %s' % udp.getDest())


udp = UDP(ready=udpReady)


def main(arg=None):
    ipAddress = str(param_ipAddress or DEFAULT_IP_ADDRESS).strip()
    port = int(param_port or DEFAULT_PORT)
    if not ipAddress or port < 1 or port > 65535:
        console.error('OSC destination is invalid')
        udp.close()
        return

    udp.setDest('%s:%s' % (ipAddress, port))
    createCustomAction()

    for index, pattern in enumerate(param_patterns or []):
        if index >= MAX_PATTERNS:
            console.warn('Only the first %s OSC patterns will be loaded' % MAX_PATTERNS)
            break
        createClientAction(pattern)
