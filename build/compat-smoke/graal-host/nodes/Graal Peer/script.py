local_event_Ping = LocalEvent({'title': 'Ping', 'schema': {'type': 'string'}})
remote_action_RemotePoke = RemoteAction({'title': 'Remote Poke', 'schema': {'type': 'string'}})

@local_action({'schema': {'type': 'string'}})
def SendPing(arg):
    print(f'ping sent: {arg}')
    local_event_Ping.emit(arg)

@local_action({'schema': {'type': 'string'}})
def Poke(arg):
    print(f'poked: {arg}')

@local_action({'schema': {'type': 'string'}})
def PokePeer(arg):
    print(f'poking peer: {arg}')
    remote_action_RemotePoke.call(arg)

def remote_event_PeerPing(arg):
    print(f'peer ping received: {arg}')

def main():
    print('graal peer started')
