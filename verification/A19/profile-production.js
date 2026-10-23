async (page) => {
  const session = await page.context().newCDPSession(page);
  await session.send('Profiler.enable');
  await session.send('Profiler.start');
  await new Promise(resolve => setTimeout(resolve, 2000));
  const result = await session.send('Profiler.stop');
  await session.send('Runtime.terminateExecution');
  const time = new Map();
  for (let index = 0; index < (result.profile.samples || []).length; index++) {
    const id = result.profile.samples[index];
    time.set(id, (time.get(id) || 0) + result.profile.timeDeltas[index]);
  }
  return result.profile.nodes.map(node => ({ ...node.callFrame, microseconds: time.get(node.id) || 0, children: node.children })).sort((a, b) => b.microseconds - a.microseconds).slice(0, 25);
}
