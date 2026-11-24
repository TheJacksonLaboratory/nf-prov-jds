const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/nextflow/prov/metadata-explorer.html'), 'utf8');
const core = html.split('<script id="explorer-code">')[1].split('function message(')[0];
function explorer(crate) {
    const context = vm.createContext({});
    vm.runInContext(core + `
        globalThis.api = {
            load(crate) { model = createModel(crate); analysis = createAnalysis(); },
            ancestors, cpuUsed, metricNumber, formatMetric,
            buildLineage,
            get model() { return model; }, get analysis() { return analysis; }
        };
    `, context);
    context.api.load(crate);
    return context.api;
}
const ref = id => ({ '@id': id });
const node = (id, type, fields = {}) => ({ '@id': id, '@type': type, name: id, ...fields });
function fixture() {
    return { '@graph': [
        node('./', 'Dataset', { mainEntity: ref('main.nf') }),
        node('main.nf', 'ComputationalWorkflow'),
        node('#run', 'CreateAction', { instrument: ref('main.nf'), object: ['input-a', 'input-b'].map(ref), result: ['out-a', 'out-b', 'out-join'].map(ref) }),
        ...['input-a', 'input-b', 'mid-a', 'mid-b', 'out-a', 'out-b', 'out-join'].map(id => node(id, 'File')),
        node('#step-first', 'HowToStep', { nextItem: ref('#step-final') }),
        node('#step-final', 'HowToStep'),
        node('#step-join', 'HowToStep'),
        node('#control-first', 'ControlAction', { instrument: ref('#step-first'), object: ['#a1', '#a2'].map(ref) }),
        node('#control-final', 'ControlAction', { instrument: ref('#step-final'), object: ['#b1', '#b2'].map(ref) }),
        node('#control-join', 'ControlAction', { instrument: ref('#step-join'), object: ref('#join') }),
        node('#a1', 'CreateAction', { object: ref('input-a'), result: ref('mid-a') }),
        node('#a2', 'CreateAction', { object: ref('input-b'), result: ref('mid-b') }),
        node('#b1', 'CreateAction', { object: ref('mid-a'), result: ref('out-a'), instrument: ref('#tool') }),
        node('#b2', 'CreateAction', { object: ref('mid-b'), result: ref('out-b') }),
        node('#join', 'CreateAction', { object: ['mid-a', 'mid-b'].map(ref), result: ref('out-join') }),
        node('#tool', 'SoftwareApplication')
    ] };
}

test('all outputs are selectable; ancestry excludes workflow aggregates, sibling samples and software-as-input', () => {
    const api = explorer(fixture()), upstream = api.ancestors('out-a');
    assert.equal(api.model.outputs.size, 3);
    for (const id of ['out-a', '#b1', 'mid-a', '#a1', 'input-a']) assert.ok(upstream.has(id), id);
    for (const id of ['#run', '#a2', '#b2', 'input-b', 'out-b', '#tool']) assert.ok(!upstream.has(id), id);
    assert.equal(api.analysis.groups.size, 3);
    assert.equal(api.analysis.groups.get('#step-first').tasks.length, 2);
    assert.equal(api.analysis.processEdges.filter(e => e.source === '#step-first' && e.target === '#step-final').length, 1);
    assert.equal(api.analysis.processEdges.find(e => e.target === '#step-final').kind, 'data');
});

test('fan-in and publication preserve both branches without adding downstream siblings', () => {
    const crate = fixture();
    crate['@graph'].push(
        node('#publish/join/final', 'CreateAction', { name: 'publish', object: ref('out-join'), result: ref('published') }),
        node('published', 'File')
    );
    const api = explorer(crate), upstream = api.ancestors('published');
    for (const id of ['input-a', 'input-b', '#a1', '#a2', 'mid-a', 'mid-b', '#join', 'out-join', '#publish/join/final', 'published']) assert.ok(upstream.has(id), id);
    assert.ok(!upstream.has('#b1'));
    assert.ok(!upstream.has('#b2'));
    assert.equal(api.analysis.groups.size, 3);
    assert.ok(!api.analysis.metrics.has('#publish/join/final'));
});

test('workflow aggregates are recognized even if the crate omits its mainEntity pointer', () => {
    const crate = fixture();
    delete crate['@graph'][0].mainEntity;
    const api = explorer(crate);
    assert.ok(!api.ancestors('out-a').has('#run'));
    assert.ok(!api.ancestors('out-a').has('input-b'));
});

test('channel-only links are visible in the process graph but never invent output ancestry', () => {
    const crate = fixture();
    crate['@graph'].find(e => e['@id'] === '#b1').object = [];
    crate['@graph'].find(e => e['@id'] === '#b2').object = [];
    const api = explorer(crate);
    assert.equal(api.analysis.processEdges.find(e => e.target === '#step-final').kind, 'channel');
    assert.ok(!api.ancestors('out-a').has('#a1'));
});

test('lineage defaults to process-level nodes and can expand to task detail', () => {
    const api = explorer(fixture());
    const overview = api.buildLineage('out-a');
    assert.equal(overview.nodes.filter(node => node.kind === 'action').map(node => node.id).sort().join(','), '#step-final,#step-first');
    assert.equal(overview.nodes.some(node => node.id === '#a1'), false);
    assert.ok(overview.edges.some(edge => edge.source === 'mid-a' && edge.target === '#step-final'));
    const detail = api.buildLineage('out-a', 'task');
    assert.ok(detail.nodes.some(node => node.id === '#a1'));
    assert.ok(detail.nodes.some(node => node.id === '#b1'));
    assert.equal(detail.nodes.some(node => node.id === '#step-first'), false);
});

test('lineage hides scalar and software support nodes unless requested', () => {
    const api = explorer(fixture());
    const overview = api.buildLineage('out-a');
    assert.equal(overview.software.size, 0);
    assert.equal(overview.nodes.some(node => node.id === '#tool'), false);
    const expanded = api.buildLineage('out-a', 'process', true, true);
    assert.ok(expanded.software.has('#tool'));
    assert.ok(expanded.nodes.some(node => node.id === '#tool'));
});

test('inverse generatedBy links, reference-only inputs and cycles remain navigable', () => {
    const crate = { '@graph': [
        node('./', 'Dataset'),
        node('#compute', 'Computation', { usedDataset: ref('file:///external/input'), generated: ref('result') }),
        node('result', 'Dataset'),
        node('final', 'Dataset', { generatedBy: ref('#compute') }),
        node('#loop', 'Computation', { usedDataset: ref('result'), generated: ref('result') })
    ] };
    const api = explorer(crate), upstream = api.ancestors('final');
    assert.ok(upstream.has('#compute'));
    assert.ok(upstream.has('file:///external/input'));
    assert.equal(api.model.referenceIds.has('file:///external/input'), true);
    assert.ok(api.ancestors('result').has('#loop'));
});

test('resource normalization retains zero, rejects missing values and distinguishes allocations', () => {
    const api = explorer(fixture());
    const metrics = new Map([['%cpu', 150], ['cpus', 2], ['peak_rss', 0], ['read_bytes', '-'], ['memory', 1024]]);
    assert.equal(api.cpuUsed(metrics), 75);
    assert.equal(api.metricNumber(metrics, 'peak_rss'), 0);
    assert.equal(api.metricNumber(metrics, 'read_bytes'), null);
    assert.equal(api.metricNumber(metrics, 'write_bytes'), null);
    assert.equal(api.cpuUsed(new Map([['%cpu', 0], ['cpus', 2]])), 0);
    assert.equal(api.cpuUsed(new Map([['%cpu', 150]])), null);
    assert.equal(api.formatMetric(1048576, 'peak_rss'), '1 MiB');
    assert.equal(api.formatMetric(1250, 'realtime'), '1.25 s');
    assert.equal(api.formatMetric(null, 'peak_rss'), 'Unavailable');
});
