'''
Demo Python 3 recipe: a simple TCP device node.

Drives a line-based TCP device (e.g. a projector or switcher on a telnet-style
port). Demonstrates parameters, local actions, local events and the managed TCP
helper — written with Python-3-only syntax (f-strings, dict.items(), print()).
'''

DEFAULT_ADDRESS = '127.0.0.1:4999'

param_Address = Parameter({'title': 'Device address (host:port)', 'schema': {'type': 'string', 'hint': DEFAULT_ADDRESS}})

# simple command table for a hypothetical device
COMMANDS = {
    'PowerOn': 'PWR ON',
    'PowerOff': 'PWR OFF',
    'Status': 'STATUS?',
}

local_event_Connected = LocalEvent({'title': 'Connected', 'group': 'Comms', 'order': 1})
local_event_Disconnected = LocalEvent({'title': 'Disconnected', 'group': 'Comms', 'order': 2})
local_event_Received = LocalEvent({'title': 'Received', 'group': 'Comms', 'schema': {'type': 'string'}, 'order': 3})
local_event_CommandSent = LocalEvent({'title': 'Command Sent', 'group': 'Device', 'schema': {'type': 'string'}, 'order': 4})


def tcp_connected():
    print('TCP connected')
    local_event_Connected.emit(None)


def tcp_received(data):
    print(f'TCP received: {data}')
    local_event_Received.emit(data)


def tcp_disconnected():
    print('TCP disconnected')
    local_event_Disconnected.emit(None)


tcp = TCP(connected=tcp_connected, received=tcp_received, disconnected=tcp_disconnected)


@local_action({'title': 'Send Raw', 'group': 'Device', 'schema': {'type': 'string'}, 'order': 1})
def SendRaw(arg):
    print(f'Sending raw command: {arg}')
    tcp.send(arg)
    local_event_CommandSent.emit(arg)


@local_action({'title': 'Power On', 'group': 'Device', 'order': 2})
def PowerOn(arg=None):
    SendRaw.call(COMMANDS['PowerOn'])


@local_action({'title': 'Power Off', 'group': 'Device', 'order': 3})
def PowerOff(arg=None):
    SendRaw.call(COMMANDS['PowerOff'])


@local_action({'title': 'List Commands', 'group': 'Device', 'order': 4})
def ListCommands(arg=None):
    # Python 3 'items()' (Jython recipes used 'iteritems()')
    for name, wire in COMMANDS.items():
        print(f'command {name!r} -> {wire!r}')


def main():
    address = param_Address or DEFAULT_ADDRESS
    print(f'TCP device node starting (address: {address})')
    tcp.setDest(address)
