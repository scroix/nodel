/*
 * Demo JavaScript recipe: a greeter node (GraalJS host).
 *
 * Demonstrates the core JS authoring surface — a parameter, local actions
 * (both naming-convention and programmatic styles), a local event and the
 * managed Timer helper. Conceptually identical to a Python recipe: only the
 * syntax changes.
 *
 * NOTE: declare bindings with 'var' or 'function' (not 'let'/'const') so
 * they land on the global object where the host discovers them.
 */

var param_Greeting = Parameter({ title: 'Greeting', schema: { type: 'string', hint: 'Hello' } });

var local_event_Greeted = LocalEvent({ title: 'Greeted', group: 'Greeter', schema: { type: 'object', properties: {
    name: { type: 'string', order: 1 },
    message: { type: 'string', order: 2 } } } });

var greetCount = 0;

// naming-convention style (like Python's 'def local_action_Greet(arg)')
function local_action_Greet(arg) {
    var name = arg || 'world';
    var message = (param_Greeting || 'Hello') + ', ' + name + '!';
    greetCount++;
    console.info(message + ' (greeting #' + greetCount + ')');
    local_event_Greeted.emit({ name: name, message: message });
}

// programmatic style with metadata (like Python's '@local_action({...})')
createLocalAction('Reset', function () {
    greetCount = 0;
    console.info('Greeting counter reset');
}, { title: 'Reset', group: 'Greeter', order: 2 });

// a managed timer (released automatically on node restart)
var heartbeat = new Timer(function () {
    console.log('heartbeat — greetings so far: ' + greetCount);
}, 60, 60);

function main() {
    console.info('Greeter node started (greeting: "' + (param_Greeting || 'Hello') + '")');
}
