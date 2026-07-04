# Smoke-test fixture: producer node.
# Pairs with the 'smoketest-consumer' fixture. See SMOKETEST.md at the repository root.
#
# Emits a 'Ping' local event whenever the 'sendPing' action is called, so a consumer
# node bound to the Ping event can prove end-to-end action -> event propagation.

param_label = Parameter({'title': 'Label', 'schema': {'type': 'string'},
                         'desc': 'Optional label prefixed to every ping value.'})

local_event_Status = LocalEvent({'title': 'Status', 'group': 'Status', 'schema': {'type': 'string'}})
local_event_Ping = LocalEvent({'title': 'Ping', 'group': 'Ping', 'schema': {'type': 'string'}})

@local_action({'title': 'Send Ping', 'group': 'Ping', 'schema': {'type': 'string'}})
def sendPing(arg):
    value = arg if arg else 'ping'
    if param_label:
        value = '%s: %s' % (param_label, value)
    local_event_Ping.emit(value)
    console.info('Ping sent: %s' % value)

def main():
    console.info('Smoke producer started')
    local_event_Status.emit('Ready')
