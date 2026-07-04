# Smoke-test fixture: consumer node.
# Pairs with the 'smoketest-producer' fixture. See SMOKETEST.md at the repository root.
#
# Declares a remote event 'IncomingPing'. Once bound (via /REST/nodes/{name}/remote/save
# or the web UI) to the producer's 'Ping' event, every received ping is logged to the
# console and re-emitted as the 'Received' local event.

local_event_Received = LocalEvent({'title': 'Received', 'schema': {'type': 'string'}})

def remote_event_IncomingPing(arg):
    console.info('Received ping: %s' % arg)
    local_event_Received.emit(arg)

def main():
    console.info('Smoke consumer started')
