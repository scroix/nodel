'''
Demo Python 3 recipe: a timer / scheduler node.

Emits a periodic Tick event whose interval is a saved parameter and can be
started, stopped and re-paced with actions. Demonstrates parameters, local
actions, local events and the managed Timer helper — written with
Python-3-only syntax (f-strings, print()).
'''

DEFAULT_INTERVAL = 5.0

param_IntervalSeconds = Parameter({'title': 'Tick interval (seconds)', 'schema': {'type': 'number', 'hint': str(DEFAULT_INTERVAL)}})

local_event_Tick = LocalEvent({'title': 'Tick', 'group': 'Scheduler', 'schema': {'type': 'object', 'properties': {
    'count': {'type': 'integer', 'order': 1},
    'interval': {'type': 'number', 'order': 2}}}})
local_event_Running = LocalEvent({'title': 'Running', 'group': 'Scheduler', 'schema': {'type': 'boolean'}})

tick_count = 0


def handle_tick():
    global tick_count
    tick_count += 1
    print(f'tick #{tick_count} (every {timer.interval}s)')
    local_event_Tick.emit({'count': tick_count, 'interval': timer.interval})


timer = Timer(handle_tick, DEFAULT_INTERVAL, first_delay_seconds=1, stopped=True)


@local_action({'title': 'Start', 'group': 'Scheduler', 'order': 1})
def Start(arg=None):
    print('Scheduler starting')
    timer.start()
    local_event_Running.emit(True)


@local_action({'title': 'Stop', 'group': 'Scheduler', 'order': 2})
def Stop(arg=None):
    print('Scheduler stopping')
    timer.stop()
    local_event_Running.emit(False)


@local_action({'title': 'Set Interval', 'group': 'Scheduler', 'schema': {'type': 'number'}, 'order': 3})
def SetInterval(arg):
    seconds = float(arg)
    print(f'Interval changing to {seconds}s')
    timer.set_interval(seconds)


@local_action({'title': 'Tick Now', 'group': 'Scheduler', 'order': 4})
def TickNow(arg=None):
    handle_tick()


def main():
    interval = float(param_IntervalSeconds or DEFAULT_INTERVAL)
    timer.set_interval(interval)
    print(f'Scheduler node started (interval: {interval}s, use Start/Stop actions)')
