local_event_Ping = LocalEvent({'title': 'Ping', 'schema': {'type': 'string'}})
remote_action_RemotePoke = RemoteAction({'title': 'Remote Poke', 'schema': {'type': 'string'}})

def local_action_SendPing(arg):
    console.info('ping sent: %s' % arg)
    local_event_Ping.emit(arg)

def local_action_Poke(arg):
    console.info('poked: %s' % arg)

def local_action_PokePeer(arg):
    console.info('poking peer: %s' % arg)
    remote_action_RemotePoke.call(arg)

def remote_event_PeerPing(arg):
    console.info('peer ping received: %s' % arg)

def main():
    console.info('jython peer started')
