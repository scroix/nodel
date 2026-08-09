# Experimental Python 3 port of museumsvictoria/nodel-recipes
# "AMX beacon receiver" at 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
"""Receive AMX beacon signals from multicast address 239.255.250.250:9131."""

import os
import java

SimpleName = java.type('org.nodel.SimpleName')

param_EnableReceiver = Parameter({
    'title': 'Enable multicast receiver',
    'group': 'Comms',
    'schema': {'type': 'boolean'},
})

eventsByUUID = {}
uuidByBindingName = {}
multicast_receiver = None

MAX_PACKET_LENGTH = 4096
MAX_DISCOVERED_BEACONS = 128

registry_event = Event('AMX Beacon Registry', {
    'group': 'Discovery',
    'schema': {'type': 'array', 'items': {'type': 'string'}},
})


def multicast_ready():
    print('AMX beacon receiver started.')


def multicast_received(source, data):
    if not data.startswith('AMX'):
        return
    if len(data) > MAX_PACKET_LENGTH:
        console.warn('Ignoring oversized AMX beacon packet.')
        return
    console.info('recv: [%s]' % data)
    parseAMXBPacket(source, data)


def main():
    global multicast_receiver

    print('AMX beacon driver loaded.')
    if param_EnableReceiver is not True:
        clearRegistry()
        console.warn(
            'AMX multicast receiver disabled and discovery registry cleared; '
            'set EnableReceiver to true to listen.'
        )
        return

    loadRegistry()
    multicast_receiver = UDP(
        source='239.255.250.250:9131',
        dest=None,
        ready=multicast_ready,
        received=multicast_received,
    )


@at_cleanup
def close_receiver():
    if multicast_receiver is not None:
        multicast_receiver.close()


def parseAMXBPacket(source, data):
    parts = data.split('<-')
    info = {}
    plainInfo = {}
    uuid = None

    for part in parts:
        part = part.strip()
        if part.endswith('>'):
            part = part[:-1]

        splitPos = part.find('=')
        if splitPos < 0:
            continue

        name = part[:splitPos]
        value = part[splitPos + 1:]
        plainName = plainFieldName(name)
        if plainName == 'uuid':
            uuid = value

        info[name] = value
        plainInfo[plainName] = value

    if uuid is None:
        console.warn('Ignoring AMX beacon without a UUID.')
        return

    bindingName = reducedUUID(uuid)
    if bindingName is None:
        console.warn('Ignoring AMX beacon with an invalid UUID.')
        return

    existingUUID = uuidByBindingName.get(bindingName)
    if existingUUID is not None and existingUUID != uuid:
        console.warn('Ignoring AMX beacon UUID that collides with %s.' % existingUUID)
        return

    info['SourceAddress'] = source
    plainInfo[plainFieldName('SourceAddress')] = source

    event = eventsByUUID.get(uuid)
    if event is None:
        if existingUUID is None and len(uuidByBindingName) >= MAX_DISCOVERED_BEACONS:
            console.warn('Ignoring AMX beacon because the discovery limit was reached.')
            return

        metadata = {'title': '%s beacon' % uuid}
        groupList = []

        make = plainInfo.get('make')
        if make:
            groupList.append(make)

        model = plainInfo.get('model')
        if model:
            groupList.append(model)

        if groupList:
            metadata['group'] = ' - '.join(groupList)

        props = {}
        for key in info:
            props[plainFieldName(key)] = {'title': key, 'type': 'string'}

        metadata['schema'] = {
            'type': 'object',
            'title': 'Beacon info',
            'properties': props,
        }

        event = Event('%s beacon' % uuid, metadata)
        print('Added beacon info from new device. Metadata was %s' % metadata)
        eventsByUUID[uuid] = event
        if existingUUID is None:
            uuidByBindingName[bindingName] = uuid
            registry_event.emit(sorted(uuidByBindingName.values()))
            registry_event.persistNow()

    event.emit(plainInfo)

    key = '%s IP Address' % uuid
    ipSignal = lookup_local_event(key)
    if not ipSignal:
        ipSignal = create_local_event(key, {
            'group': 'Discovery',
            'order': next_seq(),
            'schema': {'type': 'string'},
        })

    colonIndex = source.rfind(':')
    ipAddress = source[:colonIndex] if colonIndex >= 0 else source
    ipSignal.emit(ipAddress)


def plainFieldName(rawName):
    fieldname = []
    for char in rawName:
        codepoint = ord(char)
        if (0x30 <= codepoint <= 0x39 or
                0x41 <= codepoint <= 0x5A or
                0x61 <= codepoint <= 0x7A):
            fieldname.append(char)
        else:
            fieldname.append('_')
    return ''.join(fieldname).lower()


def reducedUUID(uuid):
    if not 1 <= len(uuid) <= 80:
        return None

    reduced = []
    previous = None
    for char in uuid:
        isAsciiLetter = 'A' <= char <= 'Z' or 'a' <= char <= 'z'
        if isAsciiLetter or '0' <= char <= '9':
            reduced.append(char.lower())
        elif char not in '_-' or char == '-' and previous == '-':
            return None
        previous = char

    return ''.join(reduced) or None


def loadRegistry():
    savedUUIDs = registry_event.getArg()
    if savedUUIDs is None:
        return

    for savedUUID in savedUUIDs:
        uuid = str(savedUUID)
        bindingName = reducedUUID(uuid)
        if bindingName is None or bindingName in uuidByBindingName:
            continue
        if len(uuidByBindingName) >= MAX_DISCOVERED_BEACONS:
            break
        uuidByBindingName[bindingName] = uuid


def clearRegistry():
    metadataDir = metadataDirectoryFor(__file__)
    if os.path.islink(metadataDir):
        console.warn('Could not safely clear persisted AMX beacon events; registry retained.')
        return False

    dynamicSeedNames = set()
    savedUUIDs = registry_event.getArg()
    if savedUUIDs is not None:
        for savedUUID in savedUUIDs:
            uuid = str(savedUUID)
            if reducedUUID(uuid) is None:
                continue
            dynamicSeedNames.add(eventSeedName('%s beacon' % uuid))
            dynamicSeedNames.add(eventSeedName('%s IP Address' % uuid))

    registrySeedName = eventSeedName('AMX Beacon Registry')
    try:
        if os.path.isdir(metadataDir):
            if os.path.realpath(metadataDir) != metadataDir:
                raise OSError('Unsafe metadata directory')

            for filename in sorted(dynamicSeedNames):
                seedPath = os.path.join(metadataDir, filename)
                if os.path.lexists(seedPath):
                    os.remove(seedPath)

            registrySeedPath = os.path.join(metadataDir, registrySeedName)
            if os.path.lexists(registrySeedPath):
                os.remove(registrySeedPath)
    except OSError:
        console.warn('Could not clear persisted AMX beacon events; registry retained.')
        return False

    registry_event.emit([])
    registry_event.persistNow()
    return True


def eventSeedName(eventName):
    return '%s.event.json' % SimpleName(eventName).getReducedForMatchingName()


def metadataDirectoryFor(scriptFile):
    scriptDir = os.path.realpath(os.path.dirname(os.path.abspath(scriptFile)))
    return os.path.join(scriptDir, '.nodel')
