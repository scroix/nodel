# Experimental Python 3 port of museumsvictoria/nodel-recipes
# "Frontend/Mk2" at 42cec03bdd0e43fc9252fe3dd6f6900f355f21a3.
"""Create Nodel bindings from a bounded node-local frontend definition."""

import json
from pathlib import Path
import xml.etree.ElementTree as ET
import java


SimpleName = java.type('org.nodel.SimpleName')
Nodel = java.type('org.nodel.core.Nodel')

MAX_FRONTEND_FILE_BYTES = 256 * 1024
MAX_XML_ELEMENTS = 2048
MAX_XML_DEPTH = 64
MAX_DYNAMIC_BINDINGS = 512
MAX_BINDING_NAME_LENGTH = 256
MAX_GROUP_NAME_LENGTH = 512
MAX_SCHEMA_COUNT = 256
MAX_SCHEMA_DEPTH = 32
MAX_LOCAL_ONLY_TEXT_LENGTH = 16 * 1024
MAX_LOCAL_ONLY_NAMES = 512


param_suggestedNode = Parameter({
    'title': 'Suggested Node',
    'desc': 'A suggestion can be made when remote bindings are created.',
    'schema': {'type': 'string', 'format': 'node'},
})
param_localOnlySignals = Parameter({
    'title': 'Local only signals',
    'desc': 'No remote bindings are configured for these comma-separated signals.',
    'schema': {'type': 'string'},
})
param_localOnlyActions = Parameter({
    'title': 'Local only actions',
    'desc': 'No remote bindings are configured for these comma-separated actions.',
    'schema': {'type': 'string'},
})


local_event_Clock = LocalEvent({
    'title': 'Clock',
    'group': 'General',
    'schema': {'type': 'string'},
})
timer_clock = Timer(lambda: local_event_Clock.emit(str(date_now())), 1)


workingDir = Path(str(_node.getRoot())).resolve()
schemaMap = {}
localOnlySignals = set()
localOnlyActions = set()
dynamicActions = {}
dynamicEvents = {}
remoteActions = {}
remoteEvents = {}
remoteEventHandlers = {}


def nodePath(*parts):
    candidate = workingDir.joinpath(*parts).resolve()
    try:
        candidate.relative_to(workingDir)
    except ValueError:
        raise ValueError('Frontend path escapes the node directory.')
    return candidate


def readBoundedBytes(path, label):
    candidate = Path(path)
    size = candidate.stat().st_size
    if size > MAX_FRONTEND_FILE_BYTES:
        raise ValueError('%s exceeds the %s byte limit.' % (label, MAX_FRONTEND_FILE_BYTES))
    with candidate.open('rb') as source:
        data = source.read(MAX_FRONTEND_FILE_BYTES + 1)
    if len(data) > MAX_FRONTEND_FILE_BYTES:
        raise ValueError('%s exceeds the %s byte limit.' % (label, MAX_FRONTEND_FILE_BYTES))
    return data


def readBoundedText(path, label):
    text = readBoundedBytes(path, label).decode('utf-8-sig')
    if '\x00' in text:
        raise ValueError('%s must be UTF-8 text.' % label)
    return text


def schemaDepth(value, depth=0):
    if depth > MAX_SCHEMA_DEPTH:
        raise ValueError('Schema nesting exceeds the %s level limit.' % MAX_SCHEMA_DEPTH)
    if isinstance(value, dict):
        for key, child in value.items():
            schemaDepth(key, depth + 1)
            schemaDepth(child, depth + 1)
    elif isinstance(value, list):
        for child in value:
            schemaDepth(child, depth + 1)


def boundedBindingName(value):
    if value is None:
        return None
    name = str(value).strip()
    if not name:
        return None
    if len(name) > MAX_BINDING_NAME_LENGTH or any(c in name for c in '\x00\r\n'):
        raise ValueError('Frontend binding name is oversized or invalid.')
    if not str(SimpleName(name).getReducedForMatchingName()):
        raise ValueError('Frontend binding name has no usable characters.')
    return name


def reducedNames(value, label='Local only signals/actions'):
    text = str(value or '')
    if len(text) > MAX_LOCAL_ONLY_TEXT_LENGTH:
        raise ValueError('%s exceeds the %s character limit.' % (
            label, MAX_LOCAL_ONLY_TEXT_LENGTH))
    items = text.split(',')
    if len(items) > MAX_LOCAL_ONLY_NAMES:
        raise ValueError('%s contains too many names.' % label)

    result = set()
    for item in items:
        name = boundedBindingName(item)
        if name is not None:
            result.add(str(SimpleName(name).getReducedForMatchingName()))
    return result


def loadSchemas(text):
    schemas = json.loads(text)
    if not isinstance(schemas, dict):
        raise ValueError('content/schemas.json must contain an object.')
    if len(schemas) > MAX_SCHEMA_COUNT:
        raise ValueError('content/schemas.json contains too many schemas.')
    schemaDepth(schemas)

    loaded = []
    for rawKey, schema in schemas.items():
        key = boundedBindingName(rawKey)
        if key is None or not isinstance(schema, dict):
            raise ValueError('Every frontend schema must be a named object.')
        schemaMap[key] = schema
        loaded.append(key)

    if loaded:
        console.info('Loaded schemas: %s' % ', '.join(loaded))
    else:
        console.warn('(no schema mapping info was present)')


def loadIndexFile(xmlFile):
    text = readBoundedText(xmlFile, 'content/index.xml')
    upper = text.upper()
    if '<!DOCTYPE' in upper or '<!ENTITY' in upper:
        raise ValueError('DTD and entity declarations are not supported.')
    root = ET.fromstring(text)
    elementCount = [0]
    bindingPlan = []
    plannedActions = set()
    plannedEvents = set()
    suggestedNode = None if is_blank(param_suggestedNode) else boundedBindingName(param_suggestedNode)

    def planAction(name, group, elementType):
        if name is None or lookup_local_action(name) is not None:
            return
        key = str(SimpleName(name).getReducedForMatchingName())
        if key in plannedActions:
            return
        if len(bindingPlan) >= MAX_DYNAMIC_BINDINGS:
            raise ValueError('Frontend defines too many dynamic bindings.')
        plannedActions.add(key)
        bindingPlan.append(('action', name, group, elementType))

    def planEvent(name, group, elementType):
        if name is None or lookup_local_event(name) is not None:
            return
        key = str(SimpleName(name).getReducedForMatchingName())
        if key in plannedEvents:
            return
        if len(bindingPlan) >= MAX_DYNAMIC_BINDINGS:
            raise ValueError('Frontend defines too many dynamic bindings.')
        plannedEvents.add(key)
        bindingPlan.append(('event', name, group, elementType))

    def registerAction(name, group, elementType):
        if str(SimpleName(name).getReducedForMatchingName()) in localOnlyActions:
            handler = lambda arg: None
        else:
            remoteAction = create_remote_action(name, suggestedNode=suggestedNode)
            remoteActions[name] = remoteAction
            handler = lambda arg, target=remoteAction: target.call(arg)

        action = Action(name, handler, {
            'group': group,
            'order': next_seq(),
            'schema': schemaMap.get('%s_action' % elementType, schemaMap.get(elementType)),
        })
        dynamicActions[name] = action

    def registerEvent(name, group, elementType):
        event = Event(name, {
            'group': group,
            'order': next_seq(),
            'schema': schemaMap.get('%s_signal' % elementType, schemaMap.get(elementType)),
        })
        dynamicEvents[name] = event

        if str(SimpleName(name).getReducedForMatchingName()) not in localOnlySignals:
            def remoteEventHandler(arg=None, target=event):
                target.emit(arg)

            remoteEvent = create_remote_event(
                name,
                remoteEventHandler,
                suggestedNode=suggestedNode,
            )
            remoteEvents[name] = remoteEvent
            remoteEventHandlers[name] = remoteEventHandler

    def explore(group, element, depth):
        if depth > MAX_XML_DEPTH:
            raise ValueError('Frontend XML nesting exceeds the %s level limit.' % MAX_XML_DEPTH)
        elementCount[0] += 1
        if elementCount[0] > MAX_XML_ELEMENTS:
            raise ValueError('Frontend XML contains too many elements.')

        elementType = boundedBindingName(element.tag)
        join = boundedBindingName(element.get('join'))
        action = boundedBindingName(element.get('action')) or join
        actionOn = boundedBindingName(element.get('action-on'))
        actionOff = boundedBindingName(element.get('action-off'))
        event = boundedBindingName(element.get('event')) or join
        title = element.get('title')
        if title is None and elementType == 'title':
            title = element.text
        title = None if title in (None, 'row', 'column') else str(title).strip()

        if title:
            thisGroup = title if not group else '%s - %s' % (group, title)
            if len(thisGroup) > MAX_GROUP_NAME_LENGTH:
                raise ValueError('Frontend group name exceeds the %s character limit.' % MAX_GROUP_NAME_LENGTH)
        else:
            thisGroup = group

        planAction(action, thisGroup, elementType)
        planAction(actionOn, thisGroup, elementType)
        planAction(actionOff, thisGroup, elementType)
        planEvent(event, thisGroup, elementType)

        for child in element:
            explore(thisGroup, child, depth + 1)

    explore('', root, 0)

    for kind, name, group, elementType in bindingPlan:
        if kind == 'action':
            registerAction(name, group, elementType)
        else:
            registerEvent(name, group, elementType)


def main():
    global localOnlySignals, localOnlyActions

    try:
        localOnlySignals = reducedNames(param_localOnlySignals, 'Local only signals')
        localOnlyActions = reducedNames(param_localOnlyActions, 'Local only actions')
        indexFile = nodePath('content', 'index.xml')
        if not indexFile.is_file():
            console.warn('No "content/index.xml" file exists; use "Create from sample" Action.')
            return

        schemasFile = nodePath('content', 'schemas.json')
        if schemasFile.is_file():
            loadSchemas(readBoundedText(schemasFile, 'content/schemas.json'))
        loadIndexFile(indexFile)
    except (OSError, UnicodeError, ValueError, ET.ParseError, json.JSONDecodeError, RecursionError) as exc:
        console.error('Frontend definition could not be loaded: %s' % exc)


def defaultSampleFrontendPath():
    return Path(str(Nodel.getHostPath())).joinpath(
        '.nodel', 'webui_cache', 'index-sample.xml')


def createFromSample(restart=True, sourcePath=None):
    source = defaultSampleFrontendPath() if sourcePath is None else Path(sourcePath)
    try:
        data = readBoundedBytes(source, 'frontend sample')
        contentDir = nodePath('content')
        destination = nodePath('content', 'index.xml')
        if destination.exists():
            console.warn('index.xml file already exists!')
            return False
        contentDir.mkdir(parents=True, exist_ok=True)
        with destination.open('xb') as output:
            output.write(data)
    except (OSError, ValueError) as exc:
        console.error('Could not create content/index.xml: %s' % exc)
        return False

    console.info('"content/index.xml" created successfully from sample.')
    if restart:
        _node.restart()
    return True


@local_action({'title': 'Create Frontend from sample and restart (will not overwrite existing)'})
def CreateFromSample():
    createFromSample()
