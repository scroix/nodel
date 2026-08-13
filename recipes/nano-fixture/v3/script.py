"""Synthetic Nodel v3 fixture for the nano runtime profile."""

TCP_DEST = '127.0.0.1:7401'
UDP_DEST = '127.0.0.1:7402'

param_label = Parameter({
    'title': 'Label',
    'schema': {'type': 'string'},
})

local_event_pong = LocalEvent({'schema': {'type': 'string'}})
local_event_tick500 = LocalEvent({'schema': {'type': 'integer'}})
local_event_tick5s = LocalEvent({'schema': {'type': 'integer'}})
local_event_tcpEcho = LocalEvent({'schema': {'type': 'integer'}})
local_event_udpEcho = LocalEvent({'schema': {'type': 'integer'}})


@local_action({'schema': {'type': 'string'}})
def ping(arg):
    local_event_pong.emit(arg)


_tick500_seq = 0
_tick5s_seq = 0
_tcp_seq = 0
_udp_seq = 0
_tcp_connected = False
_udp_ready = False
_stopping = False


def emit_tick500():
    global _tick500_seq
    _tick500_seq += 1
    local_event_tick500.emit(_tick500_seq)


def emit_tick5s():
    global _tick5s_seq
    _tick5s_seq += 1
    local_event_tick5s.emit(_tick5s_seq)


def parse_echo(data, transport):
    value = str(data).strip()
    if not value.startswith('SEQ '):
        console.warn(f'Ignoring malformed {transport} echo: {value!r}')
        return None
    try:
        seq = int(value[4:])
    except ValueError:
        console.warn(f'Ignoring malformed {transport} sequence: {value!r}')
        return None
    if seq < 1:
        console.warn(f'Ignoring non-positive {transport} sequence: {seq}')
        return None
    return seq


def tcp_connected():
    global _tcp_connected
    if _stopping:
        return
    _tcp_connected = True
    console.info('Nano fixture TCP connected')


def tcp_disconnected():
    global _tcp_connected
    _tcp_connected = False
    if not _stopping:
        console.warn('Nano fixture TCP disconnected')


def tcp_received(data):
    seq = parse_echo(data, 'TCP')
    if seq is not None:
        local_event_tcpEcho.emit(seq)


def udp_ready():
    global _udp_ready
    if _stopping:
        return
    _udp_ready = True
    console.info('Nano fixture UDP ready')


def udp_received(source, data):
    seq = parse_echo(data, 'UDP')
    if seq is not None:
        local_event_udpEcho.emit(seq)


tcp = TCP(
    connected=tcp_connected,
    disconnected=tcp_disconnected,
    received=tcp_received,
    sendDelimiters='\n',
    receiveDelimiters='\n',
)
udp = UDP(ready=udp_ready, received=udp_received)


def send_tcp():
    global _tcp_seq
    if not _tcp_connected:
        return
    _tcp_seq += 1
    tcp.send(f'SEQ {_tcp_seq}')


def send_udp():
    global _udp_seq
    if not _udp_ready:
        return
    _udp_seq += 1
    udp.send(f'SEQ {_udp_seq}\n')


tick500_timer = Timer(emit_tick500, 0.5, firstDelayInSeconds=0.5, stopped=True)
tick5s_timer = Timer(emit_tick5s, 5, firstDelayInSeconds=5, stopped=True)
tcp_timer = Timer(send_tcp, 1, firstDelayInSeconds=1, stopped=True)
udp_timer = Timer(send_udp, 1, firstDelayInSeconds=1, stopped=True)
timers = (tick500_timer, tick5s_timer, tcp_timer, udp_timer)


@after_main
def start_fixture():
    global _stopping
    _stopping = False
    tcp.setDest(TCP_DEST)
    udp.setDest(UDP_DEST)
    for timer in timers:
        timer.start()
    console.info('Nano fixture started')


@at_cleanup
def stop_fixture():
    global _stopping, _tcp_connected, _udp_ready
    _stopping = True
    _tcp_connected = False
    _udp_ready = False
    for timer in timers:
        timer.stop()
    tcp.close()
    udp.close()
